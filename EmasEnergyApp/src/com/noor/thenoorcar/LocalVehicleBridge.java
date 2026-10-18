package com.noor.thenoorcar;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class LocalVehicleBridge {
    private static final String TAG = "LocalVehicleBridge";
    private static final String BASE_URL = "http://127.0.0.1:8888";

    public static void executeToolCall(Context context, JSONObject toolCall) {
        if (toolCall == null) return;
        try {
            String name = toolCall.optString("name");
            JSONObject args = toolCall.optJSONObject("args");
            if (args == null) args = new JSONObject();

            Log.i(TAG, "Executing tool call: " + name + " with args: " + args.toString());

            if ("set_climate".equals(name)) {
                double tempC = args.optDouble("temp_c", -1.0);
                int fanSpeed = args.optInt("fan_speed", -1);
                boolean hasAc = args.has("ac_on");
                boolean acOn = args.optBoolean("ac_on", true);

                StringBuilder sb = new StringBuilder("/api/hvac?");
                boolean first = true;
                if (tempC >= 16.0 && tempC <= 30.0) {
                    sb.append("temp=").append(tempC);
                    first = false;
                }
                if (fanSpeed >= 1 && fanSpeed <= 8) {
                    if (!first) sb.append("&");
                    sb.append("fan=").append(fanSpeed);
                    first = false;
                }
                if (hasAc) {
                    if (!first) sb.append("&");
                    sb.append("ac=").append(acOn ? "1" : "0");
                }
                sendLocalGet(sb.toString());
            } else if ("set_windows".equals(name)) {
                String action = args.optString("action", "vent");
                if ("vent".equalsIgnoreCase(action)) {
                    sendLocalGet("/api/ventilate?minutes=30");
                } else if ("close".equalsIgnoreCase(action)) {
                    sendLocalGet("/api/close");
                } else if ("open".equalsIgnoreCase(action)) {
                    sendLocalGet("/api/window?fl=0&fr=0&rl=0&rr=0");
                }
            } else if ("open_navigation".equals(name)) {
                String query = args.optString("query", "");
                if (!query.isEmpty() && context != null) {
                    openMapNavigation(context, query);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error executing tool call: " + t.getMessage(), t);
        }
    }

    public static void openMapNavigation(Context context, String query) {
        try {
            String encoded = URLEncoder.encode(query, "UTF-8");
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + encoded));
            mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(mapIntent);
        } catch (Throwable t) {
            Log.w(TAG, "Default geo intent failed, trying Google Maps / Waze browser: " + t.getMessage());
            try {
                Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(query, "UTF-8")));
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(webIntent);
            } catch (Throwable ignored) {}
        }
    }

    public static void sendLocalGet(final String path) {
        if (!VehicleRequestPolicy.allowed("GET", path, "")) {
            Log.w(TAG, "Legacy vehicle control unavailable in reviewed candidate");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(BASE_URL + path);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    int code = conn.getResponseCode();
                    Log.i(TAG, "Local GET " + path + " -> " + code);
                } catch (Throwable t) {
                    Log.e(TAG, "Failed local GET " + path + ": " + t.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }
}
