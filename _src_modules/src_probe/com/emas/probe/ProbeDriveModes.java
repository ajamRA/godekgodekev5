package com.emas.probe;

import android.os.IBinder;
import android.os.ServiceManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class ProbeDriveModes {
    public static void main(String[] args) {
        System.out.println("=== PROBING DRIVE MODE VHAL PROPS ON LIVE IHU ===");
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

            // Candidate Drive Mode Property IDs on Geely / Proton e.MAS 5:
            int[] candidates = {
                678428906, // 0x287000EA (Function 92 / DRIVE_MODE)
                678428907, // 0x287000EB
                678429162, // 0x287001EA
                678429418, // 0x287002EA
                678428880, // 0x287000D0
                678428881, // 0x287000D1
                678428882  // 0x287000D2
            };

            for (int prop : candidates) {
                try {
                    Object cpv = getProp.invoke(propService, prop, 0);
                    if (cpv != null) {
                        Object v = cpv.getClass().getMethod("getValue").invoke(cpv);
                        System.out.print("Prop 0x" + Integer.toHexString(prop).toUpperCase() + " (" + prop + "): ");
                        if (v instanceof byte[]) {
                            byte[] b = (byte[]) v;
                            System.out.print("Bytes [ ");
                            for (byte x : b) System.out.print((x & 0xFF) + " ");
                            System.out.println("]");
                        } else {
                            System.out.println(v);
                        }
                    } else {
                        System.out.println("Prop 0x" + Integer.toHexString(prop).toUpperCase() + ": NULL");
                    }
                } catch (Throwable t) {
                    System.out.println("Prop 0x" + Integer.toHexString(prop).toUpperCase() + ": ERR " + t.getMessage());
                }
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
