package com.emas.door;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;

/** Hub first. UNKNOWN sensors remain authoritative; only a failed/stale hub permits fallback. */
public final class DoorHubClient implements AutoCloseable {
    public interface Transport { String get() throws IOException; }
    public static final long FALLBACK_GRACE_MS = 1000, FALLBACK_PROBE_MS = 1000;
    public static final class Reading {
        public final String source;
        private final DoorSnapshot.State[] states;
        private final long[] epochs;
        public Reading(String source, DoorSnapshot.State[] states) { this(source, states, new long[4]); }
        public Reading(String source, DoorSnapshot.State[] states, long[] epochs) {
            this.source = source; this.states = states.clone(); this.epochs = epochs.clone();
        }
        public DoorSnapshot.State state(int door) { return states[door]; }
        public long epoch(int door) { return epochs[door]; }
    }
    private final DoorStateHub.Clock clock;
    private final Transport transport;
    private final DoorStateHub.Reader fallback;
    private boolean failing, usingFallback;
    private long failureSince, nextProbe;
    private DoorSnapshot lastHub;
    // Only one fallback operation may exist, including after a Binder call hangs. No retry threads.
    private final ExecutorService fallbackWorker;
    private Future<FallbackSample> pendingFallback;
    private static final class FallbackSample {
        final long startedMs;
        final DoorSnapshot.State[] states;
        FallbackSample(long startedMs, DoorSnapshot.State[] states) { this.startedMs = startedMs; this.states = states; }
    }

    public DoorHubClient(DoorStateHub.Clock clock, Transport transport, DoorStateHub.Reader fallback) {
        this.clock = clock; this.transport = transport; this.fallback = fallback;
        fallbackWorker = fallback == null ? null : new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "door-chime-vhal-fallback"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized Reading next() {
        long now = clock.elapsedMillis();
        if (!usingFallback || now >= nextProbe) {
            try {
                DoorSnapshot snapshot = DoorSnapshot.parse(transport.get(), clock.elapsedMillis());
                if (lastHub != null && lastHub.generation.equals(snapshot.generation)
                        && (snapshot.sequence < lastHub.sequence || snapshot.sampledElapsedMs < lastHub.sampledElapsedMs))
                    throw new IOException("Out-of-order snapshot");
                if (lastHub != null && lastHub.generation.equals(snapshot.generation) && snapshot.sequence == lastHub.sequence
                        && (snapshot.sampledElapsedMs != lastHub.sampledElapsedMs || snapshot.timestamp != lastHub.timestamp
                        || !java.util.Arrays.equals(snapshot.states(), lastHub.states())
                        || !java.util.Arrays.equals(snapshot.epochs(), lastHub.epochs())))
                    throw new IOException("Snapshot changed without new sequence");
                if (lastHub != null && lastHub.generation.equals(snapshot.generation)) {
                    for (int i = 0; i < 4; i++) if (snapshot.epoch(i) < lastHub.epoch(i)) throw new IOException("Out-of-order continuity");
                }
                lastHub = snapshot; failing = false; usingFallback = false;
                return new Reading("hub:" + snapshot.generation, snapshot.states(), snapshot.epochs());
            } catch (IOException e) {
                now = clock.elapsedMillis();
                if (!failing) { failing = true; failureSince = now; }
                nextProbe = now + FALLBACK_PROBE_MS;
            }
        }
        if (fallback == null || now - failureSince < FALLBACK_GRACE_MS)
            return new Reading("unavailable", DoorStateHub.unknownStates());
        usingFallback = true;
        if (pendingFallback == null) {
            pendingFallback = fallbackWorker.submit(() -> {
                long started = clock.elapsedMillis();
                DoorSnapshot.State[] states = DoorStateHub.unknownStates();
                for (int i = 0; i < 4; i++) {
                    try { DoorSnapshot.State state = fallback.read(i); if (state != null) states[i] = state; }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    catch (Exception ignored) {}
                }
                return new FallbackSample(started, states);
            });
        }
        try {
            FallbackSample sample = pendingFallback.get(60, TimeUnit.MILLISECONDS); pendingFallback = null;
            long age = clock.elapsedMillis() - sample.startedMs;
            return new Reading("fallback-vhal", age >= 0 && age <= DoorSnapshot.MAX_AGE_MS ? sample.states : DoorStateHub.unknownStates());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | CancellationException e) { pendingFallback = null; }
        catch (TimeoutException e) { /* Retain the single pending operation; never spawn another. */ }
        return new Reading("fallback-vhal", DoorStateHub.unknownStates());
    }
    @Override public void close() { if (fallbackWorker != null) fallbackWorker.shutdownNow(); }

    /** Fixed loopback endpoint, bounded size and absolute read deadline; no redirects or chunking. */
    public static final class HttpTransport implements Transport {
        private final int port;
        public HttpTransport() { this(8885); }
        public HttpTransport(int port) { this.port = port; }
        @Override public String get() throws IOException {
            long deadline = System.nanoTime() + 200_000_000L;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 80);
                socket.getOutputStream().write(("GET /api/door/state HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                TimedInput in = new TimedInput(socket, deadline);
                String status = line(in);
                if (!"HTTP/1.1 200 OK".equals(status)) throw new IOException("Hub HTTP status");
                int length = -1, total = status.length();
                while (true) {
                    String line = line(in); total += line.length() + 2;
                    if (total > 4096) throw new IOException("Headers too large");
                    if (line.isEmpty()) break;
                    int colon = line.indexOf(':');
                    if (colon <= 0) throw new IOException("Header");
                    String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
                    String value = line.substring(colon + 1).trim();
                    if (key.equals("transfer-encoding")) throw new IOException("Chunking unsupported");
                    if (key.equals("content-length")) {
                        if (length >= 0 || !value.matches("[0-9]{1,4}")) throw new IOException("Content length");
                        length = Integer.parseInt(value);
                    }
                }
                if (length < 1 || length > 4096) throw new IOException("Body size");
                byte[] body = new byte[length];
                for (int i = 0; i < length; i++) body[i] = (byte) in.read();
                return new String(body, StandardCharsets.UTF_8);
            }
        }
        private static final class TimedInput {
            final Socket socket;
            final InputStream input;
            final long deadline;
            final byte[] buffer = new byte[4096];
            int pos, count;
            TimedInput(Socket socket, long deadline) throws IOException {
                this.socket = socket; this.deadline = deadline; this.input = socket.getInputStream();
            }
            int read() throws IOException {
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new SocketTimeoutException("Hub deadline");
                if (pos == count) {
                    socket.setSoTimeout((int) Math.max(1, Math.min(80, (left + 999_999) / 1_000_000)));
                    count = input.read(buffer); pos = 0;
                    if (count < 0) throw new EOFException("Hub response truncated");
                }
                return buffer[pos++] & 255;
            }
        }
        private static String line(TimedInput in) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (bytes.size() < 1024) {
                int value = in.read();
                if (value == '\n') {
                    byte[] line = bytes.toByteArray();
                    if (line.length == 0 || line[line.length - 1] != '\r') throw new IOException("CRLF");
                    return new String(line, 0, line.length - 1, StandardCharsets.US_ASCII);
                }
                bytes.write(value);
            }
            throw new IOException("Long line");
        }
    }
}
