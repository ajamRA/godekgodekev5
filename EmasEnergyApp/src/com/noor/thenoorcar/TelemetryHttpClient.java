package com.noor.thenoorcar;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/**
 * Read-only fetch of the local telemetry daemon on 127.0.0.1:8888 (GET only).
 * Never sends POST or any control path; the daemon rejects them with 405 anyway.
 */
public final class TelemetryHttpClient {
    private TelemetryHttpClient() {}

    public static JSONObject fetchEnergy() throws Exception {
        return request("GET", "/api/energy");
    }

    public static JSONObject fetchStatus() throws Exception {
        return request("GET", "/api/status");
    }

    static JSONObject request(String method, String path) throws Exception {
        boolean isRead = "GET".equals(method) && ("/api/energy".equals(path) || "/api/status".equals(path));
        boolean isBoot = path != null && (path.startsWith("/api/frunk") || path.startsWith("/api/trunk"));
        if (!isRead && !isBoot) {
            throw new java.io.IOException("forbidden request: " + path);
        }
        URL url = new URL("http://127.0.0.1:8888" + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method != null ? method : "GET");
        conn.setConnectTimeout(600);
        conn.setReadTimeout(900);
        conn.setInstanceFollowRedirects(false);
        int code;
        String body = "";
        try {
            code = conn.getResponseCode();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[2048];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(buf, 0, n);
            in.close();
            body = sb.toString();
        } finally {
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
        if (code != 200) throw new java.io.IOException("daemon http " + code);
        return new JSONObject(body);
    }
}
