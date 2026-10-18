package com.noor.thenoorcar;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Fixed loopback destination; no redirects, retries, browser Origin or arbitrary endpoints. */
public final class VehicleHttpClient {
    public interface Connections { HttpURLConnection open(URL url) throws IOException; }
    public static final class Result {
        public final int status; public final String body;
        Result(int status, String body) { this.status = status; this.body = body; }
    }
    private final Connections connections;
    private final LocalVehicleAuth.Credentials credentials;
    public VehicleHttpClient() { this(url -> (HttpURLConnection) url.openConnection()); }
    public VehicleHttpClient(Connections connections) { this(connections, LocalVehicleAuth::readToken); }
    public VehicleHttpClient(Connections connections, LocalVehicleAuth.Credentials credentials) {
        if (connections == null || credentials == null) throw new IllegalArgumentException("Missing transport dependency");
        this.connections = connections; this.credentials = credentials;
    }
    public Result request(String method, String path, String body) throws IOException {
        if (!VehicleRequestPolicy.allowed(method, path, body)) throw new IOException("Unsupported vehicle request");
        HttpURLConnection connection = connections.open(new URL("http://127.0.0.1:8888" + path));
        if (connection == null) throw new IOException("Vehicle connection unavailable");
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000); connection.setReadTimeout(3000);
            connection.setUseCaches(false); connection.setRequestMethod(method);
            if (path.startsWith("/api/climate/") || "POST".equals(method)) LocalVehicleAuth.apply(connection, credentials);
            if ("POST".equals(method)) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return new Result(status, "{}");
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[1024]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (System.nanoTime() > deadline) throw new SocketTimeoutException("Vehicle response deadline");
                    if (out.size() + n > 32768) throw new IOException("Vehicle response too large");
                    out.write(buf, 0, n);
                }
                return new Result(status, new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
}
