package com.emas.telemetry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * Telemetry-only daemon for Proton e.MAS 5 (IHU801P).
 *
 * - READ-ONLY VHAL: exactly 3 battery properties (SOC 0x287001AF, Volts 0x28700315,
 *   Amps 0x287001B0) + optional standard speed (291504647). No setProperty anywhere.
 * - Local HTTP server on 127.0.0.1:8888: GET /api/energy (JSON), GET /api/status.
 *   POST and any non-whitelisted path -> 405 Method Not Allowed.
 * - ABRP: outbound-only POST to api.iternio.com every >=5 s while the vehicle is on;
 *   failures are silent, queue-free, and never block the loop.
 * - Offline resiliency: no network dependency for the local server or VHAL polling;
 *   threads never crash the daemon (all Throwable guarded).
 */
public final class TelemetryDaemon {

    // ---- VHAL property ids -------------------------------------------------
    static final int PROP_SOC = 0x287001AF;       // 678429103 BYTES, uint16/10 = %
    static final int PROP_VOLTAGE = 0x28700315;   // 678429461 BYTES, uint16/10 = V
    static final int PROP_CURRENT = 0x287001B0;   // 678429104 BYTES, (raw-5000)/10 = A
    static final int PROP_SPEED = 291504647;      // standard float km/h
    static final int PROP_SPEED_MCU = 678428840;  // 0x287000A8 ECARX CAN Speed in 0.01 km/h
    static final int AREA_ZERO = 0;

    // Doors & Bonnets (1 = closed, 0 = open for doors; hood: 1 = open, 0 = closed)
    static final int PROP_DOOR_FL = 678428815;
    static final int PROP_DOOR_FR = 678428816;
    static final int PROP_DOOR_RL = 678428817;
    static final int PROP_DOOR_RR = 678428818;
    static final int PROP_DOOR_TRUNK = 678428819;
    static final int PROP_DOOR_HOOD = 678428820; // 0x28700094: Frunk / Front Hood sensor

    // Actuators
    static final int PROP_TRUNK_ACTION_REQ = 678428745; // 0x28700049: 1=open, 2=close, 3=stop
    static final int PROP_MMI_UNLOCK_HOOD  = 678429406; // 0x287002DE: 1=unlock/release front hood

    // TPMS Pressures & Temps
    static final int PROP_TPMS_P_FL = 678428853;
    static final int PROP_TPMS_P_FR = 678428854;
    static final int PROP_TPMS_P_RL = 678428855;
    static final int PROP_TPMS_P_RR = 678428856;
    static final int PROP_TPMS_T_FL = 678428857;
    static final int PROP_TPMS_T_FR = 678428858;
    static final int PROP_TPMS_T_RL = 678428859;
    static final int PROP_TPMS_T_RR = 678428860;

    // Gear, Temp, Range & Trip
    static final int PROP_VEHICLE_GEAR = 289408001; // Android Automotive standard (4=P, 8=D, 2=R, 1=N)
    static final int PROP_GEAR_MCU = 678428908;
    static final int PROP_OUTSIDE_TEMP = 291505923;
    static final int PROP_DTE_RANGE = 678429378;
    static final int PROP_TRIP_DIST = 678429315;
    static final int PROP_TRIP_DUR = 678429290;
    static final int PROP_TRIP_ENERGY = 678429417;

    // Seat Belt / Occupancy
    static final int PROP_DRV_SEAT_BELT = 678429211;  // 0x2870021B
    static final int PROP_PASS_SEAT_BELT = 678429212; // 0x2870021C

    // ---- ABRP --------------------------------------------------------------
    static volatile String abrpApiKey = "";
    static volatile String abrpToken = "";
    private static volatile boolean abrpInFlight = false;

    // ---- Runtime state -----------------------------------------------------
    static volatile boolean running = true;
    static final Object LOCK = new Object();
    static double soc = Double.NaN, volts = Double.NaN, amps = Double.NaN, speed = Double.NaN;
    static volatile boolean doorFl = false, doorFr = false, doorRl = false, doorRr = false, doorTrunk = false, doorFrunk = false;
    static volatile double tpmsP_fl = 2.5, tpmsP_fr = 2.5, tpmsP_rl = 2.4, tpmsP_rr = 2.4;
    static volatile int tpmsT_fl = 31, tpmsT_fr = 32, tpmsT_rl = 30, tpmsT_rr = 31;
    static volatile String gear = "P";
    static volatile double outsideTemp = 25.0;
    static volatile int dteRange = 340;
    static volatile double tripDistKm = 0.0;
    static volatile int tripDurMin = 0;
    static volatile double tripEnergyKwh = 0.0;
    static volatile int drvBelt = -1;
    static volatile int passBelt = -1;
    static long lastGoodReadMs = 0;
    static long lastAbrpAttemptMs = 0;
    static final long ABRP_INTERVAL_MS = 5000;

    static final Charset UTF8 = StandardCharsets.UTF_8;

    public static void main(String[] args) throws Exception {
        System.out.println("[TelemetryDaemon] starting, pid=" + android.os.Process.myPid());
        loadAbrpConfig();

        if (!connectCarService()) {
            System.err.println("[TelemetryDaemon] car_service unavailable; retrying in loop");
        }

        Thread vhal = new Thread(() -> {
            while (running) {
                try { pollVhal(); }
                catch (Throwable t) { System.err.println("[TelemetryDaemon] poll error: " + t); }
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                if (!abrpApiKey.isEmpty() && !abrpToken.isEmpty() && isVehicleOn() && System.currentTimeMillis() - lastAbrpAttemptMs >= ABRP_INTERVAL_MS) {
                    double s, v, a, sp;
                    synchronized (LOCK) { s = soc; v = volts; a = amps; sp = speed; }
                    sendAbrp(s, v, a, sp);
                }
            }
        }, "vhal-poll");
        vhal.setDaemon(true);
        vhal.start();

        startHttpServer();

        while (running) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
        System.out.println("[TelemetryDaemon] stopped");
    }

    // ------------------------------------------------------------------ VHAL
    static Object propService = null;
    static Method getPropMethod = null;
    static Method setPropMethod = null;
    static java.lang.reflect.Constructor<?> cpvCtor = null;

    static boolean connectCarService() {
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
                if ("getProperty".equals(m.getName()) && m.getParameterTypes().length == 2) getPropMethod = m;
                if ("setProperty".equals(m.getName()) && m.getParameterTypes().length == 1) setPropMethod = m;
            }
            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            for (java.lang.reflect.Constructor<?> c : cpvClass.getConstructors()) {
                Class<?>[] pTypes = c.getParameterTypes();
                if (pTypes.length == 3 && pTypes[0] == int.class && pTypes[1] == int.class) {
                    cpvCtor = c;
                    break;
                }
            }
            return getPropMethod != null;
        } catch (Throwable t) {
            System.err.println("[TelemetryDaemon] connectCarService: " + t);
            return false;
        }
    }

    static boolean writePropBytes(int propId, int area, byte[] val) {
        if (propService == null || setPropMethod == null || cpvCtor == null) {
            connectCarService();
            if (propService == null || setPropMethod == null || cpvCtor == null) return false;
        }
        try {
            Object cpv = cpvCtor.newInstance(propId, area, val);
            setPropMethod.invoke(propService, cpv);
            return true;
        } catch (Throwable t) {
            System.err.println("[TelemetryDaemon] writePropBytes error (prop=" + propId + "): " + t);
            return false;
        }
    }

    static byte[] readBytesProp(int propId, int area) {
        if (propService == null || getPropMethod == null) return null;
        try {
            Object cpv = getPropMethod.invoke(propService, propId, area);
            if (cpv == null) return null;
            Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
            if (val instanceof byte[]) return (byte[]) val;
        } catch (Throwable ignored) {}
        return null;
    }

    static Float readFloatProp(int propId, int area) {
        if (propService == null || getPropMethod == null) return null;
        try {
            Object cpv = getPropMethod.invoke(propService, propId, area);
            if (cpv == null) return null;
            Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
            if (val instanceof Float) return (Float) val;
            if (val instanceof Double) return (Double) val != null ? (float) (double) (Double) val : null;
            if (val instanceof Integer) return (float) (Integer) val;
        } catch (Throwable ignored) {}
        return null;
    }

    static Integer readIntProp(int propId, int area) {
        if (propService == null || getPropMethod == null) return null;
        try {
            Object cpv = getPropMethod.invoke(propService, propId, area);
            if (cpv == null) return null;
            Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
            if (val instanceof Integer) return (Integer) val;
            if (val instanceof Byte) return (int) (Byte) val;
            if (val instanceof Short) return (int) (Short) val;
        } catch (Throwable ignored) {}
        return null;
    }

    static int le16(byte[] b) {
        if (b == null || b.length < 2) return -1;
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8);
    }

    /** Decode SOC 0..1000 -> 0..100.0 %, or NaN. */
    static double decodeSoc(byte[] b) {
        int raw = le16(b);
        return (raw >= 0 && raw <= 1000) ? raw / 10.0 : Double.NaN;
    }

    /** Decode voltage 1000 < raw < 10000 -> 100..1000 V, or NaN. */
    static double decodeVolts(byte[] b) {
        int raw = le16(b);
        return (raw > 1000 && raw < 10000) ? raw / 10.0 : Double.NaN;
    }

    /** Decode current (raw-5000)/10 -> -500..+500 A, or NaN. */
    static double decodeAmps(byte[] b) {
        int raw = le16(b);
        return (raw > 0 && raw < 10000) ? (raw - 5000) / 10.0 : Double.NaN;
    }

    static void pollVhal() {
        if (propService == null || getPropMethod == null) {
            // car_service may not be ready at boot time; retry until it comes up.
            connectCarService();
            if (propService == null || getPropMethod == null) return;
            System.out.println("[TelemetryDaemon] car_service connected (delayed)");
        }
        byte[] bS = readBytesProp(PROP_SOC, AREA_ZERO);
        byte[] bV = readBytesProp(PROP_VOLTAGE, AREA_ZERO);
        byte[] bA = readBytesProp(PROP_CURRENT, AREA_ZERO);
        Integer mcuSp = readIntProp(PROP_SPEED_MCU, AREA_ZERO);
        double curSpeed = Double.NaN;
        if (mcuSp != null && mcuSp.intValue() > 0) {
            curSpeed = mcuSp.intValue() * 0.01;
        } else {
            Float sp = readFloatProp(PROP_SPEED, AREA_ZERO);
            if (sp != null) curSpeed = sp.doubleValue();
        }
        double s = decodeSoc(bS), v = decodeVolts(bV), a = decodeAmps(bA);
        synchronized (LOCK) {
            if (!Double.isNaN(s)) soc = s;
            if (!Double.isNaN(v)) volts = v;
            if (!Double.isNaN(a)) amps = a;
            speed = curSpeed;
            if (!Double.isNaN(s) || !Double.isNaN(v) || !Double.isNaN(a)) lastGoodReadMs = System.currentTimeMillis();
        }

        // Doors (1=closed, 0=open for doors; 1=open, 0=closed for trunk/hood)
        byte[] bDFl = readBytesProp(PROP_DOOR_FL, AREA_ZERO);
        byte[] bDFr = readBytesProp(PROP_DOOR_FR, AREA_ZERO);
        byte[] bDRl = readBytesProp(PROP_DOOR_RL, AREA_ZERO);
        byte[] bDRr = readBytesProp(PROP_DOOR_RR, AREA_ZERO);
        byte[] bDTr = readBytesProp(PROP_DOOR_TRUNK, AREA_ZERO);
        byte[] bDHd = readBytesProp(PROP_DOOR_HOOD, AREA_ZERO);
        if (bDFl != null && bDFl.length > 0) doorFl = (bDFl[0] & 0xFF) == 0;
        if (bDFr != null && bDFr.length > 0) doorFr = (bDFr[0] & 0xFF) == 0;
        if (bDRl != null && bDRl.length > 0) doorRl = (bDRl[0] & 0xFF) == 0;
        if (bDRr != null && bDRr.length > 0) doorRr = (bDRr[0] & 0xFF) == 0;
        if (bDTr != null && bDTr.length > 0) doorTrunk = (bDTr[0] & 0xFF) != 0;
        if (bDHd != null && bDHd.length > 0) doorFrunk = (bDHd[0] & 0xFF) != 0;

        // Gear: Geely MCU TCU signal takes precedence (0x287000EC = 678428908)
        // 2 = D (101), 3 = N (0), 4 = R (-1), 5 = P (102)
        Integer mcuG = readIntProp(PROP_GEAR_MCU, AREA_ZERO);
        if (mcuG != null && mcuG.intValue() > 0) {
            int g = mcuG.intValue();
            if (g == 2) gear = "D";
            else if (g == 4) gear = "R";
            else if (g == 3) gear = "N";
            else if (g == 5) gear = "P";
            else if (g == 1) gear = "N";
        } else {
            // Fallback: standard AOSP Vehicle HAL property
            Integer iGear = readIntProp(PROP_VEHICLE_GEAR, AREA_ZERO);
            if (iGear != null) {
                int g = iGear.intValue();
                if (g == 8) gear = "D";
                else if (g == 2) gear = "R";
                else if (g == 1) gear = "N";
                else if (g == 4) gear = "P";
            }
        }
        // Extra safeguard: if vehicle is moving, cannot be in P
        if (!Double.isNaN(curSpeed) && curSpeed > 1.0 && "P".equals(gear)) {
            gear = (mcuG != null && mcuG.intValue() == 4) ? "R" : "D";
        }

        // Ambient Temp
        Float ot = readFloatProp(PROP_OUTSIDE_TEMP, AREA_ZERO);
        if (ot != null) outsideTemp = ot.doubleValue();

        // DTE Range
        byte[] bDte = readBytesProp(PROP_DTE_RANGE, AREA_ZERO);
        if (bDte != null && bDte.length >= 2) {
            int d = le16(bDte);
            if (d > 0 && d < 1000) dteRange = d;
        }

        // Trip 1
        byte[] bTDist = readBytesProp(PROP_TRIP_DIST, AREA_ZERO);
        if (bTDist != null && bTDist.length >= 2) {
            int td = le16(bTDist);
            if (td >= 0) tripDistKm = td / 10.0;
        }
        byte[] bTDur = readBytesProp(PROP_TRIP_DUR, AREA_ZERO);
        if (bTDur != null && bTDur.length >= 2) {
            int dur = le16(bTDur);
            if (dur >= 0) tripDurMin = dur;
        }
        byte[] bTEng = readBytesProp(PROP_TRIP_ENERGY, AREA_ZERO);
        if (bTEng != null && bTEng.length >= 2) {
            int eng = le16(bTEng);
            if (eng >= 0) tripEnergyKwh = eng / 10.0;
        }

        // TPMS
        byte[] bPFl = readBytesProp(PROP_TPMS_P_FL, AREA_ZERO);
        byte[] bPFr = readBytesProp(PROP_TPMS_P_FR, AREA_ZERO);
        byte[] bPRl = readBytesProp(PROP_TPMS_P_RL, AREA_ZERO);
        byte[] bPRr = readBytesProp(PROP_TPMS_P_RR, AREA_ZERO);
        if (bPFl != null && bPFl.length > 0 && (bPFl[0] & 0xFF) > 0) tpmsP_fl = ((bPFl[0] & 0xFF) * 4) / 100.0;
        if (bPFr != null && bPFr.length > 0 && (bPFr[0] & 0xFF) > 0) tpmsP_fr = ((bPFr[0] & 0xFF) * 4) / 100.0;
        if (bPRl != null && bPRl.length > 0 && (bPRl[0] & 0xFF) > 0) tpmsP_rl = ((bPRl[0] & 0xFF) * 4) / 100.0;
        if (bPRr != null && bPRr.length > 0 && (bPRr[0] & 0xFF) > 0) tpmsP_rr = ((bPRr[0] & 0xFF) * 4) / 100.0;

        byte[] bTFl = readBytesProp(PROP_TPMS_T_FL, AREA_ZERO);
        byte[] bTFr = readBytesProp(PROP_TPMS_T_FR, AREA_ZERO);
        byte[] bTRl = readBytesProp(PROP_TPMS_T_RL, AREA_ZERO);
        byte[] bTRr = readBytesProp(PROP_TPMS_T_RR, AREA_ZERO);
        if (bTFl != null && bTFl.length > 0 && (bTFl[0] & 0xFF) > 0) tpmsT_fl = (bTFl[0] & 0xFF) - 40;
        if (bTFr != null && bTFr.length > 0 && (bTFr[0] & 0xFF) > 0) tpmsT_fr = (bTFr[0] & 0xFF) - 40;
        if (bTRl != null && bTRl.length > 0 && (bTRl[0] & 0xFF) > 0) tpmsT_rl = (bTRl[0] & 0xFF) - 40;
        if (bTRr != null && bTRr.length > 0 && (bTRr[0] & 0xFF) > 0) tpmsT_rr = (bTRr[0] & 0xFF) - 40;

        // Seat Belts / Occupancy (0=buckled/ok, 1=unbuckled warning)
        byte[] bDb = readBytesProp(PROP_DRV_SEAT_BELT, AREA_ZERO);
        if (bDb != null && bDb.length > 0) drvBelt = (bDb[0] & 0xFF);
        byte[] bPb = readBytesProp(PROP_PASS_SEAT_BELT, AREA_ZERO);
        if (bPb != null && bPb.length > 0) passBelt = (bPb[0] & 0xFF);
    }

    static boolean isVehicleOn() {
        synchronized (LOCK) {
            return !Double.isNaN(speed) && speed > 0.5
                || (System.currentTimeMillis() - lastGoodReadMs) < 30000;
        }
    }

    // ------------------------------------------------------------------ HTTP
    static void startHttpServer() throws Exception {
        ServerSocket server = new ServerSocket(8888, 50, java.net.InetAddress.getByName("127.0.0.1"));
        System.out.println("[TelemetryDaemon] HTTP listening on 127.0.0.1:8888");
        Thread acc = new Thread(() -> {
            while (running) {
                try {
                    final Socket sock = server.accept();
                    Thread t = new Thread(() -> handle(sock), "http-" + sock.getPort());
                    t.setDaemon(true);
                    t.start();
                } catch (Throwable t) {
                    if (running) System.err.println("[TelemetryDaemon] accept: " + t);
                    try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                }
            }
        }, "http-accept");
        acc.setDaemon(true);
        acc.start();
    }

    static void handle(Socket sock) {
        try {
            sock.setSoTimeout(2000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), UTF8));
            String requestLine = in.readLine();
            int clen = 0;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try { clen = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                }
            }
            if (clen > 0 && clen < 65536) { // drain any body, never parse it (same reader: it may be buffered)
                char[] sink = new char[clen];
                int got = 0;
                while (got < clen) {
                    int r = in.read(sink, got, clen - got);
                    if (r < 0) break;
                    got += r;
                }
            }

            String method = "GET", path = "/";
            if (requestLine != null) {
                String[] parts = requestLine.trim().split("\\s+");
                if (parts.length >= 2) { method = parts[0].toUpperCase(); path = parts[1]; }
            }
            String pathOnly = path.indexOf('?') >= 0 ? path.substring(0, path.indexOf('?')) : path;

            int code; String body;
            if ("GET".equals(method) && "/api/energy".equals(pathOnly)) {
                body = energyJson(); code = 200;
            } else if ("GET".equals(method) && "/api/status".equals(pathOnly)) {
                body = "{\"ok\":true,\"daemon\":\"telemetry_only\"}"; code = 200;
            } else if ("/api/frunk/open".equals(pathOnly) || "/api/frunk/unlock".equals(pathOnly)) {
                boolean ok = writePropBytes(PROP_MMI_UNLOCK_HOOD, AREA_ZERO, new byte[]{1});
                body = "{\"ok\":" + ok + ",\"action\":\"open_frunk\"}";
                code = ok ? 200 : 500;
            } else if ("/api/trunk/open".equals(pathOnly)) {
                boolean ok = writePropBytes(PROP_TRUNK_ACTION_REQ, AREA_ZERO, new byte[]{1});
                body = "{\"ok\":" + ok + ",\"action\":\"open_trunk\"}";
                code = ok ? 200 : 500;
            } else if ("/api/trunk/close".equals(pathOnly)) {
                boolean ok = writePropBytes(PROP_TRUNK_ACTION_REQ, AREA_ZERO, new byte[]{2});
                body = "{\"ok\":" + ok + ",\"action\":\"close_trunk\"}";
                code = ok ? 200 : 500;
            } else if ("/api/trunk/toggle".equals(pathOnly)) {
                byte act = doorTrunk ? (byte)2 : (byte)1;
                boolean ok = writePropBytes(PROP_TRUNK_ACTION_REQ, AREA_ZERO, new byte[]{act});
                body = "{\"ok\":" + ok + ",\"action\":\"toggle_trunk\",\"actuated\":" + (act == 1 ? "\"open\"" : "\"close\"") + "}";
                code = ok ? 200 : 500;
            } else {
                code = 405;
                body = "{\"ok\":false,\"error\":\"METHOD_NOT_ALLOWED\",\"allowed\":[\"GET /api/energy\",\"GET /api/status\"]}";
            }
            byte[] out = body.getBytes(UTF8);
            OutputStream os = sock.getOutputStream();
            os.write(("HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Method Not Allowed")
                + "\r\nContent-Type: application/json\r\nContent-Length: " + out.length
                + "\r\nConnection: close\r\n\r\n").getBytes(UTF8));
            os.write(out);
            os.flush();
        } catch (Throwable t) {
            System.err.println("[TelemetryDaemon] http handle error: " + t);
            t.printStackTrace();
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    static String energyJson() {
        double s, v, a, sp;
        long age;
        synchronized (LOCK) {
            s = soc; v = volts; a = amps; sp = speed;
            age = lastGoodReadMs == 0 ? -1 : System.currentTimeMillis() - lastGoodReadMs;
        }
        StringBuilder b = new StringBuilder(160);
        boolean any = false;
        if (!Double.isNaN(s)) { b.append("\"soc_pct\":").append(f1(s)); any = true; }
        if (!Double.isNaN(v)) { if (any) b.append(','); b.append("\"voltage_v\":").append(f1(v)); any = true; }
        if (!Double.isNaN(a)) { if (any) b.append(','); b.append("\"current_a\":").append(f1(a)); any = true; }
        double pkw = (!Double.isNaN(v) && !Double.isNaN(a)) ? (v * a / 1000.0) : Double.NaN;
        if (!Double.isNaN(pkw)) { if (any) b.append(','); b.append("\"power_kw\":").append(f2(pkw)); }
        StringBuilder r = new StringBuilder(512);
        r.append("{\"ok\":").append(any)
         .append(",\"source\":\"telemetry_daemon\",\"battery\":{").append(b)
         .append(",\"range_km\":").append(dteRange).append('}');
        if (!Double.isNaN(pkw)) r.append(",\"motor_power_kw\":").append(f1(pkw));
        if (!Double.isNaN(sp)) r.append(",\"speed_kmh\":").append(f1(sp));
        r.append(",\"gear\":\"").append(gear).append("\"");
        r.append(",\"ambient_temp_c\":").append(f1(outsideTemp));
        r.append(",\"doors\":{\"fl\":").append(doorFl).append(",\"fr\":").append(doorFr)
         .append(",\"rl\":").append(doorRl).append(",\"rr\":").append(doorRr)
         .append(",\"trunk\":").append(doorTrunk).append(",\"frunk\":").append(doorFrunk).append("}");
        r.append(",\"seatbelt\":{\"driver\":").append(drvBelt)
         .append(",\"passenger\":").append(passBelt).append("}");
        r.append(",\"tpms\":{")
         .append("\"fl\":{\"p\":").append(f1(tpmsP_fl)).append(",\"t\":").append(tpmsT_fl).append("},")
         .append("\"fr\":{\"p\":").append(f1(tpmsP_fr)).append(",\"t\":").append(tpmsT_fr).append("},")
         .append("\"rl\":{\"p\":").append(f1(tpmsP_rl)).append(",\"t\":").append(tpmsT_rl).append("},")
         .append("\"rr\":{\"p\":").append(f1(tpmsP_rr)).append(",\"t\":").append(tpmsT_rr).append("}}");
        r.append(",\"trip\":{")
         .append("\"distance_km\":").append(f1(tripDistKm))
         .append(",\"duration_min\":").append(tripDurMin)
         .append(",\"avg_speed_kmh\":").append(tripDurMin > 0 ? (int)(tripDistKm / (tripDurMin / 60.0)) : 0)
         .append(",\"consumption_kwh_100km\":").append(tripDistKm > 0 ? f1(tripEnergyKwh / tripDistKm * 100.0) : "0.0")
         .append(",\"energy_kwh\":").append(f1(tripEnergyKwh))
         .append(",\"distance2_km\":0.0}");
        r.append(",\"age_ms\":").append(age)
         .append(",\"observed_at_ms\":").append(System.currentTimeMillis())
         .append('}');
        return r.toString();
    }

    // ------------------------------------------------------------------ ABRP
    static void loadAbrpConfig() {
        try {
            File f = new File("/data/local/window/telemetry_config.json");
            if (!f.exists()) return;
            String json = readFileToString(f);
            String k = extractJsonString(json, "abrp_api_key");
            if (k == null || k.isEmpty()) k = extractJsonString(json, "api_key");
            if (k != null && !k.isEmpty()) abrpApiKey = k;
            String t = extractJsonString(json, "abrp_token");
            if (t == null || t.isEmpty()) t = extractJsonString(json, "token");
            if (t != null && !t.isEmpty()) abrpToken = t;
        } catch (Throwable ignored) {}
    }

    static void sendAbrp(double s, double v, double a, double sp) {
        if (abrpApiKey.isEmpty() || abrpToken.isEmpty()) return;
        if (abrpInFlight) return;
        abrpInFlight = true;
        lastAbrpAttemptMs = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                if (Double.isNaN(s) || Double.isNaN(v) || Double.isNaN(a)) return;
                float powerKw = (float) (v * a / 1000.0);
                long utc = System.currentTimeMillis() / 1000L;
                String tlmJson = "{"
                    + "\"utc\":" + utc + ","
                    + "\"soc\":" + f1(s) + ","
                    + "\"power\":" + f2(powerKw) + ","
                    + "\"speed\":" + f1(Double.isNaN(sp) ? 0.0 : sp) + ","
                    + "\"voltage\":" + f1(v) + ","
                    + "\"current\":" + f1(a) + ","
                    + "\"is_charging\":" + (a < -1.0)
                    + "}";
                String urlStr = "https://api.iternio.com/1/tlm/send?api_key=" + URLEncoder.encode(abrpApiKey, "UTF-8")
                    + "&token=" + URLEncoder.encode(abrpToken, "UTF-8")
                    + "&tlm=" + URLEncoder.encode(tlmJson, "UTF-8");
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setFixedLengthStreamingMode(0);
                conn.setDoOutput(true);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Throwable ignored) {
            } finally {
                abrpInFlight = false;
            }
        }, "abrp-post");
        t.setDaemon(true);
        t.start();
    }

    static String f1(double d) { return String.format(java.util.Locale.US, "%.1f", d); }
    static String f2(double d) { return String.format(java.util.Locale.US, "%.2f", d); }

    // ------------------------------------------------------------------ util
    static String readFileToString(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), UTF8);
        } finally { try { fis.close(); } catch (Throwable ignored) {} }
    }

    static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int c = json.indexOf(":", k + needle.length());
        if (c < 0) return null;
        int q1 = json.indexOf("\"", c);
        if (q1 < 0) return null;
        int q2 = json.indexOf("\"", q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
