package com.emas.test;

import java.lang.reflect.Method;

public class TestHvac {
    public static void main(String[] args) {
        System.out.println("=== HVAC AREA PROBE ===");
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
            Method getProp = propService.getClass().getMethod("getProperty", int.class, int.class);

            int[] props = {358614275, 356517120, 354419973, 354419984};
            String[] names = {"SETPOINT", "BLOWER", "AC_ON", "POWER"};

            for (int p = 0; p < props.length; p++) {
                int propId = props[p];
                System.out.println("--- Testing " + names[p] + " (0x" + Integer.toHexString(propId) + ") ---");
                for (int a = 0; a <= 150; a++) {
                    try {
                        Object cpv = getProp.invoke(propService, propId, a);
                        if (cpv != null) {
                            Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                            System.out.println("  -> Area " + a + ": " + val);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
