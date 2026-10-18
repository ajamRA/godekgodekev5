package com.emas.test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class HvacSensorAudit {
    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("   PROTON e.MAS 5 - HVAC & SENSOR AUDIT (LIVE)   ");
        System.out.println("==================================================");

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

            Map<String, Integer> standardProps = new LinkedHashMap<>();
            standardProps.put("ENV_OUTSIDE_TEMPERATURE (0x11600703)", 291505923);
            standardProps.put("HVAC_TEMPERATURE_CURRENT/CABIN (0x15600502)", 358614274);
            standardProps.put("HVAC_TEMPERATURE_SET (0x15600503)", 358614275);
            standardProps.put("HVAC_FAN_SPEED (0x15400500)", 356517120);
            standardProps.put("HVAC_FAN_DIRECTION (0x15400501)", 356517121);
            standardProps.put("HVAC_AC_ON (0x15200505)", 354419973);
            standardProps.put("HVAC_POWER_ON (0x15200510)", 354419984);
            standardProps.put("HVAC_AUTO_ON (0x1520050A)", 354419978);
            standardProps.put("HVAC_RECIRC_ON (0x15200508)", 354419976);
            standardProps.put("HVAC_DUAL_ON (0x15200509)", 354419977);
            standardProps.put("HVAC_DEFROSTER (0x13200504)", 320865540);

            System.out.println("\n[1] STANDARD ANDROID AUTOMOTIVE VHAL PROPERTIES:");
            for (Map.Entry<String, Integer> entry : standardProps.entrySet()) {
                String name = entry.getKey();
                int propId = entry.getValue();
                boolean found = false;
                for (int area : new int[]{0, 1, 2, 4, 16, 32, 48, 49, 64, 68, 117}) {
                    try {
                        Object cpv = getProp.invoke(propService, propId, area);
                        if (cpv != null) {
                            Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                            String valStr = (val instanceof byte[]) ? Arrays.toString((byte[]) val) : String.valueOf(val);
                            System.out.println("  " + name + " [Area " + area + "] = " + valStr);
                            found = true;
                        }
                    } catch (Throwable ignored) {}
                }
                if (!found) {
                    System.out.println("  " + name + " = <NOT AVAILABLE / UNSUPPORTED>");
                }
            }

            Map<String, Integer> geelyProps = new LinkedHashMap<>();
            geelyProps.put("GEELY_AC_POWER (0x28700011)", 678428689);
            geelyProps.put("GEELY_AC_SWITCH (0x28700001)", 678428673);
            geelyProps.put("GEELY_AC_MAX (0x28700002)", 678428674);
            geelyProps.put("GEELY_FAN_SPEED (0x28700005)", 678428677);
            geelyProps.put("GEELY_BLOWING_MODE (0x28700006)", 678428678);
            geelyProps.put("GEELY_TEMP_DRIVER (0x28700007)", 678428679);
            geelyProps.put("GEELY_TEMP_PASS (0x28700008)", 678428680);
            geelyProps.put("GEELY_AUTO (0x2870000D)", 678428685);
            geelyProps.put("GEELY_AUTO_FAN_SETTING (0x287001A8)", 678429096);
            geelyProps.put("GEELY_CIRCULATION (0x2870000E)", 678428686);
            geelyProps.put("GEELY_DEFROST_FRONT (0x28700003)", 678428675);
            geelyProps.put("GEELY_DEFROST_REAR (0x28700004)", 678428676);

            System.out.println("\n[2] GEELY / ECARX CUSTOM MCU PROPERTIES:");
            for (Map.Entry<String, Integer> entry : geelyProps.entrySet()) {
                String name = entry.getKey();
                int propId = entry.getValue();
                boolean found = false;
                for (int area : new int[]{0, 1, 4}) {
                    try {
                        Object cpv = getProp.invoke(propService, propId, area);
                        if (cpv != null) {
                            Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                            String valStr = (val instanceof byte[]) ? Arrays.toString((byte[]) val) : String.valueOf(val);
                            System.out.println("  " + name + " [Area " + area + "] = " + valStr);
                            found = true;
                        }
                    } catch (Throwable ignored) {}
                }
                if (!found) {
                    System.out.println("  " + name + " = <NULL / UNSUPPORTED>");
                }
            }

            System.out.println("\n==================================================");
            System.out.println("AUDIT COMPLETE.");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
