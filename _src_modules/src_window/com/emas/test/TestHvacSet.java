package com.emas.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

/** One-shot: read MCU temp bytes, nudge left by +1°C, read back, restore. */
public class TestHvacSet {
    static final int LEFT = 678428679;
    static final int RIGHT = 678428680;
    static final int FAN = 678428677;
    static final int AC = 678428673;
    static final int POWER = 678428689;
    static final int DUAL_W = 678428681; // 0x28700009 write-only double
    static final int DUAL_S = 678428682; // 0x2870000A
    static final int SETPOINT = 358614275;
    static final int MCU = 0x08000000;

    public static void main(String[] args) {
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Object binder = smCls.getMethod("getService", String.class).invoke(null, "car_service");
            Class<?> stub = Class.forName("android.car.ICar$Stub");
            Object car = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            Object propBinder = car.getClass().getMethod("getCarService", String.class).invoke(car, "property");
            Class<?> propStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object prop = propStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);
            Method getProp = prop.getClass().getMethod("getProperty", int.class, int.class);
            Method setProp = null;
            for (Method m : prop.getClass().getMethods()) {
                if (m.getName().equals("setProperty") && m.getParameterTypes().length == 1) setProp = m;
            }
            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            Constructor<?> cpvCtor = null;
            for (Constructor<?> c : cpvClass.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 3 && p[0] == int.class && p[1] == int.class) { cpvCtor = c; break; }
            }

            int[] areas = {0, 1, 4, MCU};
            int[] ids = {LEFT, RIGHT, FAN, AC, POWER, DUAL_S};
            String[] names = {"L", "R", "FAN", "AC", "PWR", "DUAL"};
            System.out.println("--- before ---");
            for (int i = 0; i < ids.length; i++) dump(getProp, prop, names[i], ids[i], areas);
            dumpFloat(getProp, prop, "SET49", SETPOINT, 49);
            dumpFloat(getProp, prop, "SET68", SETPOINT, 68);

            dumpFloat(getProp, prop, "before49", SETPOINT, 49);
            System.out.println("WRITE float area 49 = 18");
            try {
                Object cpv = cpvCtor.newInstance(SETPOINT, 49, Float.valueOf(18f));
                System.out.println("  built id=" + cpv.getClass().getMethod("getPropertyId").invoke(cpv)
                        + " area=" + cpv.getClass().getMethod("getAreaId").invoke(cpv)
                        + " val=" + cpv.getClass().getMethod("getValue").invoke(cpv)
                        + " ctor=" + cpvCtor);
                setProp.invoke(prop, cpv);
                System.out.println("  float set returned");
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                System.out.println("  float FAIL " + c);
            }
            Thread.sleep(400);
            dumpFloat(getProp, prop, "after49", SETPOINT, 49);
            System.out.println("RESTORE float area 49 = 16");
            try {
                Object cpv = cpvCtor.newInstance(SETPOINT, 49, Float.valueOf(16f));
                setProp.invoke(prop, cpv);
            } catch (Throwable t) {
                System.out.println("  restore FAIL " + t.getMessage());
            }
            Thread.sleep(200);
            dumpFloat(getProp, prop, "end49", SETPOINT, 49);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void writeByte(Method setProp, Constructor<?> ctor, Object prop, int id, int area, int v) {
        try {
            Object cpv = ctor.newInstance(id, area, new byte[]{(byte) v});
            setProp.invoke(prop, cpv);
            System.out.println("  set ok id=" + id + " area=" + area + " v=" + v);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            System.out.println("  set FAIL area=" + area + " " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }

    static int first(Method getProp, Object prop, int id, int area) {
        try {
            Object res = getProp.invoke(prop, id, area);
            if (res == null) return -1;
            Object val = res.getClass().getMethod("getValue").invoke(res);
            if (val instanceof byte[] && ((byte[]) val).length > 0) return ((byte[]) val)[0] & 0xFF;
            return -2;
        } catch (Throwable t) {
            return -3;
        }
    }

    static void dump(Method getProp, Object prop, String name, int id, int[] areas) {
        StringBuilder sb = new StringBuilder(name);
        for (int a : areas) {
            try {
                Object res = getProp.invoke(prop, id, a);
                if (res == null) { sb.append(" a").append(a).append("=null"); continue; }
                Object val = res.getClass().getMethod("getValue").invoke(res);
                sb.append(" a").append(a == MCU ? "MCU" : String.valueOf(a)).append("=").append(val instanceof byte[] ? Arrays.toString((byte[]) val) : String.valueOf(val));
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                sb.append(" a").append(a == MCU ? "MCU" : String.valueOf(a)).append(" ERR ").append(c.getClass().getSimpleName());
            }
        }
        System.out.println(sb);
    }

    static Object readAny(Method getProp, Object prop, int id, int area) {
        try {
            Object res = getProp.invoke(prop, id, area);
            if (res == null) return null;
            return res.getClass().getMethod("getValue").invoke(res);
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    static void dumpFloat(Method getProp, Object prop, String name, int id, int area) {
        try {
            Object res = getProp.invoke(prop, id, area);
            Object val = res == null ? null : res.getClass().getMethod("getValue").invoke(res);
            System.out.println(name + "=" + val);
        } catch (Throwable t) {
            System.out.println(name + " ERR " + t.getMessage());
        }
    }
}
