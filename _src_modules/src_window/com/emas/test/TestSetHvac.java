package com.emas.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class TestSetHvac {
    public static void main(String[] args) {
        System.out.println("=== TESTING HVAC WRITE ===");
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Method getService = smCls.getMethod("getService", String.class);
            Object binder = getService.invoke(null, "car_service");
            Class<?> stub = Class.forName("android.car.ICar$Stub");
            Object carService = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            Method getCarService = carService.getClass().getMethod("getCarService", String.class);
            Object propBinder = getCarService.invoke(carService, "property");
            Class<?> propStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = propStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);
            
            Method setPropMethod = null;
            for (Method m : propService.getClass().getMethods()) {
                if (m.getName().equals("setProperty") && m.getParameterTypes().length == 1) {
                    setPropMethod = m;
                    break;
                }
            }

            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            Constructor<?> cpvCtor = null;
            for (Constructor<?> c : cpvClass.getConstructors()) {
                Class<?>[] pTypes = c.getParameterTypes();
                if (pTypes.length == 3 && pTypes[0] == int.class && pTypes[1] == int.class) {
                    cpvCtor = c;
                    break;
                }
            }

            float targetTemp = 22.0f;
            if (args.length > 0) targetTemp = Float.parseFloat(args[0]);

            int targetFan = 4;
            if (args.length > 1) targetFan = Integer.parseInt(args[1]);

            System.out.println("Writing Setpoint " + targetTemp + "°C to Area 49 & 68...");
            Object cpvTemp1 = cpvCtor.newInstance(358614275, 49, Float.valueOf(targetTemp));
            setPropMethod.invoke(propService, cpvTemp1);
            Object cpvTemp2 = cpvCtor.newInstance(358614275, 68, Float.valueOf(targetTemp));
            setPropMethod.invoke(propService, cpvTemp2);

            System.out.println("Writing Fan Speed " + targetFan + " to Area 117...");
            Object cpvFan = cpvCtor.newInstance(356517120, 117, Integer.valueOf(targetFan));
            setPropMethod.invoke(propService, cpvFan);

            System.out.println("SUCCESS! Values written to VHAL.");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
