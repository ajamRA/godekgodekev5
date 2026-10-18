package com.emas.probe;

import java.lang.reflect.Method;

/**
 * Probe ICarFunction (ecarx XUI) — READ-ONLY sahaja.
 * isFunctionSupported + getFunctionValue untuk AWAY_LOCK & co.
 * Ikut corak binder ReadOnlyTelemetry yang terbukti berfungsi.
 */
public class ProbeFunction {

    // SETTING_FUNC IDs dari ISafety (AdapterAPI.jar)
    static final int AWAY_LOCK = 738263552;            // 0x2c010200
    static final int KEY_LOST_WARNING = 738330624;   // 0x2c020800
    static final int SPEED_AUTO_LOCKING = 739247104;  // 0x2c100400
    static final int APPROACH_UNLOCK = 738263296;     // 0x2c010100
    static final int AUTO_FOLD_MIRRORS = 738263040;   // SETTING_FUNC_AUTO_FOLD? semak
    static final int AUTO_HOLD = 738259968;
    static final int AVAS_SWITCH = 738259456;

    public static void main(String[] args) throws Exception {
        int[] ids = {AWAY_LOCK, KEY_LOST_WARNING, SPEED_AUTO_LOCKING, APPROACH_UNLOCK,
                     AUTO_FOLD_MIRRORS, AUTO_HOLD, AVAS_SWITCH};
        String[] names = {"AWAY_LOCK", "KEY_LOST_WARNING", "SPEED_AUTO_LOCKING",
                          "APPROACH_UNLOCK", "AUTO_FOLD_MIRRORS?", "AUTO_HOLD?", "AVAS_SWITCH?"};

        // hidden API exemption
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object vmRuntime = getRuntime.invoke(null);
            Method setEx = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setEx.invoke(vmRuntime, new Object[]{new String[]{"L"}});
        } catch (Throwable ignored) { }

        // Dapatkan ICarFunction service
        Class<?> binderClass = Class.forName("android.os.IBinder");
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "car_service");
        if (binder == null) throw new IllegalStateException("car_service unavailable");
        Object car = Class.forName("android.car.ICar$Stub")
                .getMethod("asInterface", binderClass).invoke(null, binder);

        // ICarFunction diadaptasi dari getCarService("function")?
        Object funcBinder = null;
        String[] svcNames = {"function", "CarFunctionService", "ecarx_ihu_service"};
        for (String sn : svcNames) {
            try {
                Object b = car.getClass().getMethod("getCarService", String.class).invoke(car, sn);
                if (b != null) { funcBinder = b; System.out.println("(service: " + sn + ")"); break; }
            } catch (Throwable t) { /* cuba seterusnya */ }
        }
        if (funcBinder == null) throw new IllegalStateException("function binder unavailable");

        // asInterface — kena tau Stub class. Cuba beberapa
        Object funcSvc = null;
        String[] stubClasses = {
            "com.ecarx.xui.adaptapi.car.base.ICarFunction$Stub",
            "com.ecarx.xui.adaptapi.ICarFunction$Stub"
        };
        for (String sc : stubClasses) {
            try {
                funcSvc = Class.forName(sc).getMethod("asInterface", binderClass).invoke(null, funcBinder);
                if (funcSvc != null) { System.out.println("(stub: " + sc + ")"); break; }
            } catch (Throwable t) { }
        }
        if (funcSvc == null) throw new IllegalStateException("ICarFunction stub tak jumpa");

        // Method signatures (dari parse DEX):
        // isFunctionSupported(I) -> FunctionStatus
        // getFunctionValue(I) -> I
        Method isSupported = null, getValue = null;
        for (Method m : funcSvc.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("isFunctionSupported") && p.length == 1 && p[0] == int.class) isSupported = m;
            if (m.getName().equals("getFunctionValue") && p.length == 1 && p[0] == int.class) getValue = m;
        }
        System.out.println("isSupported=" + isSupported + " getValue=" + getValue);

        System.out.println("\n=== PROBE ICarFunction READ-ONLY ===");
        for (int i = 0; i < ids.length; i++) {
            System.out.println(names[i] + " (" + ids[i] + "):");
            try {
                Object sup = isSupported.invoke(funcSvc, ids[i]);
                System.out.println("  SUPPORTED: " + sup);
            } catch (Throwable t) {
                Throwable c = (t.getCause() != null) ? t.getCause() : t;
                System.out.println("  SUPPORT-ERR: " + c.getClass().getSimpleName() + ": " + c.getMessage());
            }
            try {
                Object v = getValue.invoke(funcSvc, ids[i]);
                System.out.println("  VALUE: " + v);
            } catch (Throwable t) {
                Throwable c = (t.getCause() != null) ? t.getCause() : t;
                System.out.println("  VALUE-ERR: " + c.getClass().getSimpleName() + ": " + c.getMessage());
            }
        }
    }
}
