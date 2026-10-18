package com.emas.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private LinearLayout layout;
    private TextView addressLabel;
    private String gateway;
    private boolean failed, paused;
    private final Runnable deadline = () -> showOffline();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.rgb(13, 21, 25));
        getWindow().setNavigationBarColor(Color.rgb(13, 21, 25));
        try { gateway = GatewayAddress.normalize(getPreferences(MODE_PRIVATE)
                .getString("gateway", BuildConfig.DEFAULT_GATEWAY)); }
        catch (Exception error) { gateway = BuildConfig.DEFAULT_GATEWAY; }
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(13, 21, 25));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(4), dp(8), dp(4));
        addressLabel = new TextView(this);
        addressLabel.setTextColor(Color.rgb(125, 219, 198));
        addressLabel.setTextSize(12);
        toolbar.addView(addressLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button connection = new Button(this);
        connection.setText("Sambungan");
        connection.setOnClickListener(v -> editGateway());
        toolbar.addView(connection, new LinearLayout.LayoutParams(dp(125), dp(48)));
        Button refresh = new Button(this);
        refresh.setText("↻");
        refresh.setContentDescription("Muat semula");
        refresh.setOnClickListener(v -> loadGateway());
        toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        layout.addView(toolbar);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(13, 21, 25));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !GatewayAddress.sameOrigin(gateway, request.getUrl().toString());
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showOffline();
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) showOffline();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!GatewayAddress.sameOrigin(gateway, url) || failed) return;
                handler.removeCallbacks(deadline);
                addressLabel.setText("e.MAS Companion · " + gateway.substring(7));
                if (!paused) view.evaluateJavascript("(function(){var b=document.querySelector('[data-detect]');"
                        + "if(b&&window.CompanionConnection&&window.CompanionConnection.client)b.click();})()", null);
            }
        });
        layout.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(layout);
        loadGateway();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void loadGateway() {
        handler.removeCallbacks(deadline);
        failed = false;
        addressLabel.setText("Menyambung · " + gateway.substring(7));
        webView.stopLoading();
        webView.loadUrl(gateway);
        handler.postDelayed(deadline, 12000);
    }

    private void showOffline() {
        if (failed || webView == null || isFinishing()) return;
        failed = true;
        handler.removeCallbacks(deadline);
        webView.stopLoading();
        addressLabel.setText("Gateway tidak dapat dicapai");
        webView.loadDataWithBaseURL(null, "<!doctype html><html lang='ms'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<body style='margin:0;padding:32px 22px;background:#0d1519;color:#e5f4f1;font:16px sans-serif;line-height:1.6'>"
                + "<h2>Sambungan belum tersedia</h2><p>Telefon dan laptop perlu berada pada Wi-Fi yang sama."
                + " Pastikan gateway pada laptop masih berjalan.</p><p>Tekan <b>Sambungan</b> untuk semak IP laptop,"
                + " kemudian tekan <b>↻</b> untuk cuba semula.</p><p>Port gateway ialah <b>8766</b>."
                + " Port 5555 hanya untuk ADB.</p></body></html>", "text/html", "UTF-8", null);
    }

    private void editGateway() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(gateway);
        input.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Gateway pada laptop")
                .setMessage("Masukkan IP laptop dan port 8766. Laptop perlu menjalankan Web Companion.")
                .setView(input).setNegativeButton("Batal", null).setPositiveButton("Sambung", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                gateway = GatewayAddress.normalize(input.getText().toString());
                getPreferences(MODE_PRIVATE).edit().putString("gateway", gateway).apply();
                dialog.dismiss(); loadGateway();
            } catch (IllegalArgumentException error) { input.setError(error.getMessage()); }
        }));
        dialog.show();
    }

    @Override protected void onPause() {
        paused = true;
        if (webView != null) {
            webView.evaluateJavascript("window.CompanionLifecycle&&window.CompanionLifecycle.pause()", null);
            webView.onPause();
        }
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); paused = false;
        if (webView != null) {
            webView.onResume();
            webView.evaluateJavascript("window.CompanionLifecycle&&window.CompanionLifecycle.resume()", null);
        }
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading(); layout.removeView(webView); webView.destroy(); webView = null;
        }
        super.onDestroy();
    }
}
