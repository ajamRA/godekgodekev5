package com.emas.probe;

import android.os.IBinder;
import android.os.ServiceManager;
import java.lang.reflect.Method;

public class ProbeMoreProps {
    public static void main(String[] args) {
        System.out.println("=== PROBING SUSPECT DRIVE MODE PROPS ===");
        try {
            IBinder binder = ServiceManager.getService("car_service");
            Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
            Object icar = iCarStub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");
            Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = iPropStub.getMethod("asInterface", IBinder.class).invoke(null, propBinder);

            Method getProp = propService.getClass().getMethod("getProperty", int.class, int.class);

            int[] props = {
                0x28700192, // 678429074
                0x287002CE, // 678429390
                0x28700300, // 678429440
                0x28700302, // 678429442
                0x287000EA, // 678428906
                0x287001B1, // 678429105 (Regen)
                0x28700062,
                0x28700063
            };

            for (int p : props) {
                try {
                    Object cpv = getProp.invoke(propService, p, 0);
                    if (cpv != null) {
                        Object v = cpv.getClass().getMethod("getValue").invoke(cpv);
                        System.out.print("Prop 0x" + Integer.toHexString(p).toUpperCase() + ": ");
                        if (v instanceof byte[]) {
                            byte[] b = (byte[]) v;
                            System.out.print("Bytes [ ");
                            for (byte x : b) System.out.print((x & 0xFF) + " ");
                            System.out.println("]");
                        } else {
                            System.out.println(v);
                        }
                    }
                } catch (Throwable t) {}
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
