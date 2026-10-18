package com.emas.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Set HVAC_TEMPERATURE_SET area 49 to 18, read back, restore 16. */
public class TestFloatOnly {
    public static void main(String[] args) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Object binder = sm.getMethod("getService", String.class).invoke(null, "car_service");
        Class<?> stub = Class.forName("android.car.ICar$Stub");
        Object car = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Object pb = car.getClass().getMethod("getCarService", String.class).invoke(car, "property");
        Class<?> ps = Class.forName("android.car.hardware.property.ICarProperty$Stub");
        Object prop = ps.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, pb);
        Method get = prop.getClass().getMethod("getProperty", int.class, int.class);
        Method set = null;
        for (Method m : prop.getClass().getMethods()) {
            if (m.getName().equals("setProperty") && m.getParameterTypes().length == 1) set = m;
        }
        Constructor<?> ctor = null;
        for (Constructor<?> c : Class.forName("android.car.hardware.CarPropertyValue").getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 3 && p[0] == int.class && p[1] == int.class && p[2] == Object.class) ctor = c;
        }
        int id = 358614275;
        System.out.println("before " + val(get, prop, id, 49));
        set.invoke(prop, ctor.newInstance(id, 49, Float.valueOf(18f)));
        Thread.sleep(700);
        System.out.println("after " + val(get, prop, id, 49));
        set.invoke(prop, ctor.newInstance(id, 49, Float.valueOf(16f)));
        Thread.sleep(300);
        System.out.println("end " + val(get, prop, id, 49));
    }

    static Object val(Method get, Object prop, int id, int area) throws Exception {
        Object r = get.invoke(prop, id, area);
        return r == null ? null : r.getClass().getMethod("getValue").invoke(r);
    }
}
