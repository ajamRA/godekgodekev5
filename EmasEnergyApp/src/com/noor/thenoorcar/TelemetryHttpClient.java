package com.noor.thenoorcar;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/**
 * Exact telemetry reads and authenticated bonnet controls on 127.0.0.1:8888.
 */
public final class TelemetryHttpClient {
    private TelemetryHttpClient() {}

    public static JSONObject fetchEnergy() throws Exception {
        return request("GET", "/api/energy");
    }

    public static JSONObject fetchStatus() throws Exception {
        return request("GET", "/api/status");
    }

    public static JSONObject fetchTasks() throws Exception {
        return request("GET", "/api/tasks");
    }

    public static JSONObject closeTask(String pkg) throws Exception {
        return request("GET", "/api/tasks/close?pkg=" + java.net.URLEncoder.encode(pkg, "UTF-8"));
    }

    public static JSONObject clearAllTasks() throws Exception {
        return request("GET", "/api/tasks/clear_all");
    }

    static JSONObject request(String method, String path) throws Exception {
        return request(method, path, url -> (HttpURLConnection) url.openConnection(), LocalVehicleAuth::readToken);
    }

    static JSONObject request(String method, String path, VehicleHttpClient.Connections connections,
            LocalVehicleAuth.Credentials credentials) throws Exception {
        boolean isRead = "GET".equals(method) && ("/api/energy".equals(path) || "/api/status".equals(path));
        boolean isBoot = "GET".equals(method) && ("/api/frunk/open".equals(path) || "/api/frunk/unlock".equals(path)
                || "/api/trunk/open".equals(path) || "/api/trunk/close".equals(path) || "/api/trunk/toggle".equals(path));
        boolean isClimate = "GET".equals(method) && path != null && path.startsWith("/api/climate/");
        boolean isTasks = "GET".equals(method) && path != null && path.startsWith("/api/tasks");
        if (!isRead && !isBoot && !isClimate && !isTasks) {
            throw new java.io.IOException("forbidden request: " + path);
        }
        URL url = new URL("http://127.0.0.1:8888" + path);
        HttpURLConnection conn = connections.open(url);
        if (conn == null) throw new java.io.IOException("Telemetry connection unavailable");
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        conn.setRequestMethod(method != null ? method : "GET");
        conn.setConnectTimeout(600);
        conn.setReadTimeout(900);
        conn.setInstanceFollowRedirects(false);
        int code;
        String body = "";
        try {
            if (isBoot || isClimate) LocalVehicleAuth.apply(conn, credentials);
            code = conn.getResponseCode();
            java.io.InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) throw new java.io.IOException("empty daemon response: " + code);
            try (java.io.InputStream in = stream; java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[1024]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (System.nanoTime() > deadline) throw new java.net.SocketTimeoutException("Telemetry response deadline");
                    if (out.size() + n > 32768) throw new java.io.IOException("Telemetry response too large");
                    out.write(buf, 0, n);
                }
                body = out.toString("UTF-8");
            }
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
        if (code != 200) throw new java.io.IOException("daemon http " + code);
        return new JSONObject(body);
    }
}
