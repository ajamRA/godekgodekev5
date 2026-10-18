package com.noor.thenoorcar;

import java.util.regex.Pattern;

/** Pure policy shared by the Android transport and laptop tests. */
public final class VehicleRequestPolicy {
    private VehicleRequestPolicy() {}

    private static final Pattern GET_CLIMATE_TEMP = Pattern.compile("^/api/climate/temp\\?target=(1[7-9](\\.[0-9]+)?|2[0-9](\\.[0-9]+)?|3[0-1](\\.[0-9]+)?|32(\\.0+)?)$");
    private static final Pattern GET_CLIMATE_FAN = Pattern.compile("^/api/climate/fan\\?level=[1-8]$");
    private static final Pattern GET_CLIMATE_AUTO = Pattern.compile("^/api/climate/auto\\?mode=(adaptive|quick_cool|comfort|off)&enabled=(true|false)$");
    private static final Pattern GET_CLIMATE_POWER = Pattern.compile("^/api/climate/power\\?state=[01]$");
    private static final Pattern GET_CLIMATE_AC = Pattern.compile("^/api/climate/ac\\?state=[01]$");
    private static final Pattern GET_CLIMATE_RECIRC = Pattern.compile("^/api/climate/recirc\\?state=[01]$");
    private static final Pattern POST_REGEN = Pattern.compile("^\\{\\s*\"regen\"\\s*:\\s*[1-3]\\s*\\}$");

    public static boolean allowed(String method, String path, String body) {
        if (method == null || path == null || body == null || body.length() > 128) return false;
        if (path.contains("..") || path.contains("\r") || path.contains("\n") || !path.startsWith("/")) return false;

        if ("GET".equals(method)) {
            if (!body.isEmpty()) return false;
            if ("/api/energy".equals(path) || "/api/status".equals(path) || "/api/drive_mode".equals(path)) return true;
            if ("/api/window?action=crack".equals(path) || "/api/window?action=close".equals(path)) return true;
            if (GET_CLIMATE_TEMP.matcher(path).matches()) return true;
            if (GET_CLIMATE_FAN.matcher(path).matches()) return true;
            if (GET_CLIMATE_AUTO.matcher(path).matches()) return true;
            if (GET_CLIMATE_POWER.matcher(path).matches()) return true;
            if (GET_CLIMATE_AC.matcher(path).matches()) return true;
            if (GET_CLIMATE_RECIRC.matcher(path).matches()) return true;
            return false;
        }

        if ("POST".equals(method)) {
            return "/api/drive_mode".equals(path) && POST_REGEN.matcher(body).matches();
        }

        return false;
    }
}
