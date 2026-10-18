package com.emas.window;

import java.lang.reflect.Method;

public class ReadWindows {
    public static void main(String[] args) {
        System.out.println("=== PROBING WINDOW PROPERTIES ===");
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Object binder = smClass.getMethod("getService", String.class).invoke(null, "car_service");
            Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
            Object icar = iCarStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");
            Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = iPropStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);

            Method getProp = propService.getClass().getMethod("getProperty", int.class, int.class);

            int[] props = {322964416, 591405227, 537396224, 320867268};
            String[] propNames = {"WINDOW_POS (AOSP)", "WINDOW_POS (Geely 0x234020AB)", "AUTO_CLOSE", "WINDOW_LOCK"};
            int[] areas = {0, 16, 64, 256, 1024};

            for (int p = 0; p < props.length; p++) {
                int propId = props[p];
                System.out.println("\nChecking " + propNames[p] + " [ID: " + propId + " / 0x" + Integer.toHexString(propId) + "]");
                for (int area : areas) {
                    try {
                        Object cpv = getProp.invoke(propService, propId, area);
                        if (cpv != null) {
                            Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                            int status = (int) cpv.getClass().getMethod("getStatus").invoke(cpv);
                            System.out.println("  Area " + area + ": val=" + val + " (status=" + status + ")");
                        } else {
                            System.out.println("  Area " + area + ": null");
                        }
                    } catch (Exception e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        System.out.println("  Area " + area + ": ERR (" + cause.getMessage() + ")");
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
