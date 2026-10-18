package com.emas.window;

import com.emas.door.DoorSnapshot;
import com.emas.door.DoorStateHub;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Cached door reads have their own bounded workers, separate from slow window commands. */
public final class DoorHubHttpServer implements AutoCloseable {
    public interface WindowHandler { Response handle(LocalHttpRequest request) throws Exception; }
    public static final class Response {
        public final int status;
        public final String body;
        public Response(int status, String body) { this.status = status; this.body = body; }
    }
    private final ServerSocket server;
    private final Supplier<DoorSnapshot> snapshots;
    private final DoorStateHub.Clock clock;
    private final WindowHandler windowHandler;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor reads = pool("door-hub-http", 2, 8);
    private final ThreadPoolExecutor commands = pool("window-http-command", 1, 4);
    private volatile boolean closed;

    private static ThreadPoolExecutor pool(String name, int workers, int capacity) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public DoorHubHttpServer(int port, Supplier<DoorSnapshot> snapshots,
                             DoorStateHub.Clock clock, WindowHandler windowHandler) throws IOException {
        this.snapshots = snapshots; this.clock = clock; this.windowHandler = windowHandler;
        server = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
    }
    public int port() { return server.getLocalPort(); }
    public void start() {
        Thread acceptor = new Thread(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept(); sockets.add(socket);
                    // Starts on accept, so queued slow requests also expire.
                    long deadline = System.nanoTime() + 300_000_000L;
                    try { reads.execute(() -> handle(socket, deadline)); }
                    catch (RejectedExecutionException e) { reply(socket, new Response(503, "{\"ok\":false,\"error\":\"BUSY\"}")); }
                } catch (IOException e) {
                    if (!closed) System.err.println("[DoorHub] HTTP accept failed: " + e.getMessage());
                }
            }
        }, "door-hub-http-accept");
        acceptor.setDaemon(true); acceptor.start();
    }
    private void handle(Socket socket, long deadline) {
        try {
            LocalHttpRequest request = LocalHttpRequest.read(new DeadlineInput(socket, deadline));
            WindowRequestPolicy.Action action = WindowRequestPolicy.classify(request.method, request.target);
            if (action == WindowRequestPolicy.Action.DOOR_STATE) {
                DoorSnapshot snapshot = snapshots.get(); long now = clock.elapsedMillis();
                reply(socket, new Response(snapshot.isFresh(now) ? 200 : 503, snapshot.toJson(now)));
            } else if (action == WindowRequestPolicy.Action.CRACK || action == WindowRequestPolicy.Action.CLOSE) {
                try { commands.execute(() -> {
                    if (System.nanoTime() >= deadline) reply(socket, new Response(408, "{\"ok\":false,\"error\":\"COMMAND_EXPIRED\"}"));
                    else window(socket, request);
                }); }
                catch (RejectedExecutionException e) { reply(socket, new Response(503, "{\"ok\":false,\"error\":\"COMMAND_BUSY\"}")); }
            } else if (action == WindowRequestPolicy.Action.STATUS) {
                window(socket, request);
            } else {
                reply(socket, new Response(action == WindowRequestPolicy.Action.METHOD_NOT_ALLOWED ? 405 : 404,
                        "{\"ok\":false,\"error\":\"" + action.name() + "\"}"));
            }
        } catch (IOException e) { reply(socket, new Response(400, "{\"ok\":false,\"error\":\"BAD_REQUEST\"}")); }
        catch (RuntimeException e) { reply(socket, new Response(500, "{\"ok\":false,\"error\":\"INTERNAL\"}")); }
    }
    private static final class DeadlineInput extends InputStream {
        private final Socket socket;
        private final InputStream input;
        private final long deadline;
        private final byte[] buffer = new byte[2048];
        private int pos, count;
        DeadlineInput(Socket socket, long deadline) throws IOException {
            this.socket = socket; this.deadline = deadline; this.input = socket.getInputStream();
        }
        @Override public int read() throws IOException {
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new SocketTimeoutException("Request deadline");
            if (pos == count) {
                socket.setSoTimeout((int) Math.max(1, Math.min(150, (left + 999_999) / 1_000_000)));
                count = input.read(buffer); pos = 0;
                if (count < 0) { count = 0; return -1; }
            }
            return buffer[pos++] & 255;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int first = read(); if (first < 0) return -1;
            b[off] = (byte) first; int copied = 1;
            while (copied < len && pos < count) b[off + copied++] = (byte) read();
            return copied;
        }
    }
    private void window(Socket socket, LocalHttpRequest request) {
        try { reply(socket, windowHandler.handle(request)); }
        catch (Exception e) { reply(socket, new Response(500, "{\"ok\":false,\"error\":\"WINDOW_FAILED\"}")); }
    }
    private void reply(Socket socket, Response response) {
        try (Socket owned = socket) {
            byte[] body = response.body.getBytes(StandardCharsets.UTF_8);
            String phrase = response.status == 200 ? "OK" : "Rejected";
            OutputStream out = owned.getOutputStream();
            out.write(("HTTP/1.1 " + response.status + " " + phrase + "\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body); out.flush();
        } catch (IOException ignored) {}
        finally { sockets.remove(socket); }
    }
    @Override public void close() throws IOException {
        closed = true; server.close(); reads.shutdownNow(); commands.shutdownNow();
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
        sockets.clear();
    }
}
