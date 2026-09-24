package com.emas.window;

import java.lang.reflect.Method;

public class TestDoors {
    public static void main(String[] args) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Object binder = smClass.getMethod("getService", String.class).invoke(null, "car_service");
            Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
            Object icar = iCarStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");
            Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = iPropStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);
            Method getPropMethod = propService.getClass().getMethod("getProperty", int.class, int.class);

            int[] doorProps = {678428815, 678428816, 678428817, 678428818, 678428819, 678428820};
            String[] doorNames = {"FL Door", "FR Door", "RL Door", "RR Door", "Trunk", "Hood"};

            for (int i = 0; i < doorProps.length; i++) {
                Object cpv = getPropMethod.invoke(propService, doorProps[i], 0);
                if (cpv != null) {
                    Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                    if (val instanceof byte[]) {
                        byte[] b = (byte[]) val;
                        System.out.println(doorNames[i] + " [0x" + Integer.toHexString(doorProps[i]) + "]: " + (b.length > 0 ? (b[0] & 0xFF) : "empty"));
                    } else {
                        System.out.println(doorNames[i] + " [0x" + Integer.toHexString(doorProps[i]) + "]: " + val);
                    }
                } else {
                    System.out.println(doorNames[i] + ": null");
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}