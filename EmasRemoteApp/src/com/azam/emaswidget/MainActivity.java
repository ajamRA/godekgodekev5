package com.azam.emaswidget;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.media.MediaRecorder;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    static final String HOST = "emas.43-159-63-29.nip.io";
    static final String PAGE = "https://" + HOST + "/static/remote-v2/index.html";
    private static final String PROTON = "com.malaysia.emas";
    private static final int MICROPHONE = 4101;
    private static final int FILE_CHOOSER = 4102;
    private static final Set<String> ASSETS = new HashSet<>(Arrays.asList(
        "index.html", "remote.css", "minimal.css", "minimal-ui.js", "model.js", "api-client.js", "recorder.js", "app.js", "avas.js", "avas-audio.js"));
    private WebView web;
    private PermissionRequest pending;
    private ValueCallback<Uri[]> fileUploadCallback;
    private boolean foreground;
    private volatile boolean trustedPage;
    private MediaRecorder nativeRecorder = null;
    private File nativeAudioFile = null;
    private String nativeRecordingSession = null;

    static boolean trusted(Uri uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost())
            || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null) return false;
        String p = uri.getPath();
        return p == null || "/".equals(p) || "/index.html".equals(p)
            || "/static/remote-v2/index.html".equals(p) || "/static/remote/index.html".equals(p);
    }
    private boolean trustedOrigin(Uri uri) {
        return uri != null && "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }
    private SharedPreferences preferences() { return getSharedPreferences("remote_v2", MODE_PRIVATE); }

    void logToWeb(String msg) {
        runOnUiThread(() -> {
            if (web != null) {
                String safe = msg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
                web.evaluateJavascript("window.logDebug && window.logDebug(\"" + safe + "\")", null);
            }
        });
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(247, 246, 243));
        getWindow().setNavigationBarColor(Color.rgb(247, 246, 243));
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(247, 246, 243));
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        web.addJavascriptInterface(new Bridge(), "AndroidApp");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !trusted(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return !trusted(Uri.parse(url)); }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) { trustedPage = trusted(Uri.parse(url)); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!trustedOrigin(uri)) return null;
                String path = uri.getPath();
                if (path == null) return null;
                String name = null;
                if ("/".equals(path) || "/index.html".equals(path)) {
                    name = "index.html";
                } else if (path.startsWith("/static/remote-v2/") || path.startsWith("/static/remote/")) {
                    name = path.substring(path.lastIndexOf('/') + 1);
                } else {
                    name = path.startsWith("/") ? path.substring(1) : path;
                }
                if (name == null || !ASSETS.contains(name)) return null;
                try {
                    String mime = name.endsWith(".js") ? "application/javascript" : name.endsWith(".css") ? "text/css" : "text/html";
                    Map<String,String> headers = new HashMap<>();
                    headers.put("Cache-Control", "no-store");
                    return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, getAssets().open(name));
                } catch (IOException e) { return null; }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    String resStr = Arrays.toString(request.getResources());
                    boolean granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                    logToWeb("[Android-WebChromeClient] onPermissionRequest: " + resStr + " | OS granted=" + granted);
                    if (granted) {
                        request.grant(request.getResources());
                        logToWeb("[Android-WebChromeClient] Berjaya diberi akses kepada WebView");
                    } else {
                        if (pending != null) {
                            try { pending.deny(); } catch (Exception ignored) {}
                        }
                        pending = request;
                        logToWeb("[Android-WebChromeClient] Meminta kebenaran dialog OS...");
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
                    }
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                runOnUiThread(() -> { if (pending == request) pending = null; });
            }
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                    fileUploadCallback = null;
                }
                fileUploadCallback = filePathCallback;
                Intent intent = null;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("audio/*");
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, "Pilih Fail Audio"), FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    if (fileUploadCallback != null) {
                        fileUploadCallback.onReceiveValue(null);
                        fileUploadCallback = null;
                    }
                    return false;
                }
            }
        });
        trustedPage = true;
        web.loadUrl(PAGE);
        EmasWidgetProvider.refresh(this);
        // Prompt upfront for microphone permission as requested by user
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
        }
        web.postDelayed(() -> {
            if (!isFinishing() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
            }
        }, 500);
        if ("proton".equals(getIntent().getStringExtra("shortcut"))) openProton();
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);
        if ("proton".equals(intent.getStringExtra("shortcut"))) openProton();
    }

    private void openProton() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(PROTON);
            if (launch != null) { startActivity(launch); return; }
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PROTON))); }
            catch (Exception unavailable) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + PROTON))); }
        } catch (Exception e) { Toast.makeText(this, "Aplikasi Proton belum dapat dibuka.", Toast.LENGTH_SHORT).show(); }
    }

    private void recorderError(String message, String id) {
        if (web != null) web.evaluateJavascript("window.onNativeAudioError && window.onNativeAudioError(" + JSONObject.quote(message) + "," + JSONObject.quote(id) + ")", null);
    }
    void startNativeRecording() { logToWeb("Legacy recorder entry disabled; session required."); }
    void stopNativeRecording() { logToWeb("Legacy recorder stop ignored; session required."); }
    void startNativeRecording(final String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) return;
        runOnUiThread(() -> {
            if (!trustedPage || !foreground) { recorderError("Aplikasi perlu berada di depan.", id); return; }
            if (nativeRecorder != null) { recorderError("Rakaman lain masih aktif.", id); return; }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
                recorderError("Benarkan mikrofon dahulu, kemudian tekan semula.", id); return;
            }
            nativeRecordingSession = id;
            try {
                nativeAudioFile = new File(getCacheDir(), "voice_" + id + ".m4a");
                nativeRecorder = new MediaRecorder();
                nativeRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                nativeRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                nativeRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                nativeRecorder.setAudioEncodingBitRate(64000);
                nativeRecorder.setAudioSamplingRate(16000);
                nativeRecorder.setMaxDuration(12000);
                nativeRecorder.setMaxFileSize(2 * 1024 * 1024);
                nativeRecorder.setOutputFile(nativeAudioFile.getAbsolutePath());
                nativeRecorder.setOnInfoListener((recorder, what, extra) -> {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stopNativeRecording(id);
                });
                nativeRecorder.prepare(); nativeRecorder.start();
                if (web != null) web.evaluateJavascript("window.onNativeRecordingStarted && window.onNativeRecordingStarted(" + JSONObject.quote(id) + ")", null);
            } catch (Exception error) {
                cancelNativeRecording(); recorderError("Mikrofon gagal: " + error.getMessage(), id);
            }
        });
    }
    void stopNativeRecording(final String id) {
        runOnUiThread(() -> {
            if (id == null || !id.equals(nativeRecordingSession) || nativeRecorder == null) return;
            final File completed = nativeAudioFile;
            nativeRecordingSession = null;
            try {
                nativeRecorder.stop(); nativeRecorder.release(); nativeRecorder = null;
                if (completed == null || !completed.isFile() || completed.length() == 0 || completed.length() > 2 * 1024 * 1024) throw new IOException("Saiz rakaman tidak sah.");
                byte[] bytes = java.nio.file.Files.readAllBytes(completed.toPath());
                String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                if (web != null && trustedPage && foreground) web.evaluateJavascript("window.onNativeAudioRecorded && window.onNativeAudioRecorded(" + JSONObject.quote(base64) + ",\"audio/mp4\"," + JSONObject.quote(id) + ")", null);
            } catch (Exception error) { recorderError("Rakaman gagal: " + error.getMessage(), id); }
            finally {
                if (nativeRecorder != null) { try { nativeRecorder.release(); } catch (Exception ignored) {} nativeRecorder = null; }
                if (completed != null) completed.delete(); nativeAudioFile = null;
            }
        });
    }
    void cancelNativeRecording(final String id) {
        runOnUiThread(() -> { if (id != null && id.equals(nativeRecordingSession)) cancelNativeRecording(); });
    }
    void cancelNativeRecording() {
        runOnUiThread(() -> {
            nativeRecordingSession = null;
            if (nativeRecorder != null) {
                try { nativeRecorder.stop(); } catch (Exception ignored) {}
                try { nativeRecorder.release(); } catch (Exception ignored) {}
                nativeRecorder = null;
                logToWeb("⏹️ [Native-Recorder] Rakaman dibatalkan.");
            }
            if (nativeAudioFile != null && nativeAudioFile.exists()) {
                nativeAudioFile.delete();
            }
        });
    }

    public final class Bridge {
        @JavascriptInterface public String apiKey() {
            return trustedPage ? preferences().getString("api_key", BuildConfig.DEFAULT_API_KEY) : "";
        }
        @JavascriptInterface public void saveApiKey(String key) {
            if (trustedPage && key != null && key.length() <= 200) preferences().edit().putString("api_key", key).apply();
        }
        @JavascriptInterface public void openOfficialProtonApp() { if (trustedPage) runOnUiThread(() -> openProton()); }
        @JavascriptInterface public void haptic() {
            if (trustedPage) runOnUiThread(() -> web.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY));
        }
        @JavascriptInterface public boolean isNativeRecorderSupported() {
            return true;
        }
        @JavascriptInterface public int nativeRecorderProtocol() { return 2; }
        @JavascriptInterface public void startRecordingSession(String id) { if (trustedPage) startNativeRecording(id); }
        @JavascriptInterface public void stopRecordingSession(String id) { if (trustedPage) stopNativeRecording(id); }
        @JavascriptInterface public void cancelRecordingSession(String id) { if (trustedPage) cancelNativeRecording(id); }
        @JavascriptInterface public void startRecording() {
            if (trustedPage) startNativeRecording();
        }
        @JavascriptInterface public void stopRecording() {
            if (trustedPage) stopNativeRecording();
        }
        @JavascriptInterface public void cancelRecording() {
            if (trustedPage) cancelNativeRecording();
        }
        @JavascriptInterface public boolean hasMicPermission() {
            return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }
        @JavascriptInterface public String checkMicStatus() {
            boolean osGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            boolean rationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO);
            return "{\"os_granted\":" + osGranted + ",\"rationale\":" + rationale + "}";
        }
        @JavascriptInterface public void requestMic() {
            if (trustedPage) runOnUiThread(() -> {
                boolean granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                logToWeb("[Bridge] requestMic dipanggil dari JS. Status OS=" + granted);
                if (!granted) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
                }
            });
        }
        @JavascriptInterface public void openAppSettings() {
            if (trustedPage) runOnUiThread(() -> {
                logToWeb("[Bridge] Membuka App Settings...");
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Buka Tetapan > Aplikasi > eMAS Remote > Kebenaran", Toast.LENGTH_LONG).show();
                }
            });
        }
        @JavascriptInterface public void pinWidget() {
            if (!trustedPage) return;
            runOnUiThread(() -> {
                AppWidgetManager manager = getSystemService(AppWidgetManager.class);
                if (manager != null && manager.isRequestPinAppWidgetSupported()) {
                    manager.requestPinAppWidget(new ComponentName(MainActivity.this, EmasWidgetProvider.class), null, null);
                } else Toast.makeText(MainActivity.this, "Tambah eMAS Remote melalui menu widget telefon.", Toast.LENGTH_LONG).show();
            });
        }
        @JavascriptInterface public void updateTelemetry(String json) {
            if (!trustedPage || json == null || json.length() > 2000) return;
            try {
                JSONObject reading = new JSONObject(json);
                double soc = reading.optDouble("soc_pct", Double.NaN);
                double range = reading.optDouble("range_km", Double.NaN);
                String ip = reading.optString("car_ip", "");
                String ssid = reading.optString("ssid", "");
                boolean locked = reading.optBoolean("locked", true);
                boolean charging = reading.optBoolean("charging", false);
                long updated = reading.optLong("updated_at", 0);
                long now = System.currentTimeMillis() / 1000;

                SharedPreferences prefs = preferences();
                SharedPreferences.Editor edit = prefs.edit();
                if (Double.isFinite(soc) && soc >= 0 && soc <= 100) edit.putFloat("soc", (float) soc);
                if (Double.isFinite(range) && range >= 0) edit.putFloat("range_km", (float) range);
                if (!ip.isEmpty()) edit.putString("car_ip", ip);
                if (!ssid.isEmpty()) edit.putString("ssid", ssid);
                if (reading.has("locked")) edit.putBoolean("locked", locked);
                if (reading.has("charging")) edit.putBoolean("charging", charging);
                if (updated > 0 && updated <= now + 60) edit.putLong("soc_at", updated);
                edit.apply();
                runOnUiThread(() -> EmasWidgetProvider.refresh(MainActivity.this));
            } catch (Exception ignored) { }
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER) {
            if (fileUploadCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else {
                        results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                    }
                }
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != MICROPHONE) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        logToWeb("[Android-OS] onRequestPermissionsResult: granted=" + granted + (pending != null ? " (ada pending web request)" : " (tiada pending web request)"));
        if (pending != null) {
            PermissionRequest request = pending;
            pending = null;
            if (granted) {
                try {
                    request.grant(request.getResources());
                    logToWeb("[Android-WebChromeClient] Berjaya beri kebenaran web request!");
                } catch (Exception e) {
                    try { request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); } catch (Exception ignored) {}
                }
            } else {
                try { request.deny(); logToWeb("[Android-WebChromeClient] Web request ditolak."); } catch (Exception ignored) {}
            }
        }
        if (granted) {
            Toast.makeText(this, "Kebenaran mikrofon diaktifkan.", Toast.LENGTH_SHORT).show();
            if (web != null) web.evaluateJavascript("window.onMicPermissionUpdated && window.onMicPermissionUpdated(true)", null);
        } else {
            if (web != null) web.evaluateJavascript("window.onMicPermissionUpdated && window.onMicPermissionUpdated(false)", null);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
        }
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.remoteResume&&window.remoteResume()", null);
        }
    }
    @Override protected void onPause() {
        foreground = false;
        cancelNativeRecording();
        if (pending != null) { pending.deny();pending = null; }
        if (web != null) { web.evaluateJavascript("window.remotePause&&window.remotePause()", null);web.onPause(); }
        super.onPause();
    }
    @Override protected void onDestroy() {
        trustedPage = false;
        cancelNativeRecording();
        if (pending != null) { pending.deny();pending = null; }
        if (web != null) { web.removeJavascriptInterface("AndroidApp");web.stopLoading();web.destroy();web = null; }
        super.onDestroy();
    }
}
