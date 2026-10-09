package com.noor.thenoorcar;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.media.MediaPlayer;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;


public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean isRunning = true;
    private MediaPlayer lockPreviewPlayer;
    private final java.util.concurrent.atomic.AtomicBoolean lockSoundListPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void releaseLockPreview() {
        if (lockPreviewPlayer != null) {
            lockPreviewPlayer.release();
            lockPreviewPlayer = null;
        }
    }
    private void reportLockPreview(final boolean ok, final String key, final String error) {
        handler.post(() -> {
            if (webView == null || isFinishing()) return;
            webView.evaluateJavascript("window.onLockSoundPreviewResult && window.onLockSoundPreviewResult("
                    + ok + "," + org.json.JSONObject.quote(key) + "," + org.json.JSONObject.quote(error) + ");", null);
        });
    }

    private final java.util.concurrent.ThreadPoolExecutor vehicleRequests = new java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<Runnable>(4));
    private final com.noor.thenoorcar.VehicleHttpClient vehicleClient = new com.noor.thenoorcar.VehicleHttpClient();
    private volatile boolean energyReadErrorLogged = false;
    private boolean legacyVehicleControlsBlocked() { return true; }

    private final java.util.concurrent.atomic.AtomicBoolean lockSoundRequestPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void reportLockSound(final boolean ok, final String soundKey, final org.json.JSONObject details) {
        final String keyJson = org.json.JSONObject.quote(soundKey == null ? "" : soundKey);
        final String detailsJson = details == null ? "{}" : details.toString();
        handler.post(new Runnable() {
            @Override public void run() {
                if (webView == null || isFinishing()) return;
                String js = "window.onLockSoundResult && window.onLockSoundResult("
                        + (ok ? "true" : "false") + "," + keyJson + "," + detailsJson + ");";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private volatile String latestEnergyJson = "{}";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
        );

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setBlockNetworkLoads(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setGeolocationEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                request.grant(request.getResources());
            }
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d("WebViewConsole", consoleMessage.message() + " [" + consoleMessage.lineNumber() + ":" + consoleMessage.sourceId() + "]");
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return url == null || !url.startsWith("file:///android_asset/");
            }
        });
        WebAppInterface webAppInterface = new WebAppInterface();
        webView.addJavascriptInterface(webAppInterface, "AndroidEnergy");
        webView.addJavascriptInterface(webAppInterface, "Android");

        webView.loadUrl("file:///android_asset/index.html");

        // The reviewed JS transport owns polling; no parallel legacy polling loop.
        stopFloatingBubbleService();
    }


    private void stopFloatingBubbleService() {
        try {
            stopService(new Intent(this, FloatingBubbleService.class));
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume(); webView.resumeTimers();
            webView.evaluateJavascript("window.CompanionLifecycle && window.CompanionLifecycle.resume();", null);
        }
    }

    @Override protected void onPause() {
        if (webView != null) {
            webView.evaluateJavascript("window.CompanionLifecycle && window.CompanionLifecycle.pause();", null);
            webView.onPause(); webView.pauseTimers();
        }
        releaseLockPreview();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int nightModeFlags = newConfig.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        final boolean isNight = (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript("if (window.onCarNightModeChanged) window.onCarNightModeChanged(" + isNight + ");", null);
                }
            }
        });
    }

    private void startEnergyPolling() {
        Thread pollThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isRunning) {
                    try {
                        java.net.Socket sock = new java.net.Socket("127.0.0.1", 8888);
                        sock.setSoTimeout(500);
                        java.io.OutputStream out = sock.getOutputStream();
                        out.write("GET /api/energy HTTP/1.1\r\nHost: 127.0.0.1:8888\r\nConnection: close\r\n\r\n".getBytes());
                        out.flush();
                        BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                        String line;
                        int clen = 0;
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("content-length:")) {
                                try { clen = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                            }
                        }
                        StringBuilder sb = new StringBuilder();
                        if (clen > 0) {
                            char[] buf = new char[clen];
                            int totalRead = 0;
                            while (totalRead < clen) {
                                int r = in.read(buf, totalRead, clen - totalRead);
                                if (r == -1) break;
                                totalRead += r;
                            }
                            sb.append(buf, 0, totalRead);
                        } else {
                            while ((line = in.readLine()) != null) sb.append(line);
                        }
                        sock.close();

                        final String json = sb.toString().trim();
                        if (json.contains("\"total_power_") || json.contains("\"battery\"")) {
                            latestEnergyJson = json;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (webView != null) {
                                        webView.evaluateJavascript("if (window.onEnergyUpdate) window.onEnergyUpdate(" + json + ");", null);
                                    }
                                }
                            });
                        }
                    } catch (Throwable ignored) {}

                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        });
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private static volatile String cachedAvasToken = null;
    private static String getAvasToken() {
        if (cachedAvasToken != null && !cachedAvasToken.isEmpty()) return cachedAvasToken;
        try {
            java.io.File tf = new java.io.File("/data/local/window/avas.token");
            if (tf.exists() && tf.length() > 0) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(tf))) {
                    String line = br.readLine();
                    if (line != null) cachedAvasToken = line.trim();
                }
            }
        } catch (Throwable ignored) {}
        return cachedAvasToken != null ? cachedAvasToken : "";
    }

    private static final class AvasResponse {
        final int code;
        final org.json.JSONObject data;
        AvasResponse(int code, org.json.JSONObject data) {
            this.code = code;
            this.data = data;
        }
    }

    private static AvasResponse forwardAvas(String method, String path, String body) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL url = new java.net.URL("http://127.0.0.1:8889" + path);
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                String token = getAvasToken();
                if (!token.isEmpty()) conn.setRequestProperty("X-AVAS-Token", token);
                if ("POST".equals(method) && body != null && !body.isEmpty()) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    byte[] payload = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        os.write(payload);
                    }
                }

                int code = conn.getResponseCode();
                java.io.InputStream stream = code >= 200 && code < 400
                    ? conn.getInputStream() : conn.getErrorStream();
                org.json.JSONObject data = new org.json.JSONObject();
                if (stream != null) {
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) response.append(line);
                        String text = response.toString().trim();
                        if (text.startsWith("{")) data = new org.json.JSONObject(text);
                        else if (!text.isEmpty()) data.put("raw", text);
                    }
                }

                if (code == 401 && attempt == 0) {
                    cachedAvasToken = null;
                    continue;
                }
                return new AvasResponse(code, data);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return new AvasResponse(401, new org.json.JSONObject().put("ok", false).put("error", "UNAUTHORIZED"));
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void requestVehicle(final String id, final String method, final String path, final String body) {
            if (id == null || !id.matches("v[0-9]{1,12}")) return;
            final long queuedAt = android.os.SystemClock.elapsedRealtime();
            try {
                vehicleRequests.execute(() -> {
                    if (android.os.SystemClock.elapsedRealtime() - queuedAt > 3000) {
                        sendVehicleResponse(id, 429, new org.json.JSONObject());
                        return;
                    }
                    int code = 0;
                    org.json.JSONObject data = new org.json.JSONObject();
                    try {
                        if ("GET".equals(method) && "/api/energy".equals(path)) {
                            // Preferred source: local telemetry daemon (root, reads VHAL directly).
                            boolean fromDaemon = false;
                            try {
                                org.json.JSONObject d = com.noor.thenoorcar.TelemetryHttpClient.fetchEnergy();
                                if (d.optBoolean("ok", false)) {
                                    data = d;
                                    fromDaemon = true;
                                }
                            } catch (Exception daemonDown) {
                                if (!energyReadErrorLogged) {
                                    energyReadErrorLogged = true;
                                    Log.i(TAG, "Telemetry daemon unavailable, falling back to in-process VHAL: "
                                        + daemonDown.getClass().getSimpleName());
                                }
                            }
                            if (!fromDaemon) {
                                // Fallback: in-process VHAL read (only works when this app holds the permission).
                                data = com.noor.thenoorcar.ReadOnlyTelemetry.read();
                            }
                            energyReadErrorLogged = false;
                            code = 200;
                        } else if ("GET".equals(method) && "/api/status".equals(path)) {
                            data.put("ok", true);
                            data.put("source", "ihu_vhal_read_only");
                            data.put("windows", org.json.JSONObject.NULL);
                            code = 200;
                        } else if ("GET".equals(method) && "/api/drive_mode".equals(path)) {
                            data.put("ok", true);
                            data.put("source", "ihu_vhal_read_only");
                            data.put("candidate_controls_enabled", false);
                            data.put("confirmed", false);
                            data.put("dispatch_requested", false);
                            data.put("current", org.json.JSONObject.NULL);
                            org.json.JSONObject lock = new org.json.JSONObject();
                            lock.put("enabled", false);
                            data.put("lock", lock);
                            code = 200;
                        } else if ("GET".equals(method) && ("/api/window?action=crack".equals(path)
                                || "/api/window?action=close".equals(path))) {
                            data = com.noor.thenoorcar.WindowHttpClient.request(path);
                            code = data.optBoolean("ok", false) ? 200 : 502;
                        } else if (path != null && path.startsWith("/api/avas")) {
                            // Forward AVAS sound management requests to dedicated AvasSoundDaemon on port 8889
                            try {
                                AvasResponse response = forwardAvas(method, path, body);
                                code = response.code;
                                data = response.data;
                            } catch (Exception avasErr) {
                                code = 503;
                                data.put("ok", false);
                                data.put("error", "AVAS_DAEMON_UNAVAILABLE");
                                data.put("detail", avasErr.getMessage());
                            }
                        } else if (path != null && (path.startsWith("/api/frunk") || path.startsWith("/api/trunk"))) {
                            try {
                                data = com.noor.thenoorcar.TelemetryHttpClient.request(method, path);
                                code = 200;
                            } catch (Exception bootErr) {
                                code = 500;
                                data.put("ok", false);
                                data.put("error", "BOOT_CONTROL_FAILED");
                                data.put("detail", bootErr.getMessage());
                            }
                        } else {
                            code = 405;
                            data.put("ok", false);
                            data.put("error", "READ_ONLY_MODE");
                        }
                      } catch (Exception e) {
                          if ("/api/energy".equals(path) && !energyReadErrorLogged) {
                              energyReadErrorLogged = true;
                              Log.w(TAG, "Read-only battery telemetry unavailable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                          }
                          code = 0; // Unknown outcome, never retry automatically.
                        try { data.put("ok", false); data.put("error", "REQUEST_UNAVAILABLE"); } catch (Exception ignored) {}
                    }
                    sendVehicleResponse(id, code, data);
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                sendVehicleResponse(id, 429, new org.json.JSONObject());
            }
        }

        private void sendVehicleResponse(String id, int status, org.json.JSONObject data) {
            try {
                org.json.JSONObject envelope = new org.json.JSONObject();
                envelope.put("status", status); envelope.put("body", data);
                final String script = "window.onVehicleResponse && window.onVehicleResponse("
                    + org.json.JSONObject.quote(id) + "," + envelope.toString() + ");";
                handler.post(() -> {
                    if (isRunning && webView != null && webView.getUrl() != null
                            && webView.getUrl().startsWith("file:///android_asset/")) {
                        webView.evaluateJavascript(script, null);
                    }
                });
            } catch (Exception e) { Log.w(TAG, "Vehicle callback unavailable"); }
        }

        @JavascriptInterface
        public String getLatestEnergy() {
            return latestEnergyJson;
        }

        @JavascriptInterface
        public void playMegafonPreset(final String preset) {
            if (preset == null || preset.isEmpty()) return;
            new Thread(() -> {
                try {
                    URL u = new URL("http://127.0.0.1:8890/api/megafon/play?preset=" + java.net.URLEncoder.encode(preset, "UTF-8"));
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setRequestMethod("GET");
                    c.getResponseCode();
                    c.disconnect();
                } catch (Throwable t) {
                    Log.e(TAG, "Error playing preset: " + t.getMessage());
                }
            }).start();
        }

        @JavascriptInterface
        public void playDoa() {
            new Thread(() -> {
                try {
                    URL u = new URL("http://127.0.0.1:8890/api/doa/play");
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setRequestMethod("GET");
                    c.getResponseCode();
                    c.disconnect();
                } catch (Throwable t) {
                    Log.e(TAG, "Error playing doa: " + t.getMessage());
                }
            }).start();
        }


        @JavascriptInterface
        public void closeApp() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean keepOn) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (keepOn) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                }
            });
        }

        @JavascriptInterface
        public void setScreenBrightness(final float brightness) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = brightness;
                    getWindow().setAttributes(lp);
                }
            });
        }

        @JavascriptInterface
        public void triggerWindowCrack(final int percent) {
            if (legacyVehicleControlsBlocked()) { Log.w(TAG, "Control unavailable in reviewed candidate"); return; }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String ep = percent > 0 ? "/api/ventilate?minutes=30" : "/api/close";
                        java.net.Socket sock = new java.net.Socket("127.0.0.1", 8888);
                        sock.setSoTimeout(800);
                        java.io.OutputStream os = sock.getOutputStream();
                        os.write(("GET " + ep + " HTTP/1.1\r\nHost: 127.0.0.1:8888\r\nConnection: close\r\n\r\n").getBytes());
                        os.flush();
                        sock.close();
                    } catch (Throwable ignored) {}
                }
            }).start();
        }

        @JavascriptInterface
        public void playAudioPreview(final String soundKey) {
            if (soundKey == null || !soundKey.matches("[a-z0-9_]{1,32}")) {
                reportLockPreview(false, soundKey == null ? "" : soundKey, "INVALID_SOUND_KEY");
                return;
            }
            handler.post(() -> {
                if (isFinishing()) return;
                releaseLockPreview();
                try {
                    final MediaPlayer mp = new MediaPlayer();
                    lockPreviewPlayer = mp;
                    mp.setOnPreparedListener(player -> {
                        if (lockPreviewPlayer != player) return;
                        try { player.start(); }
                        catch (RuntimeException failed) { releaseLockPreview(); reportLockPreview(false, soundKey, "PREVIEW_FAILED"); }
                    });
                    mp.setOnCompletionListener(player -> {
                        if (lockPreviewPlayer != player) return;
                        releaseLockPreview();
                        reportLockPreview(true, soundKey, "");
                    });
                    mp.setOnErrorListener((player, what, extra) -> {
                        if (lockPreviewPlayer != player) return true;
                        releaseLockPreview();
                        reportLockPreview(false, soundKey, "PREVIEW_UNAVAILABLE");
                        return true;
                    });
                    mp.setDataSource(com.noor.thenoorcar.LockSoundHttpClient.previewUrl(soundKey));
                    mp.prepareAsync();
                } catch (Exception failed) {
                    releaseLockPreview();
                    reportLockPreview(false, soundKey, "PREVIEW_UNAVAILABLE");
                }
            });
        }

        @JavascriptInterface
        public void listLockSounds() {
            if (!lockSoundListPending.compareAndSet(false, true)) return;
            try { vehicleRequests.execute(() -> {
                try {
                    final org.json.JSONObject result = com.noor.thenoorcar.LockSoundHttpClient.listPresets();
                    handler.post(() -> {
                        if (webView == null || isFinishing()) return;
                        webView.evaluateJavascript("window.onLockSoundPresets && window.onLockSoundPresets("
                                + result.toString() + ");", null);
                    });
                } finally { lockSoundListPending.set(false); }
            }); } catch (java.util.concurrent.RejectedExecutionException busy) {
                lockSoundListPending.set(false);
            }
        }

        @JavascriptInterface
        public void stopLockSoundPreview() { handler.post(() -> releaseLockPreview()); }

        @JavascriptInterface
        public void getLockSoundStatus(final String requestId) {
            if (requestId == null || !requestId.matches("s[0-9]{1,12}")) return;
            try { vehicleRequests.execute(() -> {
                final org.json.JSONObject result = com.noor.thenoorcar.LockSoundHttpClient.status();
                handler.post(() -> {
                    if (!isRunning || webView == null || isFinishing()) return;
                    webView.evaluateJavascript("window.onLockSoundStatus && window.onLockSoundStatus("
                            + org.json.JSONObject.quote(requestId) + "," + result.toString() + ");", null);
                });
            }); } catch (java.util.concurrent.RejectedExecutionException busy) {
                Log.w(TAG, "Lock status queue busy");
            }
        }

        @JavascriptInterface
        public void applyLockSoundDirect(final String soundKey) {
            final long queuedAt = android.os.SystemClock.elapsedRealtime();
            if (!lockSoundRequestPending.compareAndSet(false, true)) {
                try {
                    reportLockSound(false, soundKey, new org.json.JSONObject()
                            .put("ok", false).put("file_committed", false).put("error", "LOCK_REQUEST_BUSY"));
                } catch (org.json.JSONException impossible) { Log.w(TAG, "lock callback unavailable"); }
                return;
            }
            // Deliberately does NOT unlock any other legacy vehicle controls.
            try { vehicleRequests.execute(new Runnable() {
                @Override public void run() {
                    try {
                        if (android.os.SystemClock.elapsedRealtime() - queuedAt > 3000) {
                            try { reportLockSound(false, soundKey, new org.json.JSONObject().put("ok", false)
                                    .put("file_committed", false).put("error", "LOCK_REQUEST_BUSY")); }
                            catch (org.json.JSONException ignored) {}
                            return;
                        }
                        org.json.JSONObject result = com.noor.thenoorcar.LockSoundHttpClient.apply(soundKey);
                        reportLockSound(result.optBoolean("file_committed", false), soundKey, result);
                    } finally {
                        lockSoundRequestPending.set(false);
                    }
                }
            }); } catch (java.util.concurrent.RejectedExecutionException busy) {
                lockSoundRequestPending.set(false);
                try { reportLockSound(false, soundKey, new org.json.JSONObject().put("ok", false)
                        .put("file_committed", false).put("error", "LOCK_REQUEST_BUSY")); }
                catch (org.json.JSONException ignored) {}
            }
        }

        @JavascriptInterface
        public boolean isSystemNightMode() {
            int nightModeFlags = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        }


        @JavascriptInterface
        public void triggerRemoteAction(final String endpoint, final String payload) {
            if (legacyVehicleControlsBlocked()) { Log.w(TAG, "Control unavailable in reviewed candidate"); return; }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        URL url = new URL("http://127.0.0.1:8888" + endpoint);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/json");
                        OutputStream os = conn.getOutputStream();
                        os.write(payload.getBytes("UTF-8"));
                        os.flush();
                        os.close();
                        conn.getResponseCode();
                    } catch (Throwable ignored) {}
                }
            }).start();
        }

        @JavascriptInterface
        public void setTargetTemperature(final float temp) {
            if (legacyVehicleControlsBlocked()) { Log.w(TAG, "Control unavailable in reviewed candidate"); return; }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.net.Socket sock = new java.net.Socket("127.0.0.1", 8888);
                        sock.setSoTimeout(800);
                        java.io.OutputStream os = sock.getOutputStream();
                        os.write(("GET /api/hvac?temp=" + temp + " HTTP/1.1\r\nHost: 127.0.0.1:8888\r\nConnection: close\r\n\r\n").getBytes());
                        os.flush();
                        sock.close();
                    } catch (Throwable ignored) {}
                }
            }).start();
        }

        @JavascriptInterface
        public void setBlowerSpeed(final int speed) {
            if (legacyVehicleControlsBlocked()) { Log.w(TAG, "Control unavailable in reviewed candidate"); return; }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.net.Socket sock = new java.net.Socket("127.0.0.1", 8888);
                        sock.setSoTimeout(800);
                        java.io.OutputStream os = sock.getOutputStream();
                        os.write(("GET /api/hvac?fan=" + speed + " HTTP/1.1\r\nHost: 127.0.0.1:8888\r\nConnection: close\r\n\r\n").getBytes());
                        os.flush();
                        sock.close();
                    } catch (Throwable ignored) {}
                }
            }).start();
        }

        @JavascriptInterface
        public void setClimatePower(final boolean on) {
            if (legacyVehicleControlsBlocked()) { Log.w(TAG, "Control unavailable in reviewed candidate"); return; }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.net.Socket sock = new java.net.Socket("127.0.0.1", 8888);
                        sock.setSoTimeout(800);
                        java.io.OutputStream os = sock.getOutputStream();
                        os.write(("GET /api/hvac?power=" + (on ? "1" : "0") + " HTTP/1.1\r\nHost: 127.0.0.1:8888\r\nConnection: close\r\n\r\n").getBytes());
                        os.flush();
                        sock.close();
                    } catch (Throwable ignored) {}
                }
            }).start();
        }

        @JavascriptInterface
        public void setAirRecirculation(final int mode) {
            if (legacyVehicleControlsBlocked()) { Log.w(TAG, "Control unavailable in reviewed candidate"); return; }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.net.Socket sock = new java.net.Socket("127.0.0.1", 8888);
                        sock.setSoTimeout(800);
                        java.io.OutputStream os = sock.getOutputStream();
                        os.write(("GET /api/hvac?recirc=" + mode + " HTTP/1.1\r\nHost: 127.0.0.1:8888\r\nConnection: close\r\n\r\n").getBytes());
                        os.flush();
                        sock.close();
                    } catch (Throwable ignored) {}
                }
            }).start();
        }

        @JavascriptInterface
        public void setAcCompressor(final boolean on) {
            if (legacyVehicleControlsBlocked()) { Log.w(TAG, "Control unavailable in reviewed candidate"); return; }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.net.Socket sock = new java.net.Socket("127.0.0.1", 8888);
                        sock.setSoTimeout(800);
                        java.io.OutputStream os = sock.getOutputStream();
                        os.write(("GET /api/hvac?ac=" + (on ? "1" : "0") + " HTTP/1.1\r\nHost: 127.0.0.1:8888\r\nConnection: close\r\n\r\n").getBytes());
                        os.flush();
                        sock.close();
                    } catch (Throwable ignored) {}
                }
            }).start();
        }


        @JavascriptInterface
        public void launchApp(final String packageName) {
            if (packageName == null) return;
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Cannot launch app: " + packageName, t);
                    }
                }
            });
        }


    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        releaseLockPreview();
        vehicleRequests.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidEnergy");
            webView.removeJavascriptInterface("Android");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
