package com.noor.thenoorcar;

import java.io.*;
import java.net.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;

/** Fixed-loopback lock client. No redirects, no vehicle control calls, bounded response. */
public final class LockSoundHttpClient {
    private LockSoundHttpClient() {}
    private static final String BASE = "http://127.0.0.1:8890/api/locksound/";
    public static JSONObject apply(String key) {
        return applyTo(BASE + "apply", key);
    }
    public static JSONObject listPresets() {
        return listFrom(BASE + "list");
    }
    public static JSONObject status() { return statusFrom(BASE + "status"); }
    static JSONObject statusFrom(String target) { return requestJson(target, null, false, true); }
    public static String previewUrl(String key) {
        if (key == null || !key.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("INVALID_SOUND_KEY");
        return BASE + "preview?sound=" + key;
    }
    // Package-private seam for a laptop loopback fixture, not a JS-configurable URL.
    static JSONObject applyTo(String target, String key) {
        return requestJson(target, key, true);
    }
    static JSONObject listFrom(String target) {
        return requestJson(target, null, false);
    }
    private static JSONObject requestJson(String target, String key, boolean applying) {
        return requestJson(target, key, applying, false);
    }
    private static JSONObject requestJson(String target, String key, boolean applying, boolean statusQuery) {
        HttpURLConnection conn = null;
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false).put("file_committed", false).put("sound", key == null ? "" : key)
                    .put("radar_restarted", false).put("playback_verified", false);
            if (applying && (key == null || !key.matches("[a-z0-9_]{1,32}"))) {
                return result.put("error", "INVALID_SOUND_KEY");
            }
            byte[] body = applying ? new JSONObject().put("sound", key).toString().getBytes("UTF-8") : null;
            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod(applying ? "POST" : "GET");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Connection", "close");
            if (applying) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            }
            int status = conn.getResponseCode();
            InputStream response = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (response == null) return result.put("error", "EMPTY_DAEMON_RESPONSE").put("http_status", status);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            try (InputStream in = response) {
                byte[] chunk = new byte[512]; int n;
                while ((n = in.read(chunk)) != -1) {
                    if (System.nanoTime() > deadline) throw new SocketTimeoutException("response deadline");
                    if (buf.size() + n > 8192) return result.put("error", "DAEMON_RESPONSE_TOO_LARGE");
                    buf.write(chunk, 0, n);
                }
            }
            JSONObject reply = new JSONObject(buf.toString("UTF-8"));
            if (statusQuery) {
                boolean valid = status == 200 && Boolean.TRUE.equals(reply.opt("ok"))
                        && reply.has("active_sound") && reply.has("sync_required")
                        && (reply.isNull("active_sound") || reply.opt("active_sound") instanceof String)
                        && (reply.isNull("sync_required") || reply.opt("sync_required") instanceof Boolean);
                reply.put("ok", valid).put("http_status", status);
                if (!valid) reply.put("error", status == 404 ? "STATUS_UNSUPPORTED" : "INVALID_LOCK_STATUS");
                return reply;
            }
            if (!applying) {
                boolean listed = status == 200 && Boolean.TRUE.equals(reply.opt("ok"))
                        && reply.opt("presets") instanceof JSONArray;
                reply.put("ok", listed).put("http_status", status);
                if (!listed && reply.optString("error", "").isEmpty()) reply.put("error", "INVALID_PRESET_LIST");
                return reply;
            }
            boolean committed = status == 200 && Boolean.TRUE.equals(reply.opt("ok"))
                    && Boolean.TRUE.equals(reply.opt("file_committed"))
                    && key.equals(reply.optString("sound", ""));
            reply.put("ok", committed).put("file_committed", committed).put("http_status", status);
            if (!committed && reply.optString("error", "").isEmpty())
                reply.put("error", status == 200 ? "UNCONFIRMED_DAEMON_RESPONSE" : "HTTP_" + status);
            return reply;
        } catch (SocketTimeoutException e) {
            // It may have committed after the caller timed out. Never auto-retry with a new ID.
            try { return result.put("error", "RESULT_UNKNOWN_TIMEOUT"); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        } catch (Exception e) {
            try { return result.put("error", e instanceof IOException ? "RESULT_UNKNOWN_CONNECTION_ERROR"
                    : "INVALID_DAEMON_RESPONSE"); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
