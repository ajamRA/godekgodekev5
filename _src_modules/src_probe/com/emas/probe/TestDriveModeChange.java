package com.emas.probe;

import android.os.IBinder;
import android.os.ServiceManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class TestDriveModeChange {
    public static void main(String[] args) {
        System.out.println("=== TEST ALL DRIVE MODE CANDIDATES ===");
        try {
            IBinder binder = ServiceManager.getService("car_service");
            Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
            Object icar = iCarStub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");
            Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = iPropStub.getMethod("asInterface", IBinder.class).invoke(null, propBinder);

            Method getProp = propService.getClass().getMethod("getProperty", int.class, int.class);
            Method setProp = null;
            for (Method m : propService.getClass().getMethods()) {
                if (m.getName().equals("setProperty") && m.getParameterTypes().length == 1) {
                    setProp = m;
                    break;
                }
            }

            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            Constructor<?> cpvCtor = cpvClass.getConstructor(int.class, int.class, Object.class);

            int[] props = {
                678428906, // 0x287000EA
                678428907, // 0x287000EB
                678429162, // 0x287001EA
                678428880, // 0x287000D0
                678428881  // 0x287000D1
            };

            for (int p : props) {
                System.out.println("\nTesting Prop 0x" + Integer.toHexString(p).toUpperCase() + " (" + p + "):");
                try {
                    // Try writing 2 (Sport)
                    setProp.invoke(propService, cpvCtor.newInstance(p, 0, new byte[]{(byte) 2}));
                    Thread.sleep(150);
                    Object read = getProp.invoke(propService, p, 0);
                    if (read != null) {
                        byte[] b = (byte[]) read.getClass().getMethod("getValue").invoke(read);
                        System.out.println("  Readback after write 2: " + (b[0] & 0xFF));
                    }
                } catch (Throwable t) {
                    System.out.println("  Error: " + t.getMessage());
                }
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
