package com.emas.probe;

import java.lang.reflect.Method;

/**
 * Read-only VHAL property prober untuk IHU801P (Proton e.MAS 5).
 * Query ICarProperty via binder reflection — tiada write, GET sahaja.
 *
 * Usage: app_process /data/local/window ProbeAny <propId> [propId2 ...]
 */
public class ProbeAny {

    // Prop IDs dari ISafety (AdapterAPI.jar) — decode statik
    static final long AWAY_LOCK = 738263552L;
    static final long KEY_LOST_WARNING = 738330624L;
    static final long SPEED_AUTO_LOCKING = 739247104L;
    static final long APPROACH_UNLOCK = 738263296L;

    static Method sGetter;
    static Object sService;

    public static void main(String[] args) throws Exception {
        // Default: probe semua prop known
        long[] ids;
        String[] names;
        if (args.length > 0) {
            ids = new long[args.length];
            names = args;
            for (int i = 0; i < args.length; i++) ids[i] = Long.parseLong(args[i]);
        } else {
            ids = new long[]{AWAY_LOCK, KEY_LOST_WARNING, SPEED_AUTO_LOCKING, APPROACH_UNLOCK};
            names = new String[]{"AWAY_LOCK", "KEY_LOST_WARNING", "SPEED_AUTO_LOCKING", "APPROACH_UNLOCK"};
        }

        bootstrap();
        Method getter = sGetter;
        Object svc = sService;
        System.out.println("=== PROBE VHAL READ-ONLY (corak ReadOnlyTelemetry) ===");
        for (int i = 0; i < ids.length; i++) {
            System.out.println(names[i] + " (0x" + Long.toHexString(ids[i]) + "):");
            try {
                Object cpv = getter.invoke(svc, (int) ids[i], 0);
                if (cpv == null) { System.out.println("  VALUE: null"); continue; }
                // CarPropertyValue.getValue() via reflection
                Object raw = cpv.getClass().getMethod("getValue").invoke(cpv);
                Object status = cpv.getClass().getMethod("getStatus").invoke(cpv);
                StringBuilder hex = new StringBuilder();
                if (raw instanceof byte[]) {
                    for (byte b : (byte[]) raw) hex.append(String.format("%02X ", b));
                } else if (raw != null) {
                    hex.append(raw).append(" (").append(raw.getClass().getSimpleName()).append(")");
                }
                System.out.println("  STATUS: " + status + "  VALUE: [" + hex.toString().trim() + "]");
            } catch (Throwable t) {
                Throwable c = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
                System.out.println("  ERR: " + c.getClass().getSimpleName() + ": " + c.getMessage());
            }
        }
    }

    static void bootstrap() throws Exception {
        // hidden API exemption (corak ReadOnlyTelemetry yang terbukti berfungsi)
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object vmRuntime = getRuntime.invoke(null);
            Method setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setHiddenApiExemptions.invoke(vmRuntime, new Object[]{new String[]{"L"}});
        } catch (Throwable ignored) { }

        Class<?> binderClass = Class.forName("android.os.IBinder");
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "car_service");
        if (binder == null) throw new IllegalStateException("car_service unavailable");
        Object car = Class.forName("android.car.ICar$Stub")
                .getMethod("asInterface", binderClass).invoke(null, binder);
        // NAMA BETUL: "property", bukan "CarPropertyService"
        Object propertyBinder = car.getClass().getMethod("getCarService", String.class).invoke(car, "property");
        if (propertyBinder == null) throw new IllegalStateException("property binder unavailable");
        Object propertyService = Class.forName("android.car.hardware.property.ICarProperty$Stub")
                .getMethod("asInterface", binderClass).invoke(null, propertyBinder);
        if (propertyService == null) throw new IllegalStateException("ICarProperty unavailable");

        // Scan getProperty(int, int) — ikut corak ReadOnlyTelemetry
        Method getter = null;
        for (Method m : propertyService.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("getProperty") && p.length == 2
                    && (p[0] == int.class || p[0] == Integer.class) && p[1] == int.class) {
                getter = m; break;
            }
        }
        if (getter == null) {
            for (Method m : propertyService.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().startsWith("getProperty") && p.length == 2) {
                    getter = m; System.out.println("(getter fallback: " + m + ")"); break;
                }
            }
        }
        if (getter == null) throw new IllegalStateException("getProperty method tidak ditemui");
        sGetter = getter; sService = propertyService;
    }
}
