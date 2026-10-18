package com.emas.test;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.*;

public class HvacPassiveValidator {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("          PROTON e.MAS 5 (IHU801P) - HVAC PASSIVE VALIDATION AUDIT (GET-ONLY)   ");
        System.out.println("================================================================================");

        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Method getService = smCls.getMethod("getService", String.class);
            Object binder = getService.invoke(null, "car_service");
            if (binder == null) {
                System.err.println("FATAL: car_service IBinder is null");
                return;
            }
            Class<?> stub = Class.forName("android.car.ICar$Stub");
            Object carService = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            Method getCarService = carService.getClass().getMethod("getCarService", String.class);
            Object propBinder = getCarService.invoke(carService, "property");
            Class<?> propStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = propStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);
            
            Method getPropList = propService.getClass().getMethod("getPropertyList");
            Method getProp = propService.getClass().getMethod("getProperty", int.class, int.class);

            // Build map of all registered CarPropertyConfigs
            @SuppressWarnings("unchecked")
            List<Object> configList = (List<Object>) getPropList.invoke(propService);
            Map<Integer, Object> configMap = new HashMap<>();
            if (configList != null) {
                for (Object cfg : configList) {
                    int pid = (Integer) cfg.getClass().getMethod("getPropertyId").invoke(cfg);
                    configMap.put(pid, cfg);
                }
            }

            Map<String, Integer> targetProps = new LinkedHashMap<>();
            // Standard VHAL Props
            targetProps.put("HVAC_TEMPERATURE_SET (0x15600503)", 358614275);
            targetProps.put("HVAC_TEMPERATURE_CURRENT (0x15600502)", 358614274);
            targetProps.put("ENV_OUTSIDE_TEMPERATURE (0x11600703)", 291505923);
            targetProps.put("HVAC_FAN_SPEED (0x15400500)", 356517120);
            targetProps.put("HVAC_FAN_DIRECTION (0x15400501)", 356517121);
            targetProps.put("HVAC_AC_ON (0x15200505)", 354419973);
            targetProps.put("HVAC_POWER_ON (0x15200510)", 354419984);
            targetProps.put("HVAC_AUTO_ON (0x1520050A)", 354419978);
            targetProps.put("HVAC_RECIRC_ON (0x15200508)", 354419976);
            targetProps.put("HVAC_DUAL_ON (0x15200509)", 354419977);
            targetProps.put("HVAC_DEFROSTER (0x13200504)", 320865540);
            targetProps.put("HVAC_SEAT_TEMP (0x1540050B)", 356517131);
            targetProps.put("HVAC_SEAT_VENTILATION (0x15400513)", 356517139);

            // Vendor MCU Props
            targetProps.put("GEELY_AC_POWER (0x28700011)", 678428689);
            targetProps.put("GEELY_AC_SWITCH (0x28700001)", 678428673);
            targetProps.put("GEELY_AC_MAX (0x28700002)", 678428674);
            targetProps.put("GEELY_DEFROST_FRONT (0x28700003)", 678428675);
            targetProps.put("GEELY_DEFROST_REAR (0x28700004)", 678428676);
            targetProps.put("GEELY_FAN_SPEED (0x28700005)", 678428677);
            targetProps.put("GEELY_BLOWING_MODE (0x28700006)", 678428678);
            targetProps.put("GEELY_TEMP_DRIVER (0x28700007)", 678428679);
            targetProps.put("GEELY_TEMP_PASS (0x28700008)", 678428680);
            targetProps.put("GEELY_TEMP_DUAL (0x2870000A)", 678428682);
            targetProps.put("GEELY_AUTO (0x2870000D)", 678428685);
            targetProps.put("GEELY_CIRCULATION (0x2870000E)", 678428686);
            targetProps.put("GEELY_AUTO_FAN_SETTING (0x287001A8)", 678429096);
            targetProps.put("GEELY_PM25_INDOOR (0x28700013)", 678428691);
            targetProps.put("GEELY_PM25_AMBIENT (0x28700014)", 678428692);

            System.out.println("\n[A] CONFIGURATION AUDIT (CarPropertyConfig metadata):");
            System.out.println(String.format("%-38s | %-12s | %-10s | %-10s | %-12s | %s",
                    "Property Name", "Prop ID", "Access", "ChangeMode", "Type", "Supported Area IDs"));
            System.out.println(new String(new char[110]).replace('\0', '-'));

            for (Map.Entry<String, Integer> entry : targetProps.entrySet()) {
                String name = entry.getKey();
                int pid = entry.getValue();
                Object cfg = configMap.get(pid);
                if (cfg == null) {
                    System.out.println(String.format("%-38s | 0x%-10x | %-10s | %-10s | %-12s | %s",
                            name, pid, "NOT_IN_VHAL", "-", "-", "NONE"));
                    continue;
                }

                int access = (Integer) cfg.getClass().getMethod("getAccess").invoke(cfg);
                String accessStr = (access == 1 ? "READ" : (access == 2 ? "WRITE" : (access == 3 ? "READ_WRITE" : "NONE")));
                
                int changeMode = (Integer) cfg.getClass().getMethod("getChangeMode").invoke(cfg);
                String modeStr = (changeMode == 0 ? "STATIC" : (changeMode == 1 ? "ON_CHANGE" : (changeMode == 2 ? "CONTINUOUS" : String.valueOf(changeMode))));

                Class<?> propType = (Class<?>) cfg.getClass().getMethod("getPropertyType").invoke(cfg);
                String typeStr = propType != null ? propType.getSimpleName() : "null";

                int[] areaIds = (int[]) cfg.getClass().getMethod("getAreaIds").invoke(cfg);
                String areasStr = (areaIds != null && areaIds.length > 0) ? Arrays.toString(areaIds) : "[GLOBAL (0)]";

                System.out.println(String.format("%-38s | 0x%-10x | %-10s | %-10s | %-12s | %s",
                        name, pid, accessStr, modeStr, typeStr, areasStr));
            }

            System.out.println("\n[B] LIVE SNAPSHOT AUDIT (CarPropertyValue read):");
            System.out.println(String.format("%-36s | %-8s | %-12s | %-18s | %s",
                    "Property Name", "Area ID", "Status", "Timestamp (ns / boot)", "Value"));
            System.out.println(new String(new char[110]).replace('\0', '-'));

            for (Map.Entry<String, Integer> entry : targetProps.entrySet()) {
                String name = entry.getKey();
                int pid = entry.getValue();
                Object cfg = configMap.get(pid);
                
                int[] areasToQuery = new int[]{0};
                if (cfg != null) {
                    int[] cfgAreas = (int[]) cfg.getClass().getMethod("getAreaIds").invoke(cfg);
                    if (cfgAreas != null && cfgAreas.length > 0) {
                        areasToQuery = cfgAreas;
                    }
                }

                for (int area : areasToQuery) {
                    try {
                        Object cpv = getProp.invoke(propService, pid, area);
                        if (cpv == null) {
                            System.out.println(String.format("%-36s | %-8d | %-12s | %-18s | %s",
                                    name, area, "NULL_RETURN", "-", "null"));
                            continue;
                        }

                        int status = (Integer) cpv.getClass().getMethod("getStatus").invoke(cpv);
                        String statusStr = (status == 0 ? "AVAILABLE" : (status == 1 ? "UNAVAILABLE" : "ERROR (" + status + ")"));

                        long timestamp = (Long) cpv.getClass().getMethod("getTimestamp").invoke(cpv);
                        Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                        String valStr = (val instanceof byte[]) ? Arrays.toString((byte[]) val) : String.valueOf(val);

                        System.out.println(String.format("%-36s | %-8d | %-12s | %-18d | %s",
                                name, area, statusStr, timestamp, valStr));

                    } catch (Throwable t) {
                        Throwable cause = t.getCause() != null ? t.getCause() : t;
                        System.out.println(String.format("%-36s | %-8d | %-12s | %-18s | EXCEPTION: %s",
                                name, area, "EXCEPTION", "-", cause.getClass().getSimpleName() + " " + cause.getMessage()));
                    }
                }
            }

            System.out.println("\n================================================================================");
            System.out.println("AUDIT RUN COMPLETE (NO PROPERTIES WERE WRITTEN OR MODIFIED).");
            System.out.println("================================================================================");

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
