package com.emas.window;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.*;

/**
 * Dedicated, Standalone Cabin Door Pressure Relief Daemon for Proton e.MAS 5 (IHU801P).
 * 
 * Single Responsibility:
 * 1. Door Pressure Relief: drops window to crackPercent (default 7%) when any door opens,
 *    and closes it back to 0% when that door closes.
 * 2. Dynamic Config: reads /data/local/window/config.json (on/off toggle, crack percentage).
 * 3. Ventilation Mode: opens all 4 windows to crackPercent for a configurable duration.
 * 4. Lightweight HTTP status on port 8885 (zero collision with TelemetryDaemon on 8888).
 * 5. Strictly ZERO Drive Mode tampering, ZERO audio bloat, ZERO ABRP bloat.
 */
public class DoorPressureReliefDaemon {

    static final String CONFIG_PATH = "/data/local/window/config.json";
    static final int HTTP_PORT = 8885; // Never collides with Telemetry (8888), Megaphone (8890), or AVAS (8889)

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

    // Window WRITE prop IDs (FL/FR swapped in Geely OEM VHAL)
    static final int PROP_WRITE_FL = 678428830; // 0x2870009E
    static final int PROP_WRITE_FR = 678428829; // 0x2870009D
    static final int PROP_WRITE_RL = 678428831; // 0x2870009F
    static final int PROP_WRITE_RR = 678428832; // 0x287000A0

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

    private Object propService;
    private Method getPropMethod;
    private Method setPropMethod;
    private Constructor<?> cpvCtor;
    private long lastConfigMtime = 0;

    public static void main(String[] args) {
        System.out.println("=== EMAS DEDICATED DOOR PRESSURE RELIEF DAEMON v3.1 (CLEAN) ===");
        DoorPressureReliefDaemon daemon = new DoorPressureReliefDaemon();
        daemon.loadConfig();
        daemon.startHttpServer();
        daemon.run();
    }

    public void loadConfig() {
        try {
            File f = new File(CONFIG_PATH);
            if (!f.exists()) return;
            if (f.lastModified() == lastConfigMtime) return;

            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            String json = sb.toString();

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
            lastConfigMtime = f.lastModified();
            System.out.println("[CONFIG] Loaded: enabled=" + enabled + ", crackPercent=" + crackPercent + "%");
        } catch (Throwable t) {
            System.err.println("loadConfig error: " + t.getMessage());
        }
    }

    private void connectCarService() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Object binder = smClass.getMethod("getService", String.class).invoke(null, "car_service");
            if (binder == null) {
                System.err.println("car_service binder is null!");
                return;
            }
            Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
            Object icar = iCarStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");
            Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            propService = iPropStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);

            for (Method m : propService.getClass().getMethods()) {
                if (m.getName().equals("getProperty") && m.getParameterTypes().length == 2) getPropMethod = m;
                if (m.getName().equals("setProperty") && m.getParameterTypes().length == 1) setPropMethod = m;
            }

            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            for (Constructor<?> c : cpvClass.getConstructors()) {
                Class<?>[] pTypes = c.getParameterTypes();
                if (pTypes.length == 3 && pTypes[0] == int.class && pTypes[1] == int.class) {
                    cpvCtor = c;
                    break;
                }
            }
            System.out.println("VHAL Service Connection: OK");
        } catch (Throwable t) {
            System.err.println("connectCarService error: " + t.getMessage());
        }
    }

    public int readIntProp(int propId, int area, int fallback) {
        if (propService == null || getPropMethod == null) return fallback;
        try {
            Object cpv = getPropMethod.invoke(propService, propId, area);
            if (cpv != null) {
                Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                if (val instanceof Integer) return (Integer) val;
                if (val instanceof byte[]) {
                    byte[] bytes = (byte[]) val;
                    if (bytes.length > 0) return bytes[0] & 0xFF;
                }
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    public int readIntProp(int propId) {
        return readIntProp(propId, 0, -1);
    }

    public synchronized boolean writeWindow(int writePropId, int percent) {
        if (percent < 0 || percent > 100) return false;
        boolean allowed = false;
        for (int id : WIN_WRITE_PROPS) if (id == writePropId) allowed = true;
        if (!allowed) return false;

        if (propService == null || setPropMethod == null || cpvCtor == null) {
            connectCarService();
            if (propService == null || setPropMethod == null || cpvCtor == null) return false;
        }
        try {
            Object cpv = cpvCtor.newInstance(writePropId, 0, new byte[]{(byte) percent});
            setPropMethod.invoke(propService, cpv);
            return true;
        } catch (Throwable t) {
            System.err.println("writeWindow error (prop=" + writePropId + "): " + t);
            return false;
        }
    }

    public synchronized boolean writeAllWindows(int percent) {
        if (percent < 0 || percent > 100) return false;
        boolean ok = true;
        for (int w : WIN_WRITE_PROPS) ok = writeWindow(w, percent) && ok;
        return ok;
    }

    public void startVentilation(int minutes) {
        ventilationDurationMin = Math.max(1, Math.min(180, minutes));
        ventilationStartTime = System.currentTimeMillis();
        ventilationMode = true;
        System.out.println("[VENTILATION] Started for " + ventilationDurationMin + " mins at " + crackPercent + "%");
        writeAllWindows(crackPercent);
    }

    public void stopVentilation() {
        ventilationMode = false;
        ventilationStartTime = 0;
        System.out.println("[VENTILATION] Stopped. Closing all windows.");
        writeAllWindows(0);
    }

    private void startHttpServer() {
        Thread t = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(HTTP_PORT, 16, InetAddress.getByName("127.0.0.1"))) {
                System.out.println("[HTTP] DoorPressureRelief status server listening on 127.0.0.1:" + HTTP_PORT);
                while (true) {
                    try {
                        Socket sock = server.accept();
                        sock.setSoTimeout(2000);
                        BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                        String req = in.readLine();
                        String path = "/";
                        if (req != null) {
                            String[] p = req.trim().split("\\s+");
                            if (p.length >= 2) path = p[1];
                        }
                        String respBody;
                        if (path.startsWith("/api/window")) {
                            if (path.contains("percent=0") || path.contains("action=close")) {
                                writeAllWindows(0);
                                respBody = "{\"ok\":true,\"action\":\"close_windows\"}";
                            } else {
                                writeAllWindows(crackPercent);
                                respBody = "{\"ok\":true,\"action\":\"crack_windows\",\"percent\":" + crackPercent + "}";
                            }
                        } else {
                            respBody = "{\"ok\":true,\"daemon\":\"door_pressure_relief\",\"enabled\":" + enabled 
                                     + ",\"crack_percent\":" + crackPercent + ",\"ventilation_mode\":" + ventilationMode + "}";
                        }
                        byte[] out = respBody.getBytes("UTF-8");
                        OutputStream os = sock.getOutputStream();
                        os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " 
                            + out.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                        os.write(out);
                        os.flush();
                        sock.close();
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t1) {
                System.err.println("[HTTP] Could not bind port " + HTTP_PORT + ": " + t1.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public void run() {
        connectCarService();
        int[] lastDoorState = {-1, -1, -1, -1};
        long lastConfigCheck = 0;

        System.out.println("[RELIEF] Monitoring 4 doors for automatic cabin pressure relief (crack=" + crackPercent + "%)...");

        while (true) {
            try {
                if (System.currentTimeMillis() - lastConfigCheck > 3000) {
                    loadConfig();
                    lastConfigCheck = System.currentTimeMillis();
                }

                // Check ventilation timer
                if (ventilationMode && ventilationStartTime > 0) {
                    long elapsedMs = System.currentTimeMillis() - ventilationStartTime;
                    long limitMs = (long) ventilationDurationMin * 60 * 1000L;
                    if (elapsedMs >= limitMs) {
                        System.out.println("[VENTILATION] Timer expired! Closing windows...");
                        stopVentilation();
                    }
                }

                // 4 Doors Monitoring for automatic pressure relief
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
                        int moreThanCrack = targetCrack + 4; // allow tolerance up to ~11%

                        if (opened) {
                            System.out.println("[EVENT] " + NAMES[i] + " door OPENED (window currently " + currentWinPos + "%, relief enabled=" + enabled + ")");
                            if (enabled && !ventilationMode) {
                                if (currentWinPos >= 0 && currentWinPos < targetCrack) {
                                    System.out.println(" -> [RELIEF] Cracking " + NAMES[i] + " window to " + targetCrack + "%");
                                    writeWindow(winWrite, targetCrack);
                                } else {
                                    System.out.println(" -> [RELIEF] Window already at " + currentWinPos + "% (skipping crack)");
                                }
                            }
                        } else {
                            System.out.println("[EVENT] " + NAMES[i] + " door CLOSED (window currently " + currentWinPos + "%, relief enabled=" + enabled + ")");
                            if (enabled && !ventilationMode) {
                                if (currentWinPos >= 0 && currentWinPos <= moreThanCrack) {
                                    System.out.println(" -> [RELIEF] Closing " + NAMES[i] + " window back to 0%");
                                    writeWindow(winWrite, 0);
                                } else {
                                    System.out.println(" -> [RELIEF] Window at " + currentWinPos + "% > " + moreThanCrack + "% (user opened, leaving it)");
                                }
                            }
                        }
                        lastDoorState[i] = current;
                    } else if (previous == -1) {
                        lastDoorState[i] = current;
                    }
                }

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
