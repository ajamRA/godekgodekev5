package com.emas.window;

import com.emas.door.AndroidDoorClock;
import com.emas.door.DoorSnapshot;
import com.emas.door.DoorStateHub;
import com.emas.door.DoorVhalReader;
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
    public volatile boolean observeOnly = false;

    private Object propService;
    private Method getPropMethod;
    private Method setPropMethod;
    private Constructor<?> cpvCtor;
    private long lastConfigMtime = 0;
    private final DoorStateHub.Clock doorClock;
    private final DoorStateHub doorHub;
    private final DoorStateHub.Reader doorReader;
    private DoorHubHttpServer httpServer;

    public DoorPressureReliefDaemon() { this(new AndroidDoorClock(), null); }
    DoorPressureReliefDaemon(DoorStateHub.Clock clock, DoorStateHub.Reader reader) {
        doorClock = clock; doorHub = new DoorStateHub(clock);
        doorReader = reader == null ? new DoorVhalReader(clock) : reader;
    }

    public static void main(String[] args) throws IOException {
        System.out.println("=== EMAS DEDICATED DOOR PRESSURE RELIEF DAEMON v3.1 (CLEAN) ===");
        DoorPressureReliefDaemon daemon = new DoorPressureReliefDaemon();
        for (String arg : args) if ("--observe-only".equals(arg)) daemon.observeOnly = true;
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

    protected void connectCarService() {
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
        if (observeOnly) return false;
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

    public void startVentilation(int minutes, WindowSafetyManager safetyMgr) {
        if (observeOnly) return;
        ventilationDurationMin = Math.max(1, Math.min(180, minutes));
        ventilationStartTime = System.currentTimeMillis();
        ventilationMode = true;
        System.out.println("[VENTILATION] Started for " + ventilationDurationMin + " mins at " + crackPercent + "%");
        for (int i = 0; i < 4; i++) {
            int curPos = readIntProp(WIN_READ_PROPS[i]);
            if (curPos >= 0 && curPos <= WindowSafetyManager.MAX_CLOSED_POS) {
                if (writeWindow(WIN_WRITE_PROPS[i], crackPercent)) {
                    if (safetyMgr != null) safetyMgr.setVentilationOwnership(i, true);
                }
            } else {
                if (safetyMgr != null) safetyMgr.setVentilationOwnership(i, false);
            }
        }
    }

    public void stopVentilation(WindowSafetyManager safetyMgr) {
        ventilationMode = false;
        ventilationStartTime = 0;
        System.out.println("[VENTILATION] Stopped. Closing owned windows.");
        if (observeOnly) return;
        for (int i = 0; i < 4; i++) {
            if (safetyMgr == null || safetyMgr.isVentilationOwned(i)) {
                writeWindow(WIN_WRITE_PROPS[i], 0);
            }
            if (safetyMgr != null) safetyMgr.setVentilationOwnership(i, false);
        }
    }

    public void stopVentilation() {
        stopVentilation(null);
    }

    private void startHttpServer() throws IOException {
        // Bind before starting the sampler: a duplicate host fails rather than creating another poller.
        httpServer = new DoorHubHttpServer(HTTP_PORT, doorHub::snapshot, doorClock, this::handleWindowHttp);
        httpServer.start();
        System.out.println("[HTTP] DoorStateHub and window status on 127.0.0.1:" + HTTP_PORT);
    }

    DoorHubHttpServer.Response handleWindowHttp(LocalHttpRequest request) {
        WindowRequestPolicy.Action action = WindowRequestPolicy.classify(request.method, request.target);
        if (observeOnly && (action == WindowRequestPolicy.Action.CLOSE || action == WindowRequestPolicy.Action.CRACK))
            return new DoorHubHttpServer.Response(409, "{\"ok\":false,\"error\":\"OBSERVE_ONLY\"}");
        String body;
        boolean ok = true;
        if (action == WindowRequestPolicy.Action.CLOSE) {
            ok = writeAllWindows(0);
            body = "{\"ok\":" + ok + ",\"action\":\"close_windows\"}";
        } else if (action == WindowRequestPolicy.Action.CRACK) {
            ok = writeAllWindows(crackPercent);
            body = "{\"ok\":" + ok + ",\"action\":\"crack_windows\",\"percent\":" + crackPercent + "}";
        } else {
            body = "{\"ok\":true,\"daemon\":\"door_pressure_relief\",\"enabled\":" + enabled
                    + ",\"crack_percent\":" + crackPercent + ",\"ventilation_mode\":" + ventilationMode
                    + ",\"observe_only\":" + observeOnly + "}";
        }
        return new DoorHubHttpServer.Response(ok ? 200 : 500, body);
    }

    static boolean canUseDoorObservation(DoorSnapshot sampled, DoorSnapshot latest, int door, long elapsedMs) {
        return sampled.isFresh(elapsedMs) && latest.isFresh(elapsedMs)
                && sampled.generation.equals(latest.generation) && sampled.epoch(door) == latest.epoch(door)
                && sampled.state(door) != DoorSnapshot.State.UNKNOWN && sampled.state(door) == latest.state(door);
    }

    public void run() {
        doorHub.start(doorReader);
        connectCarService();
        WindowSafetyManager safetyMgr = new WindowSafetyManager();
        DoorState[] lastDoorState = {DoorState.UNKNOWN, DoorState.UNKNOWN, DoorState.UNKNOWN, DoorState.UNKNOWN};
        long lastConfigCheck = 0;
        long lastSequence = -1;
        long[] lastDoorEpochs = {-1, -1, -1, -1};

        System.out.println("[RELIEF] Monitoring 4 doors for automatic cabin pressure relief (crack=" + crackPercent + "%)...");

        while (true) {
            try {
                DoorSnapshot snapshot = doorHub.awaitNext(lastSequence, DoorStateHub.POLL_MS);
                lastSequence = snapshot.sequence;
                if (System.currentTimeMillis() - lastConfigCheck > 3000) {
                    loadConfig();
                    lastConfigCheck = System.currentTimeMillis();
                }

                // Check ventilation timer
                if (!observeOnly && ventilationMode && ventilationStartTime > 0) {
                    long elapsedMs = System.currentTimeMillis() - ventilationStartTime;
                    long limitMs = (long) ventilationDurationMin * 60 * 1000L;
                    if (elapsedMs >= limitMs) {
                        System.out.println("[VENTILATION] Timer expired! Closing owned windows...");
                        stopVentilation(safetyMgr);
                    }
                }

                // This loop consumes the same immutable snapshot as HTTP, never rereads the doors.
                for (int i = 0; i < 4; i++) {
                    if (lastDoorEpochs[i] != snapshot.epoch(i)) {
                        // UNKNOWN may have occurred between two consumer wakeups. Do not bridge that gap.
                        safetyMgr.invalidateSession(i); lastDoorState[i] = DoorState.UNKNOWN;
                        lastDoorEpochs[i] = snapshot.epoch(i);
                    }
                    DoorState current = snapshot.isFresh(doorClock.elapsedMillis()) ? DoorState.valueOf(snapshot.state(i).name()) : DoorState.UNKNOWN;
                    if (current == DoorState.UNKNOWN) {
                        // Connection lost or sensor error: cancel active safety session to avoid unpredictable auto-closes
                        safetyMgr.invalidateSession(i);
                        lastDoorState[i] = DoorState.UNKNOWN;
                        continue;
                    }

                    DoorState previous = lastDoorState[i];
                    if (previous != DoorState.UNKNOWN && current != previous) {
                        if (observeOnly) {
                            System.out.println("[OBSERVE] " + NAMES[i] + " door=" + current);
                            lastDoorState[i] = current; continue;
                        }
                        int winRead = WIN_READ_PROPS[i];
                        int winWrite = WIN_WRITE_PROPS[i];
                        int currentWinPos = readIntProp(winRead);
                        int targetCrack = crackPercent;
                        // A slow previous window or position read must not authorize a stale door event.
                        if (!canUseDoorObservation(snapshot, doorHub.snapshot(), i, doorClock.elapsedMillis())) {
                            safetyMgr.invalidateSession(i); lastDoorState[i] = DoorState.UNKNOWN; continue;
                        }

                        if (current == DoorState.OPEN) {
                            System.out.println("[EVENT] " + NAMES[i] + " door OPENED (window currently " + currentWinPos + "%, relief enabled=" + enabled + ")");
                            if (enabled && !ventilationMode && !observeOnly) {
                                WindowSafetyManager.WindowSession session = safetyMgr.tryOpenRelief(i, currentWinPos, targetCrack, System.currentTimeMillis());
                                if (session != null) {
                                    System.out.println(" -> [RELIEF] Cracking " + NAMES[i] + " window to " + targetCrack + "% (session " + session.sessionId + ")");
                                    writeWindow(winWrite, targetCrack);
                                } else {
                                    System.out.println(" -> [RELIEF] Window at " + currentWinPos + "% (not closed originally or manual; skipping relief)");
                                }
                            }
                        } else if (current == DoorState.CLOSED) {
                            System.out.println("[EVENT] " + NAMES[i] + " door CLOSED (window currently " + currentWinPos + "%, relief enabled=" + enabled + ")");
                            if (enabled && !ventilationMode && !observeOnly) {
                                if (safetyMgr.canAutoCloseRelief(i, currentWinPos)) {
                                    System.out.println(" -> [RELIEF] Verified session valid. Closing " + NAMES[i] + " window back to 0%");
                                    writeWindow(winWrite, 0);
                                    safetyMgr.invalidateSession(i);
                                } else {
                                    System.out.println(" -> [RELIEF] Window at " + currentWinPos + "% not owned by active session (user moved or opened manual; leaving intact)");
                                }
                            }
                        }
                        lastDoorState[i] = current;
                    } else if (previous == DoorState.UNKNOWN) {
                        // Establish clean baseline silently without triggering transition
                        lastDoorState[i] = current;
                    }
                }

            } catch (InterruptedException ie) {
                System.out.println("Daemon interrupted. Exiting.");
                doorHub.close();
                if (httpServer != null) try { httpServer.close(); } catch (IOException ignored) {}
                break;
            } catch (Throwable t) {
                System.err.println("Daemon loop error: " + t.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                connectCarService();
            }
        }
    }
}
