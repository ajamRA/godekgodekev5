package com.noor.thenoorcar;

import java.io.*;
import java.net.*;
import org.json.JSONObject;

/** Exact existing pressure-relief actions; response means dispatch, not observed window position. */
public final class WindowHttpClient {
    private WindowHttpClient() {}
    public static JSONObject request(String path) throws Exception {
        if (!"/api/window?action=crack".equals(path) && !"/api/window?action=close".equals(path))
            throw new IOException("Unsupported window action");
        HttpURLConnection conn = (HttpURLConnection)new URL("http://127.0.0.1:8885" + path).openConnection();
        try {
            conn.setConnectTimeout(1000); conn.setReadTimeout(1500); conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET"); conn.setUseCaches(false);
            if (conn.getResponseCode() != 200) throw new IOException("Window daemon unavailable");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[512]; int n;
                while ((n = in.read(bytes)) != -1) {
                    if (System.nanoTime() > deadline) throw new SocketTimeoutException("Window response deadline");
                    if (out.size() + n > 4096) throw new IOException("Window response too large");
                    out.write(bytes,0,n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { conn.disconnect(); }
    }
}
