package com.emas.window;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Direct VHAL vendor property window control.
 * Bypasses AdapterAPI — writes directly to CarPropertyService
 * using the OEM vendor prop IDs from DoorAndWindowImpl.
 *
 * Usage: app_process ... WindowDirect <action> [percent]
 *   action: fl|fr|rl|rr|all|read
 *   percent: 0 = close, 10 = crack, 100 = full open
 *
 * Single window:  sendInt(propId, area=0, percent)
 * Group windows:  sendBytes(0x287000A2, area=0, byte[8])
 */
public class WindowDirect {

    // OEM vendor prop IDs from DoorAndWindowImpl decompilation
    // NOTE: In setWindowStatus, window=16(FL) sends to 678428830, window=32(FR) sends to 678428829
    // This matches the READ mapping in getWindowValue: FL=678428829, FR=678428830
    // The setter has FL→830 and FR→829 which looks swapped vs getter, 
    // but we'll try BOTH approaches
    
    // Read props (getWindowValue mapping):
    static final int PROP_READ_FL  = 678428829;  // 0x2870009D
    static final int PROP_READ_FR  = 678428830;  // 0x2870009E 
    static final int PROP_READ_RL  = 678428831;  // 0x2870009F
    static final int PROP_READ_RR  = 678428832;  // 0x287000A0
    
    // Write props (setWindowStatus mapping - note FL/FR appear swapped):
    // window=16(FL) → sendIntToMCUByPropID(678428830)  
    // window=32(FR) → sendIntToMCUByPropID(678428829)
    // Actually looking again: the code says window != 32 -> window != 16 
    // The FIRST if is window=16 -> prop 678428830
    // The SECOND if is window=32 -> prop 678428829
    // These ARE swapped vs getter which reads FL from 829 and FR from 830
    // But we follow exactly what setWindowStatus does:
    static final int PROP_WRITE_FL = 678428830;  // 0x2870009E (setWindowStatus window=16)
    static final int PROP_WRITE_FR = 678428829;  // 0x2870009D (setWindowStatus window=32)
    static final int PROP_WRITE_RL = 678428831;  // 0x2870009F  
    static final int PROP_WRITE_RR = 678428832;  // 0x287000A0
    
    // Group write prop
    static final int PROP_GROUP    = 678428834;  // 0x287000A2
    
    // Sunroof
    static final int PROP_SUNROOF = 678428827;   // 0x2870009B

    public static void main(String[] args) {
        System.out.println("=== EMAS DIRECT VHAL WINDOW CONTROL ===");
        try {
            String action = "read";
            int percent = 10;
            if (args.length > 0) action = args[0].toLowerCase();
            if (args.length > 1) percent = Integer.parseInt(args[1]);
            
            System.out.println("Action: " + action + ", Percent: " + percent);

            // Connect to car_service via ServiceManager
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Object binder = smClass.getMethod("getService", String.class).invoke(null, "car_service");
            if (binder == null) {
                System.out.println("ERROR: car_service binder is null!");
                return;
            }
            System.out.println("car_service binder: OK");

            // ICar = ICar.Stub.asInterface(binder)
            Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
            Object icar = iCarStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);

            // IBinder propBinder = icar.getCarService("property")
            Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");

            // ICarProperty propService = ICarProperty.Stub.asInterface(propBinder) 
            Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = iPropStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);
            System.out.println("CarPropertyService: OK");

            // Find getProperty and setProperty methods
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
            
            // CarPropertyValue class & constructors
            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            // Find constructor: CarPropertyValue(int propertyId, int areaId, T value)
            Constructor<?> cpvCtorInt = null;
            Constructor<?> cpvCtorBytes = null;
            for (Constructor<?> c : cpvClass.getConstructors()) {
                Class<?>[] pTypes = c.getParameterTypes();
                if (pTypes.length == 3 && pTypes[0] == int.class && pTypes[1] == int.class) {
                    // This is the generic constructor — works for both Integer and byte[]
                    cpvCtorInt = c;
                    cpvCtorBytes = c;
                    break;
                }
            }

            if (action.equals("json")) {
                int fl = getPropInt(getPropMethod, propService, PROP_READ_FL);
                int fr = getPropInt(getPropMethod, propService, PROP_READ_FR);
                int rl = getPropInt(getPropMethod, propService, PROP_READ_RL);
                int rr = getPropInt(getPropMethod, propService, PROP_READ_RR);
                System.out.println(String.format("{\"fl\":%d,\"fr\":%d,\"rl\":%d,\"rr\":%d,\"status\":\"ok\"}", fl, fr, rl, rr));
                return;
            }

            if (action.equals("read")) {
                // Read all window positions using vendor props
                System.out.println("\n--- Reading Window Positions (Vendor Props) ---");
                readProp(getPropMethod, propService, "FL", PROP_READ_FL);
                readProp(getPropMethod, propService, "FR", PROP_READ_FR);
                readProp(getPropMethod, propService, "RL", PROP_READ_RL);
                readProp(getPropMethod, propService, "RR", PROP_READ_RR);
                readProp(getPropMethod, propService, "Sunroof", PROP_SUNROOF);
                
            } else if (action.equals("all")) {
                // Group write: all 4 windows using byte[8] to prop 0x287000A2
                System.out.println("\n--- Group Write: ALL windows to " + percent + "% ---");
                byte[] data = new byte[]{-1,-1,-1,-1,-1,-1,-1,-1};
                data[0] = (byte) percent; // FL
                data[1] = (byte) percent; // FR
                data[2] = (byte) percent; // RL
                data[3] = (byte) percent; // RR
                System.out.println("Sending byte[8]: " + bytesToHex(data) + " to prop " + PROP_GROUP);
                
                try {
                    Object cpv = cpvCtorBytes.newInstance(PROP_GROUP, 0, data);
                    setPropMethod.invoke(propService, cpv);
                    System.out.println("SUCCESS: Group write sent!");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    System.out.println("FAILED: " + cause);
                    cause.printStackTrace(System.out);
                }
                
            } else {
                // Single window write
                int writeProp = 0;
                String winName = "";
                switch (action) {
                    case "fl": writeProp = PROP_WRITE_FL; winName = "FL"; break;
                    case "fr": writeProp = PROP_WRITE_FR; winName = "FR"; break;
                    case "rl": writeProp = PROP_WRITE_RL; winName = "RL"; break;
                    case "rr": writeProp = PROP_WRITE_RR; winName = "RR"; break;
                    default:
                        System.out.println("Unknown action: " + action);
                        System.out.println("Usage: WindowDirect <read|fl|fr|rl|rr|all> [percent]");
                        return;
                }
                
                System.out.println("\n--- Single Write: " + winName + " to " + percent + "% ---");
                System.out.println("Using prop ID: " + writeProp + " (0x" + Integer.toHexString(writeProp) + ")");
                
                try {
                    // Props are byte[] type (len=1), not int
                    byte[] val = new byte[]{(byte) percent};
                    Object cpv = cpvCtorInt.newInstance(writeProp, 0, val);
                    setPropMethod.invoke(propService, cpv);
                    System.out.println("SUCCESS: " + winName + " commanded to " + percent + "%");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    System.out.println("FAILED: " + cause);
                    cause.printStackTrace(System.out);
                }
            }
            
            // Read back after write
            if (!action.equals("read")) {
                Thread.sleep(500);
                System.out.println("\n--- Post-write readback ---");
                readProp(getPropMethod, propService, "FL", PROP_READ_FL);
                readProp(getPropMethod, propService, "FR", PROP_READ_FR);
                readProp(getPropMethod, propService, "RL", PROP_READ_RL);
                readProp(getPropMethod, propService, "RR", PROP_READ_RR);
            }

            System.out.println("\n=== DONE ===");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    static void readProp(Method getPropMethod, Object propService, String name, int propId) {
        try {
            Object cpv = getPropMethod.invoke(propService, propId, 0);
            if (cpv != null) {
                Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                String hex = Integer.toHexString(propId);
                if (val instanceof byte[]) {
                    byte[] bytes = (byte[]) val;
                    System.out.println("  " + name + " [0x" + hex + "]: " + bytesToHex(bytes) + " (len=" + bytes.length + ")");
                } else if (val instanceof Integer) {
                    System.out.println("  " + name + " [0x" + hex + "]: " + val + " (int)");
                } else {
                    System.out.println("  " + name + " [0x" + hex + "]: " + val + " (" + (val != null ? val.getClass().getSimpleName() : "null") + ")");
                }
            } else {
                System.out.println("  " + name + " [0x" + Integer.toHexString(propId) + "]: null");
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("  " + name + " [0x" + Integer.toHexString(propId) + "]: ERROR - " + cause.getMessage());
        }
    }
    
    static int getPropInt(Method getPropMethod, Object propService, int propId) {
        try {
            Object cpv = getPropMethod.invoke(propService, propId, 0);
            if (cpv != null) {
                Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                if (val instanceof byte[]) {
                    byte[] bytes = (byte[]) val;
                    if (bytes.length > 0) return (bytes[0] & 0xFF);
                } else if (val instanceof Integer) {
                    return ((Integer) val).intValue();
                }
            }
        } catch (Exception e) {}
        return 0;
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("0x%02X", bytes[i] & 0xFF));
        }
        sb.append("]");
        return sb.toString();
    }
}
