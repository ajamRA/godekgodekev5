package com.emas.window;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class ProbeTrunk {

    public static final int PROP_TRUNK_ACTION_REQ    = 0x28700049; // 678428745
    public static final int PROP_TRUNK_BASIC_POS_RESP= 0x28700046; // 678428742
    public static final int PROP_DOOR_TRUNK          = 0x28700093; // 678428819
    public static final int PROP_LOCK_TRUNK          = 0x28700099; // 678428825

    public static final byte ACTION_NO_REQ = 0;
    public static final byte ACTION_OPEN   = 1;
    public static final byte ACTION_CLOSE  = 2;
    public static final byte ACTION_STOP   = 3;

    public static void main(String[] args) {
        System.out.println("=== EMAS TRUNK / TAILGATE CONTROLLER ===");
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Object binder = smClass.getMethod("getService", String.class).invoke(null, "car_service");
            Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
            Object icar = iCarStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");
            Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = iPropStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);

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

            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            Constructor<?> cpvCtor = null;
            for (Constructor<?> c : cpvClass.getConstructors()) {
                Class<?>[] pTypes = c.getParameterTypes();
                if (pTypes.length == 3 && pTypes[0] == int.class && pTypes[1] == int.class) {
                    cpvCtor = c;
                    break;
                }
            }

            String cmd = args.length > 0 ? args[0].toLowerCase() : "read";

            if (cmd.equals("read")) {
                printState(propService, getPropMethod);
            } else if (cmd.equals("open") || cmd.equals("close") || cmd.equals("stop")) {
                byte act = cmd.equals("open") ? ACTION_OPEN : cmd.equals("close") ? ACTION_CLOSE : ACTION_STOP;
                System.out.println(">>> Sending Tailgate Command: " + cmd.toUpperCase() + " (action=" + act + ")...");

                byte[] payload4 = new byte[] { act, 0, 0, 0 };
                byte[] payload1 = new byte[] { act };

                int[] targetAreas = { 0, 0x8000000 };
                for (int area : targetAreas) {
                    try {
                        System.out.println("Trying area 0x" + Integer.toHexString(area) + " with 4-byte payload...");
                        Object cpv = cpvCtor.newInstance(PROP_TRUNK_ACTION_REQ, area, payload4);
                        setPropMethod.invoke(propService, cpv);
                        System.out.println("SUCCESS sending to area 0x" + Integer.toHexString(area) + "!");
                    } catch (Exception e) {
                        System.out.println("Failed area 0x" + Integer.toHexString(area) + ": " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                    }
                }

                Thread.sleep(1000);
                printState(propService, getPropMethod);
            } else if (cmd.equals("monitor")) {
                int secs = args.length > 1 ? Integer.parseInt(args[1]) : 20;
                System.out.println("Monitoring trunk state for " + secs + "s (press button on car/screen)...");
                long end = System.currentTimeMillis() + (secs * 1000L);
                while (System.currentTimeMillis() < end) {
                    printState(propService, getPropMethod);
                    Thread.sleep(800);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void printState(Object propService, Method getPropMethod) {
        try {
            Object cpvTrunk = getPropMethod.invoke(propService, PROP_DOOR_TRUNK, 0);
            Object valTrunk = (cpvTrunk != null) ? cpvTrunk.getClass().getMethod("getValue").invoke(cpvTrunk) : null;

            Object cpvLock = getPropMethod.invoke(propService, PROP_LOCK_TRUNK, 0);
            Object valLock = (cpvLock != null) ? cpvLock.getClass().getMethod("getValue").invoke(cpvLock) : null;

            Object cpvAction = getPropMethod.invoke(propService, PROP_TRUNK_ACTION_REQ, 0);
            Object valAction = (cpvAction != null) ? cpvAction.getClass().getMethod("getValue").invoke(cpvAction) : null;

            String doorStr = (valTrunk instanceof byte[] && ((byte[])valTrunk).length > 0) ? String.valueOf(((byte[])valTrunk)[0]) : String.valueOf(valTrunk);
            String lockStr = (valLock instanceof byte[] && ((byte[])valLock).length > 0) ? String.valueOf(((byte[])valLock)[0]) : String.valueOf(valLock);

            StringBuilder actStr = new StringBuilder();
            if (valAction instanceof byte[]) {
                for (byte b : (byte[])valAction) actStr.append(b & 0xFF).append(" ");
            } else {
                actStr.append(valAction);
            }

            System.out.println(String.format("State: Door=%s (%s), Lock=%s, ActionReq=[%s]",
                doorStr, (doorStr.equals("1") ? "OPEN" : "CLOSED"), lockStr, actStr.toString().trim()));
        } catch (Exception ex) {
            System.out.println("Error reading state: " + ex.getMessage());
        }
    }
}
