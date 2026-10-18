package com.emas.probe;

import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Ujian auto-lock-menjauh e.MAS 5 — MOD A (auto-revert).
 *
 * Aliran:
 *  1. Baca & log nilai AWAY_LOCK (0x28700036) dan KEY_STATE (0x287000E1) asal
 *  2. Tulis AWAY_LOCK = 01
 *  3. Pantau LOCK_FL/LOCK_FR + KEY_STATE selama 90 saat (setiap 2s), log setiap perubahan
 *  4. REVERT AWAY_LOCK ke nilai asal (00)
 *  5. Log ringkasan ke /data/local/window/awaylock_test.log
 *
 * Berjalan sepenuhnya di IHU — tiada ADB diperlukan semasa ujian.
 * Jangan jalankan kalau kunci berada DALAM kereta.
 */
public class AwayLockTest {

    static final int PROP_AWAY_LOCK = 678428726;      // 0x28700036 INFO_ID_VEHIC_AUTO_LOCK_WHEN_LEAVE_DOOR
    static final int PROP_KEY_STATE = 678428897;     // 0x287000E1 INFO_ID_VPOWERINFO_KEY_STATE
    static final int PROP_LOCK_FL = 678428821;       // 0x28700095 pintu kunci FL
    static final int PROP_LOCK_FR = 678428822;       // 0x28700096 pintu kunci FR
    static final int PROP_UNLOCK_NEAR = 678428725;   // 0x28700035 AUTO_UNLOCK_WHEN_NEAR_DOOR (rujukan)

    static final String LOG_PATH = "/data/local/window/awaylock_test.log";
    static final int MONITOR_SECONDS = 90;
    static final int POLL_MS = 2000;

    static Method sGetter;
    static Method sSetter;
    static Object sService;
    static FileWriter sLog;

    public static void main(String[] args) {
        try {
            p("=== AWAY-LOCK TEST MOD A (auto-revert) " + new java.util.Date() + " ===");
            bootstrap();

            // 1. nilai asal
            int origAway = readInt(PROP_AWAY_LOCK, "AWAY_LOCK");
            int keyState = readInt(PROP_KEY_STATE, "KEY_STATE");
            p("ASAL: AWAY_LOCK=" + origAway + " KEY_STATE=0x" + Integer.toHexString(keyState));
            if (origAway != 0) {
                p("NOTA: AWAY_LOCK bukan 0 asal — simpan nilai ini untuk revert: " + origAway);
            }

            // 2. tulis ON
            boolean wrote = writeByte(PROP_AWAY_LOCK, (byte) 1, "AWAY_LOCK=1");
            if (!wrote) {
                p("KEPUTUSAN: WRITE_REJECTED — prop tak terima tulis. Tiada revert diperlukan.");
                done();
                return;
            }
            // sahkan baca balik
            int after = readInt(PROP_AWAY_LOCK, "AWAY_LOCK(after write)");
            p("SELEPAS TULIS: AWAY_LOCK=" + after);

            // 3. monitor
            p("MONITOR mula (" + MONITOR_SECONDS + "s). Kunci perlu dijauhkan SEKARANG.");
            long end = System.currentTimeMillis() + MONITOR_SECONDS * 1000L;
            int lastKey = -1, lastLockFL = -1, lastLockFR = -1;
            boolean triggered = false;
            while (System.currentTimeMillis() < end) {
                int k = readIntRaw(PROP_KEY_STATE);
                int fl = readIntRaw(PROP_LOCK_FL);
                int fr = readIntRaw(PROP_LOCK_FR);
                if (k != lastKey || fl != lastLockFL || fr != lastLockFR) {
                    p(String.format("PERUBAHAN t+%ds: KEY_STATE=0x%X LOCK_FL=%d LOCK_FR=%d",
                        (MONITOR_SECONDS * 1000L - (end - System.currentTimeMillis())) / 1000, k, fl, fr));
                    lastKey = k; lastLockFL = fl; lastLockFR = fr;
                    if (fl == 1 || fr == 1) triggered = true;
                }
                Thread.sleep(POLL_MS);
            }

            // 4. revert
            boolean reverted = writeByte(PROP_AWAY_LOCK, (byte) origAway, "AWAY_LOCK revert=" + origAway);
            int finalVal = readInt(PROP_AWAY_LOCK, "AWAY_LOCK(final)");
            p("REVERT: " + (reverted ? "ok" : "GAGAL") + " nilai akhir=" + finalVal);

            // 5. ringkasan
            if (triggered) {
                p("KEPUTUSAN: LOCKED_AFTER_WRITE — auto-lock-menjauh BERFUNGSI!");
            } else {
                p("KEPUTUSAN: REVERTED_NO_TRIGGER — tulis diterima tapi tiada lock dalam " + MONITOR_SECONDS + "s.");
                p("(Sensor jarak PEPS mungkin tak aktif, atau kunci tak dijauhkan cukup)");
            }
            done();
        } catch (Throwable t) {
            try { p("FATAL: " + t.getClass().getSimpleName() + ": " + t.getMessage()); } catch (Exception ignored) {}
            // cuba revert walau apa jadi
            try {
                if (sSetter != null && sService != null) {
                    Object cpv = sGetter.invoke(sService, PROP_AWAY_LOCK, 0);
                    Object orig = cpv.getClass().getMethod("getValue").invoke(cpv);
                    if (orig instanceof byte[] && ((byte[]) orig).length > 0 && ((byte[]) orig)[0] == 1) {
                        writeByte(PROP_AWAY_LOCK, (byte) 0, "revert kecemasan");
                        p("REVERT KECEMASAN dilakukan");
                    }
                }
            } catch (Throwable ignored) {}
            done();
            System.exit(2);
        }
    }

    static void done() {
        try { if (sLog != null) { sLog.flush(); sLog.close(); } } catch (Exception ignored) {}
    }

    static void p(String msg) {
        System.out.println(msg);
        try {
            if (sLog == null) sLog = new FileWriter(LOG_PATH, true);
            sLog.write(msg + "\n"); sLog.flush();
        } catch (Exception ignored) {}
    }

    // ===== VHAL plumbing (corak ReadOnlyTelemetry terbukti) =====

    static int readIntRaw(int propId) {
        try {
            Object cpv = sGetter.invoke(sService, propId, 0);
            if (cpv == null) return -1;
            Object v = cpv.getClass().getMethod("getValue").invoke(cpv);
            if (v instanceof byte[]) {
                byte[] b = (byte[]) v;
                return b.length > 0 ? (b[0] & 0xFF) : -1;
            }
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        return -1;
    }

    static int readInt(int propId, String label) {
        int v = readIntRaw(propId);
        p("  baca " + label + " (0x" + Integer.toHexString(propId) + ") = " + (v < 0 ? "NULL" : "0x" + Integer.toHexString(v)));
        return v;
    }

    static boolean writeByte(int propId, byte val, String label) {
        try {
            // CarPropertyValue(int propertyId, int areaId, int status, long timestamp, Object value)
            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            Constructor<?> ctor = cpvClass.getConstructor(int.class, int.class, int.class, long.class, Object.class);
            Object cpv = ctor.newInstance(propId, 0, 0, System.currentTimeMillis(), (Object) new byte[]{val});
            sSetter.invoke(sService, cpv);
            p("  tulis " + label + " ... OK (tiada exception)");
            return true;
        } catch (Throwable t) {
            Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
            p("  tulis " + label + " GAGAL: " + c.getClass().getSimpleName() + ": " + c.getMessage());
            return false;
        }
    }

    static void bootstrap() throws Exception {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object vmRuntime = getRuntime.invoke(null);
            Method setEx = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setEx.invoke(vmRuntime, new Object[]{new String[]{"L"}});
        } catch (Throwable ignored) {}

        Class<?> binderClass = Class.forName("android.os.IBinder");
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "car_service");
        if (binder == null) throw new IllegalStateException("car_service unavailable");
        Object car = Class.forName("android.car.ICar$Stub")
                .getMethod("asInterface", binderClass).invoke(null, binder);
        Object propertyBinder = car.getClass().getMethod("getCarService", String.class).invoke(car, "property");
        if (propertyBinder == null) throw new IllegalStateException("property binder unavailable");
        Object propertyService = Class.forName("android.car.hardware.property.ICarProperty$Stub")
                .getMethod("asInterface", binderClass).invoke(null, propertyBinder);
        if (propertyService == null) throw new IllegalStateException("ICarProperty unavailable");

        for (Method m : propertyService.getClass().getMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (m.getName().equals("getProperty") && ps.length == 2 && ps[0] == int.class) sGetter = m;
            if (m.getName().equals("setProperty") && ps.length == 1) sSetter = m;
        }
        if (sGetter == null) throw new IllegalStateException("getProperty tak jumpa");
        p("bootstrap OK (setter " + (sSetter != null ? "jumpa" : "TAK jumpa") + ")");
        sService = propertyService;
    }
}
