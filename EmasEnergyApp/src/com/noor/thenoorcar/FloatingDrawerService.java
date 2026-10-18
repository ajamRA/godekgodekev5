package com.noor.thenoorcar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class FloatingDrawerService extends Service {
    private static final String TAG = "FloatingDrawerService";
    private static final String CHANNEL_ID = "emas_floating_drawer";
    private static final int NOTIF_ID = 1002;
    private static final int AUTO_HIDE_DELAY_MS = 8000;

    private WindowManager windowManager;
    private FrameLayout tabView;
    private WindowManager.LayoutParams tabParams;
    private FrameLayout drawerContainer;
    private FrameLayout drawerCard;
    private View backdropView;
    private WebView drawerWebView;
    private WindowManager.LayoutParams drawerParams;

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isDrawerOpen = false;

    // Left Screen Edge Recents Switcher
    private FrameLayout recentsTabView;
    private WindowManager.LayoutParams recentsTabParams;
    private GradientDrawable recentsTabBgDrawable;
    private View recentsTabDotView;
    private TextView recentsTabTvIcon;
    private TextView recentsTabTvLabel;
    private TextView recentsTabTvArr;

    private FrameLayout recentsContainer;
    private View recentsBackdropView;
    private WebView recentsWebView;
    private WindowManager.LayoutParams recentsParams;
    private boolean isRecentsOpen = false;

    private final Runnable autoHideRunnable = new Runnable() {
        @Override
        public void run() {
            closeDrawer();
        }
    };

    private final ThreadPoolExecutor vehicleRequests = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(4));

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("emas_floating_prefs", Context.MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        startAsForeground();
        initTabView();
        initDrawerPanel();
        // initRecentsTabView(); // User requested removing the APPS floating pill
        initRecentsPanel();
        Log.i(TAG, "FloatingDrawerService initialized successfully");
    }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "e.MAS EV Floating Panel", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Panel terapung kawalan EV dan Aircond");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);

            Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("e.MAS EV Panel Terapung")
                .setContentText("Panel EV & A/C sedia di hujung skrin")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
            startForeground(NOTIF_ID, notif);
        }
    }

    private boolean isNightMode = false;
    private GradientDrawable tabBgDrawable;
    private View tabDotView;
    private TextView tabTvIcon;
    private TextView tabTvEv;
    private TextView tabTvAmp;
    private TextView tabTvAc;
    private TextView tabTvArr;
    private android.database.ContentObserver themeObserver;

    private boolean checkIsNightMode() {
        try {
            String mode = android.provider.Settings.System.getString(getContentResolver(), "day_night_mode");
            if ("night".equalsIgnoreCase(mode)) return true;
            if ("day".equalsIgnoreCase(mode)) return false;
        } catch (Throwable ignored) {}
        try {
            int uiMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {}
        return false;
    }

    private void applyTheme(boolean night) {
        this.isNightMode = night;
        if (tabBgDrawable != null) {
            float r = dpToPx(14);
            tabBgDrawable.setCornerRadii(new float[]{ r, r, 0, 0, 0, 0, r, r });
            if (night) {
                // NIGHT / DARK THEME (Proton Dark Obsidian)
                tabBgDrawable.setColor(Color.parseColor("#E612171E"));
                tabBgDrawable.setStroke(dpToPx(1), Color.parseColor("#33FFFFFF"));
                if (tabDotView != null) {
                    GradientDrawable dotD = (GradientDrawable) tabDotView.getBackground();
                    if (dotD != null) dotD.setColor(Color.parseColor("#10B981"));
                }
                if (tabTvIcon != null) tabTvIcon.setTextColor(Color.parseColor("#E5E7EB"));
                if (tabTvEv != null) tabTvEv.setTextColor(Color.parseColor("#D1D5DB"));
                if (tabTvAmp != null) tabTvAmp.setTextColor(Color.parseColor("#6B7280"));
                if (tabTvAc != null) tabTvAc.setTextColor(Color.parseColor("#9CA3AF"));
                if (tabTvArr != null) tabTvArr.setTextColor(Color.parseColor("#9CA3AF"));
            } else {
                // DAY / LIGHT THEME (Proton Light OEM Pearl Grey)
                tabBgDrawable.setColor(Color.parseColor("#E6E8ECF2"));
                tabBgDrawable.setStroke(dpToPx(1), Color.parseColor("#33000000"));
                if (tabDotView != null) {
                    GradientDrawable dotD = (GradientDrawable) tabDotView.getBackground();
                    if (dotD != null) dotD.setColor(Color.parseColor("#059669"));
                }
                if (tabTvIcon != null) tabTvIcon.setTextColor(Color.parseColor("#1F2937"));
                if (tabTvEv != null) tabTvEv.setTextColor(Color.parseColor("#1F2937"));
                if (tabTvAmp != null) tabTvAmp.setTextColor(Color.parseColor("#9CA3AF"));
                if (tabTvAc != null) tabTvAc.setTextColor(Color.parseColor("#4B5563"));
                if (tabTvArr != null) tabTvArr.setTextColor(Color.parseColor("#6B7280"));
            }
        }
        if (recentsTabBgDrawable != null) {
            float r = dpToPx(14);
            recentsTabBgDrawable.setCornerRadii(new float[]{ 0, 0, r, r, r, r, 0, 0 });
            if (night) {
                recentsTabBgDrawable.setColor(Color.parseColor("#E612171E"));
                recentsTabBgDrawable.setStroke(dpToPx(1), Color.parseColor("#33FFFFFF"));
                if (recentsTabDotView != null) {
                    GradientDrawable dotD = (GradientDrawable) recentsTabDotView.getBackground();
                    if (dotD != null) dotD.setColor(Color.parseColor("#10B981"));
                }
                if (recentsTabTvIcon != null) recentsTabTvIcon.setTextColor(Color.parseColor("#E5E7EB"));
                if (recentsTabTvLabel != null) recentsTabTvLabel.setTextColor(Color.parseColor("#D1D5DB"));
                if (recentsTabTvArr != null) recentsTabTvArr.setTextColor(Color.parseColor("#9CA3AF"));
            } else {
                recentsTabBgDrawable.setColor(Color.parseColor("#E6E8ECF2"));
                recentsTabBgDrawable.setStroke(dpToPx(1), Color.parseColor("#33000000"));
                if (recentsTabDotView != null) {
                    GradientDrawable dotD = (GradientDrawable) recentsTabDotView.getBackground();
                    if (dotD != null) dotD.setColor(Color.parseColor("#059669"));
                }
                if (recentsTabTvIcon != null) recentsTabTvIcon.setTextColor(Color.parseColor("#1F2937"));
                if (recentsTabTvLabel != null) recentsTabTvLabel.setTextColor(Color.parseColor("#1F2937"));
                if (recentsTabTvArr != null) recentsTabTvArr.setTextColor(Color.parseColor("#6B7280"));
            }
        }
        if (drawerWebView != null) {
            final String themeStr = night ? "night" : "day";
            drawerWebView.evaluateJavascript("window.setTheme && window.setTheme('" + themeStr + "');", null);
        }
        if (recentsWebView != null) {
            final String themeStr = night ? "night" : "day";
            recentsWebView.evaluateJavascript("window.setTheme && window.setTheme('" + themeStr + "');", null);
        }
    }

    private void registerThemeObserver() {
        try {
            themeObserver = new android.database.ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    boolean night = checkIsNightMode();
                    Log.i(TAG, "day_night_mode setting changed! isNight=" + night);
                    applyTheme(night);
                }
            };
            getContentResolver().registerContentObserver(
                android.provider.Settings.System.getUriFor("day_night_mode"), false, themeObserver);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register themeObserver", t);
        }
    }

    private void initTabView() {
        int tabWidth = dpToPx(38);
        int tabHeight = dpToPx(120);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        tabParams = new WindowManager.LayoutParams(
            tabWidth,
            tabHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        tabParams.gravity = Gravity.TOP | Gravity.END;
        tabParams.x = 0;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int defaultY = (dm.heightPixels - tabHeight) / 2;
        tabParams.y = prefs.getInt("tab_y", defaultY);

        tabView = new FrameLayout(this);

        // Styling tab background: rounded on left, flat on right
        tabBgDrawable = new GradientDrawable();
        tabBgDrawable.setShape(GradientDrawable.RECTANGLE);
        tabView.setBackground(tabBgDrawable);
        tabView.setElevation(dpToPx(4));

        // Tab content
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dpToPx(1), dpToPx(5), dpToPx(1), dpToPx(5));

        // Status indicator dot
        tabDotView = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#10B981"));
        tabDotView.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dpToPx(5), dpToPx(5));
        dotLp.bottomMargin = dpToPx(3);
        layout.addView(tabDotView, dotLp);

        // Icon
        tabTvIcon = new TextView(this);
        tabTvIcon.setText("⚡");
        tabTvIcon.setTextSize(11);
        tabTvIcon.setGravity(Gravity.CENTER);
        layout.addView(tabTvIcon);

        // Label: EV
        tabTvEv = new TextView(this);
        tabTvEv.setText("EV");
        tabTvEv.setTextSize(9);
        tabTvEv.setTypeface(null, android.graphics.Typeface.BOLD);
        tabTvEv.setGravity(Gravity.CENTER);
        layout.addView(tabTvEv);

        // Label: &
        tabTvAmp = new TextView(this);
        tabTvAmp.setText("&");
        tabTvAmp.setTextSize(7);
        tabTvAmp.setGravity(Gravity.CENTER);
        layout.addView(tabTvAmp);

        // Label: A/C
        tabTvAc = new TextView(this);
        tabTvAc.setText("A/C");
        tabTvAc.setTextSize(9);
        tabTvAc.setTypeface(null, android.graphics.Typeface.BOLD);
        tabTvAc.setGravity(Gravity.CENTER);
        layout.addView(tabTvAc);

        // Arrow
        tabTvArr = new TextView(this);
        tabTvArr.setText("‹");
        tabTvArr.setTextSize(13);
        tabTvArr.setTypeface(null, android.graphics.Typeface.BOLD);
        tabTvArr.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arrLp.topMargin = dpToPx(1);
        layout.addView(tabTvArr, arrLp);

        tabView.addView(layout, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        applyTheme(checkIsNightMode());
        registerThemeObserver();

        // Touch Listener: Drag along right edge or Tap to open
        tabView.setOnTouchListener(new View.OnTouchListener() {
            private int initialY;
            private float initialTouchY;
            private boolean isDragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialY = tabParams.y;
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float diff = event.getRawY() - initialTouchY;
                        if (Math.abs(diff) > dpToPx(6)) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            tabParams.y = (int) (initialY + diff);
                            DisplayMetrics m = getResources().getDisplayMetrics();
                            int maxY = m.heightPixels - tabView.getHeight() - dpToPx(20);
                            if (tabParams.y < dpToPx(20)) tabParams.y = dpToPx(20);
                            if (tabParams.y > maxY) tabParams.y = maxY;
                            try {
                                windowManager.updateViewLayout(tabView, tabParams);
                            } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isDragging) {
                            prefs.edit().putInt("tab_y", tabParams.y).apply();
                        } else {
                            Log.i(TAG, "tabView tapped! Opening drawer.");
                            openDrawer();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(tabView, tabParams);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to add tabView to WindowManager", t);
        }
    }

    private void initDrawerPanel() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        drawerParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        drawerParams.gravity = Gravity.TOP | Gravity.END;

        drawerContainer = new FrameLayout(this);

        // Semi-transparent backdrop on the left
        backdropView = new View(this);
        backdropView.setBackgroundColor(Color.parseColor("#55000000"));
        backdropView.setOnClickListener(v -> closeDrawer());
        drawerContainer.addView(backdropView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Drawer Card on the right
        int drawerWidth = dpToPx(460);
        drawerCard = new FrameLayout(this);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            drawerWidth, FrameLayout.LayoutParams.MATCH_PARENT);
        cardLp.gravity = Gravity.TOP | Gravity.END;
        cardLp.setMargins(0, dpToPx(8), dpToPx(8), dpToPx(8));
        drawerCard.setLayoutParams(cardLp);

        // WebView inside card
        drawerWebView = new WebView(this);
        drawerWebView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = drawerWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        drawerWebView.setWebChromeClient(new WebChromeClient());
        drawerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        WebAppBridge bridge = new WebAppBridge();
        drawerWebView.addJavascriptInterface(bridge, "AndroidEnergy");
        drawerWebView.addJavascriptInterface(bridge, "Android");
        drawerWebView.addJavascriptInterface(new OverlayBridge(), "AndroidOverlay");

        drawerWebView.loadUrl("file:///android_asset/overlay_drawer.html");

        drawerCard.addView(drawerWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        drawerContainer.addView(drawerCard);
    }

    private void initRecentsTabView() {
        int tabWidth = dpToPx(38);
        int tabHeight = dpToPx(120);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        recentsTabParams = new WindowManager.LayoutParams(
            tabWidth,
            tabHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        recentsTabParams.gravity = Gravity.TOP | Gravity.START;
        recentsTabParams.x = 0;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int defaultY = (dm.heightPixels - tabHeight) / 2;
        recentsTabParams.y = prefs.getInt("recents_tab_y", defaultY);

        recentsTabView = new FrameLayout(this);

        recentsTabBgDrawable = new GradientDrawable();
        recentsTabBgDrawable.setShape(GradientDrawable.RECTANGLE);
        recentsTabView.setBackground(recentsTabBgDrawable);
        recentsTabView.setElevation(dpToPx(4));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dpToPx(1), dpToPx(5), dpToPx(1), dpToPx(5));

        // Status indicator dot
        recentsTabDotView = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#10B981"));
        recentsTabDotView.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dpToPx(5), dpToPx(5));
        dotLp.bottomMargin = dpToPx(4);
        layout.addView(recentsTabDotView, dotLp);

        // Icon
        recentsTabTvIcon = new TextView(this);
        recentsTabTvIcon.setText("⊞");
        recentsTabTvIcon.setTextSize(12);
        recentsTabTvIcon.setTypeface(null, android.graphics.Typeface.BOLD);
        recentsTabTvIcon.setGravity(Gravity.CENTER);
        layout.addView(recentsTabTvIcon);

        // Label: APPS
        recentsTabTvLabel = new TextView(this);
        recentsTabTvLabel.setText("APPS");
        recentsTabTvLabel.setTextSize(8);
        recentsTabTvLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        recentsTabTvLabel.setGravity(Gravity.CENTER);
        layout.addView(recentsTabTvLabel);

        // Arrow
        recentsTabTvArr = new TextView(this);
        recentsTabTvArr.setText("›");
        recentsTabTvArr.setTextSize(13);
        recentsTabTvArr.setTypeface(null, android.graphics.Typeface.BOLD);
        recentsTabTvArr.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        arrLp.topMargin = dpToPx(2);
        layout.addView(recentsTabTvArr, arrLp);

        recentsTabView.addView(layout, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        applyTheme(checkIsNightMode());

        recentsTabView.setOnTouchListener(new View.OnTouchListener() {
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isDragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialY = recentsTabParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float diffX = event.getRawX() - initialTouchX;
                        float diffY = event.getRawY() - initialTouchY;
                        if (diffX > dpToPx(18)) {
                            openRecents();
                            return true;
                        }
                        if (Math.abs(diffY) > dpToPx(6)) {
                            isDragging = true;
                            recentsTabParams.y = (int) (initialY + diffY);
                            DisplayMetrics m = getResources().getDisplayMetrics();
                            int maxY = m.heightPixels - recentsTabView.getHeight() - dpToPx(20);
                            if (recentsTabParams.y < dpToPx(20)) recentsTabParams.y = dpToPx(20);
                            if (recentsTabParams.y > maxY) recentsTabParams.y = maxY;
                            try {
                                windowManager.updateViewLayout(recentsTabView, recentsTabParams);
                            } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        float upDiffX = event.getRawX() - initialTouchX;
                        if (isDragging) {
                            prefs.edit().putInt("recents_tab_y", recentsTabParams.y).apply();
                        } else if (upDiffX > dpToPx(14)) {
                            openRecents();
                        } else {
                            Log.i(TAG, "recentsTabView tapped! Opening recents.");
                            openRecents();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(recentsTabView, recentsTabParams);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to add recentsTabView to WindowManager", t);
        }
    }

    private void initRecentsPanel() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        recentsParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        recentsParams.gravity = Gravity.TOP | Gravity.START;

        recentsContainer = new FrameLayout(this);

        recentsBackdropView = new View(this);
        recentsBackdropView.setBackgroundColor(Color.parseColor("#55000000"));
        recentsBackdropView.setOnClickListener(v -> closeRecents());
        recentsContainer.addView(recentsBackdropView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        recentsWebView = new WebView(this);
        recentsWebView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = recentsWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        recentsWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                Log.i(TAG, "[RecentsWeb] " + cm.message() + " -- Line " + cm.lineNumber());
                return true;
            }
        });
        recentsWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "[RecentsWeb] onPageFinished: " + url);
                final String themeStr = checkIsNightMode() ? "night" : "day";
                view.evaluateJavascript(
                    "window.setTheme && window.setTheme('" + themeStr + "'); window.onRecentsOpened && window.onRecentsOpened();",
                    null
                );
            }
        });

        recentsWebView.addJavascriptInterface(new RecentsBridge(), "AndroidRecents");
        recentsWebView.loadUrl("file:///android_asset/overlay_recents.html");

        recentsContainer.addView(recentsWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public void openRecents() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::openRecents);
            return;
        }
        Log.i(TAG, "openRecents invoked! isRecentsOpen was=" + isRecentsOpen);
        if (isRecentsOpen) return;
        if (isDrawerOpen) closeDrawer();
        isRecentsOpen = true;

        if (recentsTabView != null) recentsTabView.setVisibility(View.GONE);
        if (tabView != null) tabView.setVisibility(View.GONE);

        try {
            if (recentsContainer.getParent() != null) {
                windowManager.removeViewImmediate(recentsContainer);
            }
            windowManager.addView(recentsContainer, recentsParams);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to add recentsContainer", t);
        }

        recentsContainer.setVisibility(View.VISIBLE);
        recentsContainer.setAlpha(1f);
        if (recentsBackdropView != null) recentsBackdropView.setAlpha(1f);
        if (recentsWebView != null) {
            recentsWebView.setVisibility(View.VISIBLE);
            recentsWebView.setAlpha(1f);
            final String themeStr = checkIsNightMode() ? "night" : "day";
            recentsWebView.evaluateJavascript(
                "window.setTheme && window.setTheme('" + themeStr + "'); window.onRecentsOpened && window.onRecentsOpened();",
                null
            );
        }
    }

    public void closeRecents() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::closeRecents);
            return;
        }
        Log.i(TAG, "closeRecents invoked! isRecentsOpen was=" + isRecentsOpen);
        if (!isRecentsOpen) return;
        isRecentsOpen = false;

        try {
            if (recentsContainer.getParent() != null) {
                windowManager.removeView(recentsContainer);
            }
        } catch (Throwable ignored) {}
        if (recentsTabView != null) recentsTabView.setVisibility(View.VISIBLE);
        if (tabView != null) tabView.setVisibility(View.VISIBLE);
    }

    public void openDrawer() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::openDrawer);
            return;
        }
        Log.i(TAG, "openDrawer invoked! isDrawerOpen was=" + isDrawerOpen);
        if (isDrawerOpen) return;
        if (isRecentsOpen) closeRecents();
        isDrawerOpen = true;

        handler.removeCallbacks(autoHideRunnable);
        tabView.setVisibility(View.GONE);
        if (recentsTabView != null) recentsTabView.setVisibility(View.GONE);

        try {
            if (drawerContainer.getParent() != null) {
                windowManager.removeViewImmediate(drawerContainer);
            }
            windowManager.addView(drawerContainer, drawerParams);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to add drawerContainer", t);
        }

        int drawerWidth = dpToPx(460);
        drawerCard.setTranslationX(drawerWidth + dpToPx(20));
        drawerCard.animate()
            .translationX(0)
            .setDuration(260)
            .setInterpolator(new DecelerateInterpolator())
            .start();

        backdropView.setAlpha(0f);
        backdropView.animate()
            .alpha(1f)
            .setDuration(260)
            .start();

        if (drawerWebView != null) {
            final String themeStr = checkIsNightMode() ? "night" : "day";
            drawerWebView.evaluateJavascript("window.setTheme && window.setTheme('" + themeStr + "'); window.onDrawerOpened && window.onDrawerOpened();", null);
        }

        resetAutoHide();
    }

    public void closeDrawer() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::closeDrawer);
            return;
        }
        if (!isDrawerOpen) return;
        isDrawerOpen = false;

        handler.removeCallbacks(autoHideRunnable);
        int drawerWidth = dpToPx(460);

        drawerCard.animate()
            .translationX(drawerWidth + dpToPx(20))
            .setDuration(220)
            .setInterpolator(new AccelerateInterpolator())
            .withEndAction(() -> {
                try {
                    if (drawerContainer.getParent() != null) {
                        windowManager.removeView(drawerContainer);
                    }
                } catch (Throwable ignored) {}
                tabView.setVisibility(View.VISIBLE);
                if (recentsTabView != null) recentsTabView.setVisibility(View.VISIBLE);
            })
            .start();

        backdropView.animate()
            .alpha(0f)
            .setDuration(220)
            .start();
    }

    public void resetAutoHide() {
        handler.removeCallbacks(autoHideRunnable);
        handler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS);
    }

    public class OverlayBridge {
        @JavascriptInterface
        public void closeDrawer() {
            handler.post(() -> FloatingDrawerService.this.closeDrawer());
        }

        @JavascriptInterface
        public void resetTimer() {
            handler.post(() -> FloatingDrawerService.this.resetAutoHide());
        }
    }

    public class WebAppBridge {
        @JavascriptInterface
        public void requestVehicle(final String id, final String method, final String path, final String body) {
            if (id == null || !id.matches("v[0-9]{1,12}")) return;
            resetAutoHide();
            final long queuedAt = SystemClock.elapsedRealtime();
            try {
                vehicleRequests.execute(() -> {
                    if (SystemClock.elapsedRealtime() - queuedAt > 3000) {
                        sendResponse(id, 429, new JSONObject());
                        return;
                    }
                    int code = 0;
                    JSONObject data = new JSONObject();
                    try {
                        if ("GET".equals(method) && "/api/energy".equals(path)) {
                            data = TelemetryHttpClient.fetchEnergy();
                            code = 200;
                        } else if ("GET".equals(method) && ("/api/window?action=crack".equals(path)
                                || "/api/window?action=close".equals(path))) {
                            data = WindowHttpClient.request(path);
                            code = data.optBoolean("ok", false) ? 200 : 502;
                        } else if (path != null && (path.startsWith("/api/frunk") || path.startsWith("/api/trunk")
                                || path.startsWith("/api/climate"))) {
                            data = TelemetryHttpClient.request(method, path);
                            code = 200;
                        } else {
                            code = 405;
                            data.put("ok", false);
                            data.put("error", "UNSUPPORTED");
                        }
                    } catch (Exception e) {
                        code = 500;
                        try { data.put("ok", false); data.put("error", e.getMessage()); } catch (Exception ignored) {}
                    }
                    sendResponse(id, code, data);
                });
            } catch (Throwable e) {
                sendResponse(id, 429, new JSONObject());
            }
        }

        private void sendResponse(String id, int status, JSONObject data) {
            try {
                JSONObject env = new JSONObject();
                env.put("status", status);
                env.put("body", data);
                final String script = "window.onVehicleResponse && window.onVehicleResponse("
                    + JSONObject.quote(id) + "," + env.toString() + ");";
                handler.post(() -> {
                    if (drawerWebView != null) {
                        drawerWebView.evaluateJavascript(script, null);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    public class RecentsBridge {
        @JavascriptInterface
        public void closeRecents() {
            handler.post(() -> FloatingDrawerService.this.closeRecents());
        }

        @JavascriptInterface
        public void getTasks() {
            vehicleRequests.execute(() -> {
                try {
                    JSONObject tasks = TelemetryHttpClient.fetchTasks();
                    final String json = tasks.toString();
                    handler.post(() -> {
                        if (recentsWebView != null) {
                            recentsWebView.evaluateJavascript("window.onTasksLoaded && window.onTasksLoaded(" + JSONObject.quote(json) + ");", null);
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to fetch tasks", t);
                }
            });
        }

        @JavascriptInterface
        public void killTask(final String pkg) {
            vehicleRequests.execute(() -> {
                try {
                    TelemetryHttpClient.closeTask(pkg);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to close task " + pkg, t);
                }
            });
        }

        @JavascriptInterface
        public void clearAll() {
            vehicleRequests.execute(() -> {
                try {
                    TelemetryHttpClient.clearAllTasks();
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to clear all tasks", t);
                }
            });
        }

        @JavascriptInterface
        public void openTask(final String pkg, final String act) {
            handler.post(() -> {
                closeRecents();
                try {
                    Intent intent = null;
                    if (act != null && !act.isEmpty()) {
                        intent = new Intent(Intent.ACTION_MAIN);
                        intent.setClassName(pkg, act);
                    } else {
                        intent = getPackageManager().getLaunchIntentForPackage(pkg);
                    }
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        startActivity(intent);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to launch app " + pkg, t);
                }
            });
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "null";
        Log.i(TAG, "onStartCommand received action=" + action);
        if ("TOGGLE".equals(action)) {
            if (isDrawerOpen) closeDrawer(); else openDrawer();
        } else if ("OPEN".equals(action)) {
            openDrawer();
        } else if ("CLOSE".equals(action)) {
            closeDrawer();
        } else if ("OPEN_RECENTS".equals(action)) {
            openRecents();
        } else if ("CLOSE_RECENTS".equals(action)) {
            closeRecents();
        } else if ("TOGGLE_RECENTS".equals(action)) {
            if (isRecentsOpen) closeRecents(); else openRecents();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(autoHideRunnable);
        try {
            if (themeObserver != null) {
                getContentResolver().unregisterContentObserver(themeObserver);
            }
        } catch (Throwable ignored) {}
        try {
            if (tabView != null) windowManager.removeView(tabView);
            if (drawerContainer != null) windowManager.removeView(drawerContainer);
            if (recentsTabView != null) windowManager.removeView(recentsTabView);
            if (recentsContainer != null) windowManager.removeView(recentsContainer);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
