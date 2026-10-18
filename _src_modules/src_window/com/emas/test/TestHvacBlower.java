package com.emas.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class TestHvacBlower {
    public static void main(String[] args) {
        System.out.println("=== TESTING BLOWER SPEED CONTROL ===");
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

            int propBlower = 356517120; // 0x15400500 (HVAC_FAN_SPEED)
            int area = 117;

            // Read current
            Object res = getProp.invoke(prop, propBlower, area);
            Object curVal = res != null ? res.getClass().getMethod("getValue").invoke(res) : null;
            System.out.println("Current Blower Speed (Area 117): " + curVal);

            int currentSpeed = (curVal instanceof Integer) ? (Integer) curVal : 3;
            int testSpeed = (currentSpeed == 3) ? 4 : 3;

            System.out.println("Attempting to write Blower Speed -> " + testSpeed);
            Object cpv = cpvCtor.newInstance(propBlower, area, Integer.valueOf(testSpeed));
            setProp.invoke(prop, cpv);

            Thread.sleep(500);

            // Read back
            Object resAfter = getProp.invoke(prop, propBlower, area);
            Object newVal = resAfter != null ? resAfter.getClass().getMethod("getValue").invoke(resAfter) : null;
            System.out.println("New Blower Speed (Area 117): " + newVal);

            Thread.sleep(1000);

            // Restore
            System.out.println("Restoring original Blower Speed -> " + currentSpeed);
            Object cpvRestore = cpvCtor.newInstance(propBlower, area, Integer.valueOf(currentSpeed));
            setProp.invoke(prop, cpvRestore);

            Thread.sleep(500);
            Object resEnd = getProp.invoke(prop, propBlower, area);
            Object endVal = resEnd != null ? resEnd.getClass().getMethod("getValue").invoke(resEnd) : null;
            System.out.println("Final Blower Speed (Area 117): " + endVal);

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
