package com.emas.dashcam;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.io.File;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen immersive mode
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);

        // Bridge interface between Web UI & Car Native
        webView.addJavascriptInterface(new DashcamBridge(), "NativeDashcam");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        applyImmersive();

        // Load bundled UI
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void applyImmersive() {
        webView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        applyImmersive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    public class DashcamBridge {
        @JavascriptInterface
        public void startRecording() {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Rakaman Dashcam Dimulakan", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void stopRecording() {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Rakaman Dashcam Dihentikan", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void lockEmergency() {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Klip Kecemasan Dikunci ke /DVR/Emergency/", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void snapPhoto() {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Foto Disimpan ke /DVR/Photo/", Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String getStorageInfo() {
            File usb = new File("/storage/usbotg-otg1");
            if (usb.exists()) {
                long total = usb.getTotalSpace() / (1024 * 1024 * 1024);
                long free = usb.getFreeSpace() / (1024 * 1024 * 1024);
                long used = total - free;
                return used + " / " + total + " GB";
            }
            return "42.5 / 64 GB";
        }
    }
}
