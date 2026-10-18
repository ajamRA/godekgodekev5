package com.emas.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;

public class TestHvacMcu {
    public static void main(String[] args) {
        System.out.println("=== TESTING MCU HVAC WRITE ===");
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
            Method getPropMethod = null;
            for (Method m : propService.getClass().getMethods()) {
                if (m.getName().equals("setProperty") && m.getParameterTypes().length == 1) {
                    setPropMethod = m;
                }
                if (m.getName().equals("getProperty") && m.getParameterTypes().length == 2) {
                    getPropMethod = m;
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

            int propTempDriver = 678428679; // 0x28700007
            int propTempPass = 678428680;   // 0x28700008
            int propFan = 678428677;        // 0x28700005
            int propPower = 678428689;      // 0x28700011
            int propAc = 678428673;         // 0x28700001

            // Let's read current values first
            System.out.println("Reading current MCU properties:");
            int[] testProps = {propTempDriver, propTempPass, propFan, propPower, propAc};
            String[] names = {"TempDriver", "TempPass", "FanSpeed", "Power", "AC"};
            for (int i = 0; i < testProps.length; i++) {
                try {
                    Object res = getPropMethod.invoke(propService, testProps[i], 0);
                    if (res != null) {
                        Method getValue = res.getClass().getMethod("getValue");
                        Object val = getValue.invoke(res);
                        if (val instanceof byte[]) {
                            System.out.println(names[i] + " (0): " + Arrays.toString((byte[]) val));
                        } else {
                            System.out.println(names[i] + " (0): " + val);
                        }
                    } else {
                        System.out.println(names[i] + " (0): null");
                    }
                } catch (Throwable t) {
                    System.out.println(names[i] + " error: " + t.getMessage());
                }
            }

            // Also check zone 1 and 4 for Temp
            try {
                Object res1 = getPropMethod.invoke(propService, propTempDriver, 1);
                if (res1 != null) {
                    Object val = res1.getClass().getMethod("getValue").invoke(res1);
                    System.out.println("TempDriver (zone 1): " + (val instanceof byte[] ? Arrays.toString((byte[]) val) : val));
                }
            } catch (Throwable t) {}

            try {
                Object res4 = getPropMethod.invoke(propService, propTempPass, 4);
                if (res4 != null) {
                    Object val = res4.getClass().getMethod("getValue").invoke(res4);
                    System.out.println("TempPass (zone 4): " + (val instanceof byte[] ? Arrays.toString((byte[]) val) : val));
                }
            } catch (Throwable t) {}

            // Now, if arguments provided, write!
            // args: <temp> <fan>
            if (args.length > 0) {
                float targetTemp = Float.parseFloat(args[0]);
                // data = (int)((targetTemp - 17.0f) * 2.0f); clamped to 0..30
                int tempByteVal = Math.max(0, Math.min(30, (int) Math.round((targetTemp - 17.0f) * 2.0f)));
                System.out.println("Writing targetTemp " + targetTemp + "°C -> byte value: " + tempByteVal);

                // Driver temp (zone 0 and zone 1)
                byte[] tempBytes = new byte[]{(byte) tempByteVal};
                Object cpv = cpvCtor.newInstance(propTempDriver, 0, tempBytes);
                setPropMethod.invoke(propService, cpv);
                Object cpv1 = cpvCtor.newInstance(propTempDriver, 1, tempBytes);
                setPropMethod.invoke(propService, cpv1);

                // Passenger temp (zone 0 and zone 4)
                Object cpvPass = cpvCtor.newInstance(propTempPass, 0, tempBytes);
                setPropMethod.invoke(propService, cpvPass);
                Object cpvPass4 = cpvCtor.newInstance(propTempPass, 4, tempBytes);
                setPropMethod.invoke(propService, cpvPass4);
            }

            if (args.length > 1) {
                int targetFan = Integer.parseInt(args[1]);
                System.out.println("Writing fan speed " + targetFan);
                byte[] fanBytes = new byte[]{(byte) targetFan};
                Object cpvFan = cpvCtor.newInstance(propFan, 0, fanBytes);
                setPropMethod.invoke(propService, cpvFan);
            }

            System.out.println("Done!");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
