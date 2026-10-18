package com.emas.probe;

import android.os.IBinder;
import android.os.ServiceManager;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class TestVhalDriveMode {
    public static void main(String[] args) {
        System.out.println("=== TEST DRIVE MODE MODES ON LIVE IHU ===");
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

            int propDrive = 678428906; // 0x287000EA

            // Try setting mode 1 (Sport)
            System.out.println("\nSetting Drive Mode -> 1 (Sport)...");
            setProp.invoke(propService, cpvCtor.newInstance(propDrive, 0, new byte[]{(byte) 1}));
            Thread.sleep(300);

            Object read1 = getProp.invoke(propService, propDrive, 0);
            if (read1 != null) {
                byte[] b = (byte[]) read1.getClass().getMethod("getValue").invoke(read1);
                System.out.println("Readback Drive Mode: " + (b[0] & 0xFF));
            }

            // Try setting mode 2 (Eco)
            System.out.println("\nSetting Drive Mode -> 2 (Eco)...");
            setProp.invoke(propService, cpvCtor.newInstance(propDrive, 0, new byte[]{(byte) 2}));
            Thread.sleep(300);

            Object read2 = getProp.invoke(propService, propDrive, 0);
            if (read2 != null) {
                byte[] b = (byte[]) read2.getClass().getMethod("getValue").invoke(read2);
                System.out.println("Readback Drive Mode: " + (b[0] & 0xFF));
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
