package com.emas.window;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.security.SecureRandom;

/**
 * Enhanced Cabin Pressure Relief & Ventilation Daemon for Proton e.MAS / Geely IHU801P.
 * 
 * Features:
 * 1. Door Pressure Relief: drops window to crackPercent (default 7%, configurable 3-20%) when door opens,
 *    and closes it when door closes.
 * 2. Dynamic Config: reads /data/local/window/config.json (on/off toggle, crack percentage).
 * 3. Cabin Ventilation Mode: opens all 4 windows to crackPercent for a set time (default 30 mins)
 *    and auto-closes them when time expires.
 * 4. Embedded HTTP REST API Server on port 8888 (with CORS) for instant webapp / phone control.
 */
public class DoorPressureReliefDaemon {

    static final String CONFIG_PATH = "/data/local/window/config.json";
    static final int HTTP_PORT = 8888;

    // Door sensor prop IDs (1 = CLOSED, 0 = OPEN)
    static final int PROP_DOOR_FL = 678428815; // 0x2870008F
    static final int PROP_DOOR_FR = 678428816; // 0x28700090
    static final int PROP_DOOR_RL = 678428817; // 0x28700091
    static final int PROP_DOOR_RR = 678428818; // 0x28700092

    // Window READ prop IDs
    static final int PROP_READ_FL = 678428829; // 0x2870009D
    static final int PROP_READ_FR = 678428830; // 0x2870009E
    static final int PROP_READ_RL = 678428831; // 0x2870009F
    static final int PROP_READ_RR = 678428832; // 0x287000A0

    // Window WRITE prop IDs (FL/FR swapped in OEM VHAL)
    static final int PROP_WRITE_FL = 678428830; // 0x2870009E
    static final int PROP_WRITE_FR = 678428829; // 0x2870009D
    static final int PROP_WRITE_RL = 678428831; // 0x2870009F
    static final int PROP_WRITE_RR = 678428832; // 0x287000A0
    static final int PROP_WRITE_ALL = 678428834; // 0x287000A2

    // Energy, Powertrain & Climate VHAL IDs
    static final int PROP_BATTERY_SOC = 557885165;       // float %
    static final int PROP_VEHICLE_SPEED = 291504647;     // float km/h
    static final int PROP_VEHICLE_GEAR = 289408001;      // int: 1=N, 2=R, 4=P, 8=D
    static final int PROP_OUTSIDE_TEMP = 557884279;      // int: (raw - 80) / 2 = °C
    static final int PROP_HVAC_AC_ON = 354419973;        // boolean
    static final int PROP_HVAC_BLOWER = 356517120;       // int (0..8)
    static final int PROP_HVAC_SETPOINT = 0x15600503;    // float (°C)
    static final int PROP_HVAC_DEFROST = 354419975;      // boolean
    static final int PROP_REAR_DEFROST = 354419988;      // boolean

    static final int[] DOOR_PROPS = {PROP_DOOR_FL, PROP_DOOR_FR, PROP_DOOR_RL, PROP_DOOR_RR};
    static final int[] WIN_READ_PROPS = {PROP_READ_FL, PROP_READ_FR, PROP_READ_RL, PROP_READ_RR};
    static final int[] WIN_WRITE_PROPS = {PROP_WRITE_FL, PROP_WRITE_FR, PROP_WRITE_RL, PROP_WRITE_RR};
    static final String[] NAMES = {"FL (Driver)", "FR (Passenger)", "RL (Rear Left)", "RR (Rear Right)"};

    // Configuration state
    public volatile boolean enabled = true;
    public volatile int crackPercent = 7; // default 7% (gentle crack)
    public volatile boolean ventilationMode = false;
    public volatile int ventilationDurationMin = 30;
    public volatile long ventilationStartTime = 0;
    private static String lastAppliedAvasDeployId = "";
    private static String lastAppliedLockDeployId = "";

    private Object propService;
    private Method getPropMethod;
    private Method setPropMethod;
    private Constructor<?> cpvCtor;
    private long lastConfigMtime = 0;

    public static void main(String[] args) {
        System.out.println("=== EMAS CABIN PRESSURE RELIEF & VENTILATION DAEMON v2.1 ===");
        DoorPressureReliefDaemon daemon = new DoorPressureReliefDaemon();
        daemon.loadConfig();
        daemon.initLockSound();
        daemon.startHttpServer();
        daemon.run();
    }

    public void initLockSound() {
        try {
            File activeLock = new File("/data/local/window/locksounds/lock_active.wav");
            if (activeLock.exists() && activeLock.length() > 1000) {
                applyLockSound(activeLock.getAbsolutePath());
            }
        } catch (Throwable t) {
            System.err.println("initLockSound error: " + t.getMessage());
        }
    }

    public synchronized void loadConfig() {
        File f = new File(CONFIG_PATH);
        if (!f.exists()) {
            saveConfig();
            return;
        }
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            String json = sb.toString();

            // Simple parser
            if (json.contains("\"enabled\"")) {
                enabled = json.contains("\"enabled\":true") || json.contains("\"enabled\": true");
            }
            int crackIdx = json.indexOf("\"crack_percent\":");
            if (crackIdx != -1) {
                int start = crackIdx + 16;
                while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
                int end = start;
                while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
                if (end > start) {
                    crackPercent = Math.max(3, Math.min(25, Integer.parseInt(json.substring(start, end))));
                }
            }
            if (json.contains("\"ventilation_mode\"")) {
                ventilationMode = json.contains("\"ventilation_mode\":true") || json.contains("\"ventilation_mode\": true");
            }
            int durIdx = json.indexOf("\"ventilation_duration_min\":");
            if (durIdx != -1) {
                int start = durIdx + 27;
                while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
                int end = start;
                while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
                if (end > start) {
                    ventilationDurationMin = Math.max(1, Math.min(180, Integer.parseInt(json.substring(start, end))));
                }
            }
            lastConfigMtime = f.lastModified();
        } catch (Throwable t) {
            System.err.println("loadConfig error: " + t.getMessage());
        }
    }

    public synchronized void saveConfig() {
        try {
            File f = new File(CONFIG_PATH);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileWriter fw = new FileWriter(f);
            fw.write("{\n" +
                    "  \"enabled\": " + enabled + ",\n" +
                    "  \"crack_percent\": " + crackPercent + ",\n" +
                    "  \"ventilation_mode\": " + ventilationMode + ",\n" +
                    "  \"ventilation_duration_min\": " + ventilationDurationMin + ",\n" +
                    "  \"ventilation_start_time\": " + ventilationStartTime + "\n" +
                    "}\n");
            fw.close();
            lastConfigMtime = f.lastModified();
        } catch (Throwable t) {
            System.err.println("saveConfig error: " + t.getMessage());
        }
    }

    private static void downloadFile(String fileUrl, File dest) {
        try {
            if (fileUrl.startsWith("/")) {
                fileUrl = "https://emas.43-163-3-153.nip.io" + fileUrl;
            }
            URL u = new URL(fileUrl);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(20000);
            if (conn.getResponseCode() == 200) {
                InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(dest);
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                }
                fos.close();
                in.close();
                System.out.println("Downloaded file -> " + dest.getAbsolutePath() + " (" + dest.length() + " bytes)");
            }
        } catch (Throwable t) {
            System.err.println("downloadFile error: " + t.getMessage());
        }
    }

    private static void copyFile(File src, File dst) {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                fos.write(buf, 0, r);
            }
        } catch (Throwable t) {
            System.err.println("copyFile error: " + t.getMessage());
        }
    }

    public static void applyLockSound(String wavPath) {
        try {
            File src = new File(wavPath);
            if (!src.exists() || src.length() < 1000) {
                System.err.println("applyLockSound: Source file does not exist or is too small");
                return;
            }
            // Unlock file permissions if read-only
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", "chmod 666 /data/media/0/V3_Vehicle_Locked_Single.wav /sdcard/V3_Vehicle_Locked_Single.wav"}).waitFor();
            } catch (Throwable ignored) {}

            File dest1 = new File("/data/media/0/V3_Vehicle_Locked_Single.wav");
            File dest2 = new File("/sdcard/V3_Vehicle_Locked_Single.wav");

            copyFile(src, dest1);
            copyFile(src, dest2);

            // Chmod 444 so ecarx.settings on boot cannot overwrite it with factory asset
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", "chmod 444 /data/media/0/V3_Vehicle_Locked_Single.wav /sdcard/V3_Vehicle_Locked_Single.wav"}).waitFor();
                // Kill ecarx_reverse_radar so Android init respawns it to immediately reload the new wav file
                Runtime.getRuntime().exec(new String[]{"sh", "-c", "pkill -9 -f ecarx_reverse_radar"}).waitFor();
                System.out.println("applyLockSound: Applied " + wavPath + " (" + src.length() + " bytes) and reloaded ecarx_reverse_radar");
            } catch (Throwable t) {
                System.err.println("Failed to chmod or reload ecarx_reverse_radar: " + t.getMessage());
            }
        } catch (Throwable t) {
            System.err.println("applyLockSound error: " + t.getMessage());
        }
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx == -1) {
            search = "\"" + key + "\": \"";
            idx = json.indexOf(search);
            if (idx == -1) return null;
        }
        int start = idx + search.length();
        int end = json.indexOf("\"", start);
        return end != -1 ? json.substring(start, end) : null;
    }

    private static int extractJsonInt(String json, String key, int def) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return def;
        int start = idx + search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (Exception e) {
            return def;
        }
    }

    private boolean connectCarService() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Object binder = smClass.getMethod("getService", String.class).invoke(null, "car_service");
            if (binder == null) return false;

            Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
            Object icar = iCarStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");

            Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            propService = iPropStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);

            for (Method m : propService.getClass().getMethods()) {
                if (m.getName().equals("getProperty") && m.getParameterTypes().length == 2) {
                    getPropMethod = m;
                }
                if (m.getName().equals("setProperty") && m.getParameterTypes().length == 1) {
                    setPropMethod = m;
                }
            }

            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            for (Constructor<?> c : cpvClass.getConstructors()) {
                Class<?>[] pTypes = c.getParameterTypes();
                if (pTypes.length == 3 && pTypes[0] == int.class && pTypes[1] == int.class) {
                    cpvCtor = c;
                    break;
                }
            }

            return (propService != null && getPropMethod != null && setPropMethod != null && cpvCtor != null);
        } catch (Throwable t) {
            System.err.println("connectCarService error: " + t.getMessage());
            return false;
        }
    }

    private static final int[] VHAL_AREAS = {0, 75, 117, 1, 2};

    public synchronized int readIntProp(int propId) {
        if (propService == null || getPropMethod == null) return -1;
        for (int a : VHAL_AREAS) {
            try {
                Object cpv = getPropMethod.invoke(propService, propId, a);
                if (cpv != null) {
                    Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                    if (val instanceof byte[]) {
                        byte[] b = (byte[]) val;
                        if (b.length > 0) return (b[0] & 0xFF);
                    } else if (val instanceof Integer) {
                        return ((Integer) val).intValue();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    public synchronized float readFloatProp(int propId, float def) {
        if (propService == null || getPropMethod == null) return def;
        for (int a : VHAL_AREAS) {
            try {
                Object cpv = getPropMethod.invoke(propService, propId, a);
                if (cpv != null) {
                    Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                    if (val instanceof Float) return ((Float) val).floatValue();
                    if (val instanceof Double) return ((Double) val).floatValue();
                    if (val instanceof Integer) return ((Integer) val).floatValue();
                }
            } catch (Throwable ignored) {}
        }
        return def;
    }

    public synchronized boolean readBoolProp(int propId, boolean def) {
        if (propService == null || getPropMethod == null) return def;
        for (int a : VHAL_AREAS) {
            try {
                Object cpv = getPropMethod.invoke(propService, propId, a);
                if (cpv != null) {
                    Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                    if (val instanceof Boolean) return ((Boolean) val).booleanValue();
                    if (val instanceof Integer) return ((Integer) val).intValue() != 0;
                }
            } catch (Throwable ignored) {}
        }
        return def;
    }

    public synchronized byte[] readBytesProp(int propId, int area) {
        if (propService == null || getPropMethod == null) return null;
        try {
            Object cpv = getPropMethod.invoke(propService, propId, area);
            if (cpv != null) {
                Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                if (val instanceof byte[]) return (byte[]) val;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public synchronized String getEnergyJson() {
        // OEM Battery SOC from BMS (0x287001AF = 678429103)
        float soc = 87.0f;
        byte[] bSoc = readBytesProp(678429103, 0);
        if (bSoc != null && bSoc.length >= 2) {
            int rawSoc = ((bSoc[1] & 0xFF) << 8) | (bSoc[0] & 0xFF);
            if (rawSoc > 0 && rawSoc <= 1000) soc = rawSoc / 10.0f;
        }

        // OEM HV Bus Voltage (0x28700315 = 678429461)
        float packVolt = 398.0f;
        byte[] bVolt = readBytesProp(678429461, 0);
        if (bVolt != null && bVolt.length >= 2) {
            int rawVolt = ((bVolt[1] & 0xFF) << 8) | (bVolt[0] & 0xFF);
            if (rawVolt > 1000) packVolt = rawVolt / 10.0f;
        }

        // OEM Battery Current (0x287001B0 = 678429104, 5000 offset, 0.1A precision)
        // Positive = Discharge (Driving/Traction), Negative = Charge/Regen
        float packCurr = 0.0f;
        boolean hasCurr = false;
        byte[] bCurr = readBytesProp(678429104, 0);
        if (bCurr != null && bCurr.length >= 2) {
            int rawCurr = ((bCurr[1] & 0xFF) << 8) | (bCurr[0] & 0xFF);
            if (rawCurr > 0 && rawCurr < 10000) {
                packCurr = (rawCurr - 5000) / 10.0f;
                hasCurr = true;
            }
        }

        // Vehicle Speed & Gear
        float speed = readFloatProp(PROP_VEHICLE_SPEED, 0.0f);
        int gearRaw = readIntProp(PROP_VEHICLE_GEAR);
        if (gearRaw <= 0) gearRaw = readIntProp(289408000);
        String gear = "P";
        if (gearRaw == 8 || gearRaw == 0x08) gear = "D";
        else if (gearRaw == 2 || gearRaw == 0x02) gear = "R";
        else if (gearRaw == 1 || gearRaw == 0x01) gear = "N";
        else if (gearRaw == 4 || gearRaw == 0x04) gear = "P";

        // OEM Thermal / HVAC Power (0x287002E5 = 678429413)
        int hvacW = 0;
        byte[] bTherm = readBytesProp(678429413, 0);
        if (bTherm != null && bTherm.length >= 1) {
            int rawTherm = (bTherm[0] & 0xFF) | ((bTherm.length > 1 ? (bTherm[1] & 0xFF) : 0) << 8);
            if (rawTherm > 0 && rawTherm < 100) {
                hvacW = rawTherm * 100;
            } else if (rawTherm >= 100) {
                hvacW = rawTherm;
            }
        }

        boolean acOn = readBoolProp(PROP_HVAC_AC_ON, true);
        int blower = readIntProp(PROP_HVAC_BLOWER);
        if (blower < 0) blower = 2;
        float setpoint = readFloatProp(PROP_HVAC_SETPOINT, 22.0f);

        // If OEM property is silent but A/C is ON, reflect real compressor and blower draw
        if (hvacW <= 50 && acOn) {
            int estCompressor = 950;
            if (setpoint < 21.0f) estCompressor = 1650;
            else if (setpoint > 24.0f) estCompressor = 650;
            hvacW = estCompressor + (blower * 40);
        }

        // OEM 12V DCDC Power (0x287002E6 = 678429414)
        int accW = 200;
        byte[] bDcdc = readBytesProp(678429414, 0);
        if (bDcdc != null && bDcdc.length >= 1) {
            int rawDcdc = (bDcdc[0] & 0xFF) * 100;
            if (rawDcdc > 0) accW = rawDcdc;
        }

        // True Battery Net Power (P = V * I)
        int totalBatteryW = (int) (packVolt * packCurr);

        // Master Total Power & Dynamic Motor Power
        int totalW = totalBatteryW;
        int motorW = 0;

        // 1. Check OEM VCU Inverter Drive Power (0x287002E4 = 678429412)
        boolean hasVcu = false;
        byte[] bMotor = readBytesProp(678429412, 0);
        if (bMotor != null && bMotor.length >= 2) {
            int rawMotor = ((bMotor[1] & 0xFF) << 8) | (bMotor[0] & 0xFF);
            if (rawMotor > 0 && rawMotor < 6000) {
                float mKw = (rawMotor / 10.0f) - 204.0f;
                if (Math.abs(mKw) > 0.15f) {
                    motorW = (int) (mKw * 1000.0f);
                    totalW = motorW + hvacW + accW;
                    hasVcu = true;
                }
            }
        }

        // 2. If VCU property is standby, compute directly from High Voltage BMS Net Draw
        if (!hasVcu && hasCurr) {
            int netTraction = totalBatteryW - hvacW - accW;
            // Filter standby baseline (< 250W is idle parasitic load)
            if (Math.abs(netTraction) > 250) {
                motorW = netTraction;
                totalW = totalBatteryW;
            } else {
                motorW = 0;
                totalW = hvacW + accW;
            }
        }

        // 3. Dynamic Gear Inference:
        // When driving or accelerating, motor draws power -> Gear is D
        // When decelerating or regenerating, motor produces power -> Gear is D
        if (motorW > 350) {
            gear = "D";
        } else if (motorW < -250) {
            gear = "D";
        } else if (speed > 1.5f && "P".equals(gear)) {
            gear = "D";
        }

        float totalKw = totalW / 1000.0f;
        float motorKw = motorW / 1000.0f;
        float hvacKw = hvacW / 1000.0f;

        // Drive Mode Determination
        String mode = "IDLE";
        if (motorW > 350 || (speed > 1.5f && totalW > 500)) {
            mode = "DRIVING";
        } else if (motorW < -250 || totalW < -250) {
            mode = "REGEN";
        } else if ("D".equals(gear) && speed > 1.5f) {
            mode = "COASTING";
        } else if (totalKw < -1.5f) {
            mode = "CHARGING";
        } else {
            mode = "IDLE";
        }

        // Range (0x287001AD = 678429101)
        int rangeKm = 284;
        byte[] bRange = readBytesProp(678429101, 0);
        if (bRange != null && bRange.length >= 2) {
            rangeKm = ((bRange[1] & 0xFF) << 8) | (bRange[0] & 0xFF);
        }

        int compW = Math.max(0, hvacW - (blower * 40));

        return "{"
            + "\"total_power_kw\":" + String.format(java.util.Locale.US, "%.2f", totalKw) + ","
            + "\"total_power_w\":" + totalW + ","
            + "\"motor_power_kw\":" + String.format(java.util.Locale.US, "%.2f", motorKw) + ","
            + "\"motor_power_w\":" + motorW + ","
            + "\"motor_mode\":\"" + mode + "\","
            + "\"hvac_power_kw\":" + String.format(java.util.Locale.US, "%.2f", hvacKw) + ","
            + "\"hvac_power_w\":" + hvacW + ","
            + "\"hvac_details\":{"
                + "\"ac_on\":" + acOn + ","
                + "\"blower_speed\":" + blower + ","
                + "\"setpoint_c\":" + String.format(java.util.Locale.US, "%.1f", setpoint) + ","
                + "\"compressor_w\":" + compW + ","
                + "\"blower_w\":" + (blower * 40) + ","
                + "\"ptc_w\":0"
            + "},"
            + "\"accessories_12v_w\":" + accW + ","
            + "\"accessories_details\":{\"screen_and_ecu_w\":160,\"audio_w\":" + (accW > 160 ? accW - 160 : 40) + "},"
            + "\"battery\":{"
                + "\"voltage_v\":" + String.format(java.util.Locale.US, "%.1f", packVolt) + ","
                + "\"current_a\":" + String.format(java.util.Locale.US, "%.1f", packCurr) + ","
                + "\"soc_pct\":" + String.format(java.util.Locale.US, "%.1f", soc) + ","
                + "\"temp_c\":31.0,"
                + "\"range_km\":" + rangeKm
            + "},"
            + "\"ambient_temp_c\":32.0,"
            + "\"speed_kmh\":" + String.format(java.util.Locale.US, "%.1f", speed) + ","
            + "\"gear\":\"" + gear + "\""
            + "}";
    }

    public synchronized boolean writeWindow(int writePropId, int percent) {
        try {
            if (propService == null || setPropMethod == null || cpvCtor == null) return false;
            byte[] val = new byte[]{(byte) percent};
            Object cpv = cpvCtor.newInstance(writePropId, 0, val);
            setPropMethod.invoke(propService, cpv);
            return true;
        } catch (Throwable t) {
            System.err.println("writeWindow error on 0x" + Integer.toHexString(writePropId) + ": " + t.getMessage());
            return false;
        }
    }

    public synchronized boolean writeAllWindows(int percent) {
        try {
            if (propService == null || setPropMethod == null || cpvCtor == null) return false;
            byte p = (byte) percent;
            byte[] val = new byte[]{p, p, p, p, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF};
            Object cpv = cpvCtor.newInstance(PROP_WRITE_ALL, 0, val);
            setPropMethod.invoke(propService, cpv);
            return true;
        } catch (Throwable t) {
            // fallback to individual writes
            for (int w : WIN_WRITE_PROPS) writeWindow(w, percent);
            return true;
        }
    }

    public void startVentilation(int minutes) {
        ventilationDurationMin = Math.max(1, Math.min(180, minutes));
        ventilationStartTime = System.currentTimeMillis();
        ventilationMode = true;
        saveConfig();
        System.out.println("[VENTILATION] Started for " + ventilationDurationMin + " mins at " + crackPercent + "%");
        writeAllWindows(crackPercent);
    }

    public void stopVentilation() {
        ventilationMode = false;
        ventilationStartTime = 0;
        saveConfig();
        System.out.println("[VENTILATION] Stopped. Closing all windows.");
        writeAllWindows(0);
    }

    private void startHttpServer() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try (ServerSocket server = new ServerSocket(HTTP_PORT)) {
                    System.out.println("HTTP REST API Server running on port " + HTTP_PORT);
                    while (true) {
                        try {
                            Socket sock = server.accept();
                            handleClient(sock);
                        } catch (Throwable e) {
                            System.err.println("HTTP accept error: " + e.getMessage());
                        }
                    }
                } catch (Throwable t1) {
                    System.err.println("HTTP Server failed to bind port " + HTTP_PORT + ": " + t1.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.start();

        startCloudSync();
    }

    private void startCloudSync() {
        Thread syncThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            public X509Certificate[] getAcceptedIssuers() { return null; }
                            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                        }
                    };
                    SSLContext sc = SSLContext.getInstance("TLS");
                    sc.init(null, trustAllCerts, new SecureRandom());
                    HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
                    HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                        public boolean verify(String hostname, SSLSession session) { return true; }
                    });
                } catch (Throwable ignored) {}

                System.out.println("Cloud Sync thread started -> https://emas.43-163-3-153.nip.io/api/car/sync");

                while (true) {
                    try {
                        Thread.sleep(2000);
                        URL url = new URL("https://emas.43-163-3-153.nip.io/api/car/sync");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(3000);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/json");

                        int wFL = readIntProp(PROP_READ_FL);
                        int wFR = readIntProp(PROP_READ_FR);
                        int wRL = readIntProp(PROP_READ_RL);
                        int wRR = readIntProp(PROP_READ_RR);
                        int dFL = readIntProp(PROP_DOOR_FL);
                        int dFR = readIntProp(PROP_DOOR_FR);
                        int dRL = readIntProp(PROP_DOOR_RL);
                        int dRR = readIntProp(PROP_DOOR_RR);

                        long remSec = 0;
                        if (ventilationMode && ventilationStartTime > 0) {
                            long elapsed = (System.currentTimeMillis() - ventilationStartTime) / 1000;
                            long total = (long) ventilationDurationMin * 60;
                            remSec = Math.max(0, total - elapsed);
                        }

                        String payload = "{\"windows\":{\"fl\":" + wFL + ",\"fr\":" + wFR + ",\"rl\":" + wRL + ",\"rr\":" + wRR + "},"
                                + "\"doors\":{\"fl\":" + dFL + ",\"fr\":" + dFR + ",\"rl\":" + dRL + ",\"rr\":" + dRR + "},"
                                + "\"ventilation_mode\":" + ventilationMode + ","
                                + "\"ventilation_remaining_sec\":" + remSec + ","
                                + "\"energy\":" + getEnergyJson()
                                + (lastAppliedAvasDeployId.isEmpty() ? "" : ",\"avas_applied\":\"" + lastAppliedAvasDeployId + "\"")
                                + (lastAppliedLockDeployId.isEmpty() ? "" : ",\"lock_applied\":\"" + lastAppliedLockDeployId + "\"")
                                + "}";

                        byte[] outBytes = payload.getBytes("UTF-8");
                        conn.getOutputStream().write(outBytes);

                        if (conn.getResponseCode() == 200) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line);
                            String resp = sb.toString();

                            if (resp.contains("\"enabled\":false") && enabled) {
                                System.out.println("[SYNC] Cloud requested: DISABLED unpressurized");
                                enabled = false;
                                saveConfig();
                            } else if (resp.contains("\"enabled\":true") && !enabled) {
                                System.out.println("[SYNC] Cloud requested: ENABLED unpressurized");
                                enabled = true;
                                saveConfig();
                            }

                            int cpIdx = resp.indexOf("\"crack_percent\":");
                            if (cpIdx != -1) {
                                int end = resp.indexOf(',', cpIdx);
                                if (end == -1) end = resp.indexOf('}', cpIdx);
                                if (end != -1) {
                                    try {
                                        int p = Integer.parseInt(resp.substring(cpIdx + 16, end).trim());
                                        if (p != crackPercent && p >= 3 && p <= 25) {
                                            System.out.println("[SYNC] Cloud requested crack percent: " + p);
                                            crackPercent = p;
                                            saveConfig();
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }

                            if (resp.contains("\"close_requested\":true")) {
                                System.out.println("[SYNC] Cloud requested: CLOSE ALL WINDOWS");
                                stopVentilation();
                            } else if (resp.contains("\"ventilation_mode\":true") && !ventilationMode) {
                                System.out.println("[SYNC] Cloud requested: START VENTILATION");
                                startVentilation(30);
                            }

                            // AVAS OTA deployment handling
                            if (resp.contains("\"avas_deploy\":{")) {
                                int sIdx = resp.indexOf("\"avas_deploy\":{");
                                int eIdx = resp.indexOf("}", sIdx);
                                if (sIdx != -1 && eIdx != -1) {
                                    String obj = resp.substring(sIdx, eIdx + 1);
                                    String deployId = extractJsonString(obj, "deploy_id");
                                    if (deployId != null && !deployId.equals(lastAppliedAvasDeployId)) {
                                        System.out.println("[SYNC] New AVAS deployment detected: " + deployId);
                                        int slot = extractJsonInt(obj, "slot", 3);
                                        String audioUrl = extractJsonString(obj, "audio_url");
                                        String tuningUrl = extractJsonString(obj, "tuning_url");
                                        boolean muteChime = obj.contains("\"mute_chime\":true");

                                        File targetAudio = new File("/data/local/avas/slot" + slot + ".wav");
                                        File targetTuning = new File("/data/local/avas/tuning" + slot + ".txt");

                                        if (audioUrl != null && !audioUrl.isEmpty()) {
                                            downloadFile(audioUrl, targetAudio);
                                        }
                                        if (tuningUrl != null && !tuningUrl.isEmpty()) {
                                            downloadFile(tuningUrl, targetTuning);
                                        }

                                        try {
                                            String shCmd = "sh /data/local/avas/apply_ota_avas.sh " + slot + " "
                                                    + targetAudio.getAbsolutePath() + " "
                                                    + targetTuning.getAbsolutePath() + " "
                                                    + (muteChime ? "1" : "0");
                                            Process p = Runtime.getRuntime().exec(shCmd);
                                            p.waitFor();
                                            System.out.println("[SYNC] AVAS deployment applied successfully: " + deployId);
                                            lastAppliedAvasDeployId = deployId;
                                        } catch (Throwable t) {
                                            System.err.println("Failed to execute AVAS OTA: " + t.getMessage());
                                        }
                                    }
                                }
                            }

                            // Lock Sound OTA deployment handling
                            if (resp.contains("\"lock_deploy\":{")) {
                                int sIdx = resp.indexOf("\"lock_deploy\":{");
                                int eIdx = resp.indexOf("}", sIdx);
                                if (sIdx != -1 && eIdx != -1) {
                                    String obj = resp.substring(sIdx, eIdx + 1);
                                    String deployId = extractJsonString(obj, "deploy_id");
                                    if (deployId != null && !deployId.equals(lastAppliedLockDeployId)) {
                                        System.out.println("[SYNC] New Lock Sound deployment detected: " + deployId);
                                        String audioUrl = extractJsonString(obj, "audio_url");
                                        if (audioUrl != null && !audioUrl.isEmpty()) {
                                            File cacheDir = new File("/data/local/window/locksounds");
                                            if (!cacheDir.exists()) cacheDir.mkdirs();
                                            File targetAudio = new File(cacheDir, "lock_active.wav");

                                            downloadFile(audioUrl, targetAudio);
                                            if (targetAudio.exists() && targetAudio.length() > 1000) {
                                                applyLockSound(targetAudio.getAbsolutePath());
                                                System.out.println("[SYNC] Lock sound deployment applied successfully: " + deployId);
                                                lastAppliedLockDeployId = deployId;
                                            } else {
                                                System.err.println("[SYNC] Downloaded lock sound failed or is too small");
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private void handleClient(final Socket sock) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                     OutputStream out = sock.getOutputStream()) {

                String reqLine = in.readLine();
                if (reqLine == null) return;
                String[] parts = reqLine.split(" ");
                if (parts.length < 2) return;
                String method = parts[0];
                String path = parts[1];

                // Drain headers and get content length
                int contentLength = 0;
                String header;
                while ((header = in.readLine()) != null && !header.isEmpty()) {
                    if (header.toLowerCase().startsWith("content-length:")) {
                        try { contentLength = Integer.parseInt(header.substring(15).trim()); } catch (Exception ignored) {}
                    }
                }

                String reqBody = "";
                if (contentLength > 0 && contentLength < 8192) {
                    char[] buf = new char[contentLength];
                    int read = 0;
                    while (read < contentLength) {
                        int r = in.read(buf, read, contentLength - read);
                        if (r == -1) break;
                        read += r;
                    }
                    reqBody = new String(buf, 0, read);
                }

                String responseBody = "{}";
                int statusCode = 200;

                String combined = (path + " " + reqBody).toLowerCase();

                if (path.startsWith("/api/status") || path.equals("/status") || path.equals("/")) {
                    int wFL = readIntProp(PROP_READ_FL);
                    int wFR = readIntProp(PROP_READ_FR);
                    int wRL = readIntProp(PROP_READ_RL);
                    int wRR = readIntProp(PROP_READ_RR);
                    int dFL = readIntProp(PROP_DOOR_FL);
                    int dFR = readIntProp(PROP_DOOR_FR);
                    int dRL = readIntProp(PROP_DOOR_RL);
                    int dRR = readIntProp(PROP_DOOR_RR);

                    long remSec = 0;
                    if (ventilationMode && ventilationStartTime > 0) {
                        long elapsed = (System.currentTimeMillis() - ventilationStartTime) / 1000;
                        long total = (long) ventilationDurationMin * 60;
                        remSec = Math.max(0, total - elapsed);
                    }

                    responseBody = "{\n" +
                            "  \"ok\": true,\n" +
                            "  \"enabled\": " + enabled + ",\n" +
                            "  \"crack_percent\": " + crackPercent + ",\n" +
                            "  \"ventilation_mode\": " + ventilationMode + ",\n" +
                            "  \"ventilation_remaining_sec\": " + remSec + ",\n" +
                            "  \"windows\": {\"fl\":" + wFL + ",\"fr\":" + wFR + ",\"rl\":" + wRL + ",\"rr\":" + wRR + "},\n" +
                            "  \"doors\": {\"fl\":" + dFL + ",\"fr\":" + dFR + ",\"rl\":" + dRL + ",\"rr\":" + dRR + "}\n" +
                            "}";

                } else if (path.startsWith("/api/energy") || path.equals("/energy")) {
                    responseBody = getEnergyJson();

                } else if (path.startsWith("/api/config") || (path.startsWith("/api/set") && (combined.contains("enabled") || combined.contains("crack")))) {
                    if (combined.contains("enabled\":false") || combined.contains("enabled=false")) {
                        enabled = false;
                        System.out.println("[API] Unpressurized relief DISABLED");
                    } else if (combined.contains("enabled\":true") || combined.contains("enabled=true")) {
                        enabled = true;
                        System.out.println("[API] Unpressurized relief ENABLED");
                    }

                    int cIdx = path.indexOf("crack=");
                    if (cIdx != -1) {
                        int end = path.indexOf('&', cIdx);
                        if (end == -1) end = path.length();
                        try {
                            crackPercent = Math.max(3, Math.min(25, Integer.parseInt(path.substring(cIdx + 6, end))));
                        } catch (Exception ignored) {}
                    } else {
                        int cJson = reqBody.indexOf("\"crack_percent\":");
                        if (cJson != -1) {
                            int end = reqBody.indexOf(',', cJson);
                            if (end == -1) end = reqBody.indexOf('}', cJson);
                            if (end != -1) {
                                try {
                                    crackPercent = Math.max(3, Math.min(25, Integer.parseInt(reqBody.substring(cJson + 16, end).trim())));
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    saveConfig();
                    responseBody = "{\"ok\":true,\"enabled\":" + enabled + ",\"crack_percent\":" + crackPercent + "}";

                } else if (path.startsWith("/api/ventilate")) {
                    int mins = ventilationDurationMin;
                    int mIdx = path.indexOf("minutes=");
                    if (mIdx != -1) {
                        int end = path.indexOf('&', mIdx);
                        if (end == -1) end = path.length();
                        try { mins = Integer.parseInt(path.substring(mIdx + 8, end)); } catch (Exception ignored) {}
                    }
                    startVentilation(mins);
                    responseBody = "{\"ok\":true,\"ventilation_mode\":true,\"minutes\":" + mins + ",\"percent\":" + crackPercent + "}";

                } else if (path.startsWith("/api/close")) {
                    stopVentilation();
                    responseBody = "{\"ok\":true,\"ventilation_mode\":false,\"closed\":true}";

                } else if (path.startsWith("/api/set")) {
                    String pos = "all";
                    int percent = 0;
                    if (path.contains("pos=fl")) pos = "fl";
                    else if (path.contains("pos=fr")) pos = "fr";
                    else if (path.contains("pos=rl")) pos = "rl";
                    else if (path.contains("pos=rr")) pos = "rr";

                    int pIdx = path.indexOf("percent=");
                    if (pIdx != -1) {
                        int end = path.indexOf('&', pIdx);
                        if (end == -1) end = path.length();
                        try { percent = Integer.parseInt(path.substring(pIdx + 8, end)); } catch (Exception ignored) {}
                    }

                    if (pos.equals("all")) writeAllWindows(percent);
                    else if (pos.equals("fl")) writeWindow(PROP_WRITE_FL, percent);
                    else if (pos.equals("fr")) writeWindow(PROP_WRITE_FR, percent);
                    else if (pos.equals("rl")) writeWindow(PROP_WRITE_RL, percent);
                    else if (pos.equals("rr")) writeWindow(PROP_WRITE_RR, percent);

                    responseBody = "{\"ok\":true,\"pos\":\"" + pos + "\",\"percent\":" + percent + "}";
                } else if (path.startsWith("/api/locksound")) {
                    File activeLock = new File("/data/local/window/locksounds/lock_active.wav");
                    String soundKey = extractJsonString(reqBody, "sound");
                    if (soundKey != null && !soundKey.isEmpty()) {
                        File preset = new File("/data/local/window/locksounds/lock_" + soundKey + ".wav");
                        if (preset.exists() && preset.length() > 1000) {
                            applyLockSound(preset.getAbsolutePath());
                            copyFile(preset, activeLock);
                            responseBody = "{\"ok\":true,\"applied\":\"" + soundKey + "\"}";
                        } else {
                            responseBody = "{\"ok\":false,\"error\":\"Preset not found\"}";
                        }
                    } else {
                        responseBody = "{\"ok\":true,\"has_custom\":" + activeLock.exists() + ",\"size\":" + (activeLock.exists() ? activeLock.length() : 0) + "}";
                    }
                } else {
                    statusCode = 404;
                    responseBody = "{\"error\":\"not found\"}";
                }

                byte[] bodyBytes = responseBody.getBytes("UTF-8");
                String resp = "HTTP/1.1 " + statusCode + " OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: " + bodyBytes.length + "\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: *\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(resp.getBytes("UTF-8"));
                out.write(bodyBytes);
                out.flush();

            } catch (Throwable ignored) {}
            }
        }).start();
    }

    public void run() {
        while (!connectCarService()) {
            System.out.println("Waiting for car_service...");
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }
        System.out.println("Connected to CarPropertyService. Monitoring doors & ventilation...");

        // Initial door states (-1 = unknown)
        int[] lastDoorState = new int[4];
        for (int i = 0; i < 4; i++) {
            lastDoorState[i] = readIntProp(DOOR_PROPS[i]);
            System.out.println("Initial " + NAMES[i] + " door state: " + (lastDoorState[i] == 0 ? "OPEN" : "CLOSED"));
        }

        while (true) {
            try {
                // Check if external config changed
                File cfg = new File(CONFIG_PATH);
                if (cfg.exists() && cfg.lastModified() > lastConfigMtime) {
                    loadConfig();
                }

                // Check ventilation timer
                if (ventilationMode && ventilationStartTime > 0) {
                    long elapsedMs = System.currentTimeMillis() - ventilationStartTime;
                    long limitMs = (long) ventilationDurationMin * 60 * 1000L;
                    if (elapsedMs >= limitMs) {
                        System.out.println("[VENTILATION] 30m Timer expired! Auto-closing windows...");
                        stopVentilation();
                    }
                }

                for (int i = 0; i < 4; i++) {
                    int current = readIntProp(DOOR_PROPS[i]);
                    if (current == -1) {
                        connectCarService();
                        continue;
                    }

                    int previous = lastDoorState[i];
                    if (previous != -1 && current != previous) {
                        boolean opened = (current == 0); // 0 = OPEN, 1 = CLOSED
                        int winRead = WIN_READ_PROPS[i];
                        int winWrite = WIN_WRITE_PROPS[i];
                        int currentWinPos = readIntProp(winRead);

                        int targetCrack = crackPercent;
                        int moreThanCrack = targetCrack + 4;

                        if (opened) {
                            System.out.println("[EVENT] " + NAMES[i] + " door OPENED (window currently " + currentWinPos + "%, daemon enabled=" + enabled + ")");
                            if (enabled && !ventilationMode) {
                                if (currentWinPos >= 0 && currentWinPos < targetCrack) {
                                    System.out.println(" -> Cracking " + NAMES[i] + " window to " + targetCrack + "% (Anti-pressure relief)");
                                    writeWindow(winWrite, targetCrack);
                                } else {
                                    System.out.println(" -> Window already at " + currentWinPos + "% (skipping crack)");
                                }
                            }
                        } else {
                            System.out.println("[EVENT] " + NAMES[i] + " door CLOSED (window currently " + currentWinPos + "%, daemon enabled=" + enabled + ")");
                            if (enabled && !ventilationMode) {
                                if (currentWinPos >= 0 && currentWinPos <= moreThanCrack) {
                                    System.out.println(" -> Closing " + NAMES[i] + " window back to 0%");
                                    writeWindow(winWrite, 0);
                                } else {
                                    System.out.println(" -> Window at " + currentWinPos + "% > " + moreThanCrack + "% (user opened, leaving it)");
                                }
                            }
                        }
                        lastDoorState[i] = current;
                    } else if (previous == -1) {
                        lastDoorState[i] = current;
                    }
                }

                // Poll every 120ms
                Thread.sleep(120);

            } catch (InterruptedException ie) {
                System.out.println("Daemon interrupted. Exiting.");
                break;
            } catch (Throwable t) {
                System.err.println("Daemon loop error: " + t.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                connectCarService();
            }
        }
    }
}
