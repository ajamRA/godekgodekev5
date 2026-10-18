package com.emas.window;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class WindowTool {
    public static void main(String[] args) {
        System.out.println("=== EMAS VEHICLE WINDOW CLI ===");
        try {
            int targetVal = 10;
            if (args.length > 0) {
                targetVal = Integer.parseInt(args[0]);
            }
            System.out.println("Target Position: " + targetVal + "%");

            // 1. Get ServiceManager.getService("car_service")
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            Object binder = getService.invoke(null, "car_service");
            if (binder == null) {
                System.out.println("ERROR: car_service binder is null!");
                return;
            }

            // 2. ICar icar = ICar.Stub.asInterface(binder)
            Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
            Object icar = iCarStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);

            // 3. IBinder propBinder = icar.getCarService("property")
            Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");

            // 4. ICarProperty propService = ICarProperty.Stub.asInterface(propBinder)
            Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = iPropStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);

            // Methods of ICarProperty
            Method getPropMethod = null;
            Method setPropMethod = null;
            for (Method m : propService.getClass().getMethods()) {
                if (m.getName().equals("getProperty") && m.getParameterTypes().length == 2) {
                    getPropMethod = m;
                }
                if (m.getName().equals("setProperty") && m.getParameterTypes().length == 1) {
                    setPropMethod = m;
                }
            }

            int propId = 322964416; // 0x13400BC0 (WINDOW_POS)
            int[] areas = {16, 64, 256, 1024};
            String[] names = {"Driver (FL)", "Passenger (FR)", "Rear Left (RL)", "Rear Right (RR)"};

            // Class CarPropertyValue
            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            Constructor<?> cpvCtor = null;
            for (Constructor<?> c : cpvClass.getConstructors()) {
                Class<?>[] pTypes = c.getParameterTypes();
                if (pTypes.length == 3 && pTypes[0] == int.class && pTypes[1] == int.class) {
                    cpvCtor = c;
                    break;
                }
            }

            for (int i = 0; i < areas.length; i++) {
                int area = areas[i];
                System.out.println("--- " + names[i] + " (Area " + area + ") ---");
                
                // Read current value
                if (getPropMethod != null) {
                    try {
                        Object currentCpv = getPropMethod.invoke(propService, propId, area);
                        if (currentCpv != null) {
                            Object val = currentCpv.getClass().getMethod("getValue").invoke(currentCpv);
                            System.out.println("Current position: " + val + "%");
                        }
                    } catch (Exception e) {
                        System.out.println("Read failed: " + (e.getCause() != null ? e.getCause() : e));
                    }
                }

                // Write target value
                if (setPropMethod != null && cpvCtor != null) {
                    try {
                        Object newCpv = cpvCtor.newInstance(propId, area, Integer.valueOf(targetVal));
                        setPropMethod.invoke(propService, newCpv);
                        System.out.println("SUCCESS -> Commanded " + names[i] + " to " + targetVal + "%");
                    } catch (Exception e) {
                        System.out.println("Write failed: " + (e.getCause() != null ? e.getCause() : e));
                    }
                }
            }

            System.out.println("=== EXECUTION FINISHED ===");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
