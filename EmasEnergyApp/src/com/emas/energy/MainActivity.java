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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean isRunning = true;
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
        settings.setLoadsImagesAutomatically(true);

        webView.setWebViewClient(new WebViewClient());
        WebAppInterface webAppInterface = new WebAppInterface();
        webView.addJavascriptInterface(webAppInterface, "AndroidEnergy");
        webView.addJavascriptInterface(webAppInterface, "Android");

        webView.loadUrl("file:///android_asset/index.html");

        startEnergyPolling();
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
                        URL url = new URL("http://127.0.0.1:8888/api/energy");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(400);
                        conn.setReadTimeout(600);
                        if (conn.getResponseCode() == 200) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = in.readLine()) != null) sb.append(line);
                            in.close();
                            final String json = sb.toString();
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
                        Thread.sleep(350);
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
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        URL url = new URL("http://127.0.0.1:8888/api/window");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/json");
                        String payload = "{\"action\":\"crack\",\"percent\":" + percent + "}";
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
        public void playAudioPreview(final String soundKey) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String path = "/data/local/window/locksounds/lock_" + soundKey + ".wav";
                        MediaPlayer mp = new MediaPlayer();
                        mp.setDataSource(path);
                        mp.prepare();
                        mp.start();
                        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                            @Override
                            public void onCompletion(MediaPlayer mediaPlayer) {
                                mediaPlayer.release();
                            }
                        });
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void applyLockSoundDirect(final String soundKey) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        URL url = new URL("http://127.0.0.1:8888/api/locksound");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/json");
                        String payload = "{\"sound\":\"" + soundKey + "\"}";
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
        public boolean isSystemNightMode() {
            int nightModeFlags = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        }

        @JavascriptInterface
        public void triggerRemoteAction(final String endpoint, final String payload) {
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
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }
}
