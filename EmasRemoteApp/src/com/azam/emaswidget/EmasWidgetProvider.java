package com.azam.emaswidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.RemoteViews;

public final class EmasWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids != null && ids.length > 0) {
            updateWidgets(context, manager, ids);
        } else {
            refresh(context);
        }
    }

    public static Bitmap createGaugeBitmap(float soc, boolean isCharging) {
        try {
            int size = 160;
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(14f);
            trackPaint.setColor(Color.parseColor("#CFE6C4"));
            trackPaint.setStrokeCap(Paint.Cap.ROUND);

            Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeWidth(14f);
            progressPaint.setColor(isCharging ? Color.parseColor("#10B981") : Color.parseColor("#2E6F40"));
            progressPaint.setStrokeCap(Paint.Cap.ROUND);

            float pad = 12f;
            RectF oval = new RectF(pad, pad, size - pad, size - pad);

            // Background track circle
            canvas.drawArc(oval, 0, 360, false, trackPaint);

            // Progress arc
            float validSoc = Math.max(0, Math.min(100, soc));
            if (validSoc > 0) {
                float sweepAngle = (validSoc / 100f) * 360f;
                canvas.drawArc(oval, -90, sweepAngle, false, progressPaint);
            }

            // Center Battery Icon
            Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            iconPaint.setColor(isCharging ? Color.parseColor("#10B981") : Color.parseColor("#2E6F40"));
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(4.5f);

            float cx = size / 2f;
            float cy = size / 2f;
            float bw = 24f;
            float bh = 40f;

            // Battery outer body
            RectF bBody = new RectF(cx - bw / 2, cy - bh / 2 + 3f, cx + bw / 2, cy + bh / 2 + 3f);
            canvas.drawRoundRect(bBody, 6f, 6f, iconPaint);

            // Battery terminal cap
            Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaint.setColor(isCharging ? Color.parseColor("#10B981") : Color.parseColor("#2E6F40"));
            fillPaint.setStyle(Paint.Style.FILL);
            RectF bCap = new RectF(cx - 5f, cy - bh / 2 - 3f, cx + 5f, cy - bh / 2 + 3f);
            canvas.drawRoundRect(bCap, 2f, 2f, fillPaint);

            // Battery inner fill
            if (validSoc > 0) {
                float innerPad = 4f;
                float fillH = (bh - innerPad * 2) * (validSoc / 100f);
                RectF bFill = new RectF(
                    bBody.left + innerPad,
                    bBody.bottom - innerPad - fillH,
                    bBody.right - innerPad,
                    bBody.bottom - innerPad
                );
                canvas.drawRoundRect(bFill, 3f, 3f, fillPaint);
            }

            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void refresh(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, EmasWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            updateWidgets(context, manager, ids);
        } catch (Throwable ignored) { }
    }

    private static void updateWidgets(Context context, AppWidgetManager manager, int[] ids) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("remote_v2", Context.MODE_PRIVATE);
            float soc = prefs.getFloat("soc", -1f);
            float rangeKm = prefs.getFloat("range_km", -1f);
            boolean charging = prefs.getBoolean("charging", false);
            boolean locked = prefs.getBoolean("locked", true);
            String carIp = prefs.getString("car_ip", "");
            String ssid = prefs.getString("ssid", "");

            String socText;
            String subtitle;

            if (soc >= 0) {
                socText = Math.round(soc) + "%";
                if (rangeKm > 0) {
                    subtitle = Math.round(rangeKm) + " km · " + (charging ? "Mengecas ⚡" : "Baki");
                } else {
                    subtitle = charging ? "Mengecas ⚡" : "Bateri e.MAS 5";
                }
            } else {
                socText = "--%";
                subtitle = "Tekan untuk bacaan";
            }

            String lockText = locked ? "🔒 Berkunci" : "🔓 Dibuka";
            String ssidText = !ssid.isEmpty() ? "📶 " + ssid : "📶 Standby";
            String ipText = !carIp.isEmpty() ? "🌐 " + carIp : "🌐 Sambung VPS";

            Bitmap gaugeBitmap = createGaugeBitmap(soc >= 0 ? soc : 0f, charging);

            Intent openApp = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingOpen = PendingIntent.getActivity(
                context, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            for (int id : ids) {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.remote_widget);
                if (gaugeBitmap != null) {
                    views.setImageViewBitmap(R.id.widget_gauge, gaugeBitmap);
                }
                views.setTextViewText(R.id.widget_title, "e.MAS 5");
                views.setTextViewText(R.id.widget_soc, socText);
                views.setTextViewText(R.id.widget_subtitle, subtitle);
                views.setTextViewText(R.id.widget_lock_status, lockText);
                views.setTextViewText(R.id.widget_ssid, ssidText);
                views.setTextViewText(R.id.widget_ip, ipText);
                views.setOnClickPendingIntent(R.id.widget_root, pendingOpen);
                manager.updateAppWidget(id, views);
            }
        } catch (Throwable ignored) { }
    }
}
