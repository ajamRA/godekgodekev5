package com.emas.energy;

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
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setLoadsImagesAutomatically(true);

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
            stopService(new Intent(this, com.noor.thenoorcar.FloatingBubbleService.class));
        } catch (Throwable ignored) {}
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
                        if ("GET".equals(method) && "/api/energy".equals(path) && (body == null || body.isEmpty())) {
                            data = com.noor.thenoorcar.ReadOnlyTelemetry.read();
                            energyReadErrorLogged = false;
                            code = 200;
                        } else if ("GET".equals(method) && ("/api/window?action=crack".equals(path)
                                || "/api/window?action=close".equals(path))) {
                            data = com.noor.thenoorcar.WindowHttpClient.request(path);
                            code = data.optBoolean("ok", false) ? 200 : 502;
                        } else {
                            com.noor.thenoorcar.VehicleHttpClient.Result result = vehicleClient.request(method, path, body);
                            code = result.status;
                            data = new org.json.JSONObject(result.body);
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
        public void setAvasMode(final int mode) {
            if (legacyVehicleControlsBlocked()) { Log.w(TAG, "Control unavailable in reviewed candidate"); return; }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.net.Socket sock = new java.net.Socket("127.0.0.1", 8888);
                        sock.setSoTimeout(800);
                        java.io.OutputStream os = sock.getOutputStream();
                        os.write(("POST /api/avas?mode=" + mode + " HTTP/1.1\r\nHost: 127.0.0.1:8888\r\nConnection: close\r\n\r\n").getBytes());
                        os.flush();
                        sock.close();
                    } catch (Throwable ignored) {}
                    try {
                        android.content.Intent intent = new android.content.Intent("ecarx.settings.receiver.AvasSetReceiver");
                        intent.setPackage("ecarx.settings");
                        intent.addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND | 0x01000000);
                        intent.putExtra("AVAS_SET_VALUE", mode);
                        sendBroadcast(intent);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            }).start();
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
        public void launchAbrp() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.Intent intent = getPackageManager().getLaunchIntentForPackage("com.iternio.abrpapp");
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                            return;
                        }
                    } catch (Throwable ignored) {}
                    try {
                        Runtime.getRuntime().exec(new String[]{"am", "start", "-n", "com.iternio.abrpapp/.MainActivity"});
                    } catch (Throwable ignored) {}
                }
            }).start();
        }
    }

    @Override protected void onResume() {
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
