package com.emas.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

public class TestHvacBlowerMcu {
    public static void main(String[] args) {
        System.out.println("=== TESTING MCU BLOWER SPEED WRITE ===");
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

            int stdBlower = 356517120; // Standard VHAL
            int mcuBlower = 678428677; // Geely MCU Prop (0x28700005)

            // Read current standard blower
            Object resStd = getProp.invoke(prop, stdBlower, 117);
            System.out.println("Initial Standard Blower (117): " + (resStd != null ? resStd.getClass().getMethod("getValue").invoke(resStd) : null));

            // Write MCU Blower = 4
            System.out.println("Writing MCU Prop 678428677 with byte[] {4}...");
            byte[] b4 = new byte[]{(byte) 4};
            Object cpv4 = cpvCtor.newInstance(mcuBlower, 0, b4);
            setProp.invoke(prop, cpv4);

            Thread.sleep(800);

            // Read standard blower again
            Object resStd2 = getProp.invoke(prop, stdBlower, 117);
            System.out.println("After Write - Standard Blower (117): " + (resStd2 != null ? resStd2.getClass().getMethod("getValue").invoke(resStd2) : null));

            Thread.sleep(1200);

            // Restore MCU Blower = 3
            System.out.println("Restoring MCU Prop 678428677 with byte[] {3}...");
            byte[] b3 = new byte[]{(byte) 3};
            Object cpv3 = cpvCtor.newInstance(mcuBlower, 0, b3);
            setProp.invoke(prop, cpv3);

            Thread.sleep(800);
            Object resStd3 = getProp.invoke(prop, stdBlower, 117);
            System.out.println("Final - Standard Blower (117): " + (resStd3 != null ? resStd3.getClass().getMethod("getValue").invoke(resStd3) : null));

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
