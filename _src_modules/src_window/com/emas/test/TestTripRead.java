package com.emas.test;

import java.lang.reflect.Method;
import java.util.Arrays;

public class TestTripRead {
    public static void main(String[] args) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Object binder = sm.getMethod("getService", String.class).invoke(null, "car_service");
        Object car = Class.forName("android.car.ICar$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Object pb = car.getClass().getMethod("getCarService", String.class).invoke(car, "property");
        Object prop = Class.forName("android.car.hardware.property.ICarProperty$Stub")
                .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, pb);
        Method get = prop.getClass().getMethod("getProperty", int.class, int.class);
        int[] ids = {
            678429315, 678429313, 678429290, 678429291, 678429292, 678429293,
            678429318, 678429319, 678429417, 291504644, 678429101
        };
        String[] names = {
            "trip1km", "trip2km", "dur1", "dur2", "spd1", "spd2",
            "avg1", "avg2", "avgE", "odo", "range"
        };
        int[] areas = {0, 0x08000000};
        for (int i = 0; i < ids.length; i++) {
            for (int area : areas) {
                try {
                    Object r = get.invoke(prop, ids[i], area);
                    Object v = r == null ? null : r.getClass().getMethod("getValue").invoke(r);
                    String shown = v instanceof byte[] ? Arrays.toString((byte[]) v) + " le=" + le((byte[]) v) : String.valueOf(v);
                    System.out.println(names[i] + " a" + (area == 0 ? "0" : "MCU") + " " + shown);
                } catch (Throwable t) {
                    Throwable c = t.getCause() != null ? t.getCause() : t;
                    System.out.println(names[i] + " a" + area + " " + c.getClass().getSimpleName());
                }
            }
        }
    }

    static int le(byte[] b) {
        if (b == null) return -1;
        int n = 0;
        for (int i = b.length - 1; i >= 0; i--) n = (n << 8) | (b[i] & 255);
        return n;
    }
}
