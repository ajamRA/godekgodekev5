package com.emas.window;

import java.lang.reflect.Method;

/**
 * Door state probe + monitor untuk IHU801P.
 * Usage:
 *   DoorProbe read            — baca semua door states sekali
 *   DoorProbe monitor [secs]  — poll doors setiap 300ms (default 30s)
 */
public class DoorProbe {

    static final int DOOR_FL = 678428815;   // 0x2870008F
    static final int DOOR_FR = 678428816;   // 0x28700090
    static final int DOOR_RL = 678428817;   // 0x28700091
    static final int DOOR_RR = 678428818;   // 0x28700092
    static final int DOOR_TRUNK = 678428819;// 0x28700093
    static final int DOOR_HOOD = 678428820; // 0x28700094

    static final int WIN_FL = 678428829;    // 0x2870009D
    static final int WIN_FR = 678428830;    // 0x2870009E
    static final int WIN_RL = 678428831;    // 0x2870009F
    static final int WIN_RR = 678428832;    // 0x287000A0

    static Method getPropMethod;
    static Object propService;

    public static void main(String[] args) {
        String action = args.length > 0 ? args[0] : "read";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        try {
            connect();
            if (action.equals("read")) {
                readAll();
            } else if (action.equals("monitor")) {
                monitor(seconds);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static void connect() throws Exception {
        Class<?> smClass = Class.forName("android.os.ServiceManager");
        Object binder = smClass.getMethod("getService", String.class).invoke(null, "car_service");
        Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
        Object icar = iCarStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");
        Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
        propService = iPropStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);
        for (Method m : propService.getClass().getMethods()) {
            if (m.getName().equals("getProperty") && m.getParameterTypes().length == 2) {
                getPropMethod = m;
                break;
            }
        }
        System.out.println("Connected to CarPropertyService");
    }

    static int readInt(String name, int propId) {
        try {
            Object cpv = getPropMethod.invoke(propService, propId, 0);
            if (cpv != null) {
                Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                if (val instanceof byte[]) {
                    byte[] b = (byte[]) val;
                    return b.length > 0 ? (b[0] & 0xFF) : -1;
                } else if (val instanceof Integer) {
                    return (Integer) val;
                }
            }
        } catch (Throwable t) {
            System.out.println("  " + name + " [0x" + Integer.toHexString(propId) + "]: ERR " + t.getCause());
        }
        return -2;
    }

    static void readAll() {
        int fl = readInt("FL", DOOR_FL);
        int fr = readInt("FR", DOOR_FR);
        int rl = readInt("RL", DOOR_RL);
        int rr = readInt("RR", DOOR_RR);
        int trunk = readInt("TRUNK", DOOR_TRUNK);
        int hood = readInt("HOOD", DOOR_HOOD);
        System.out.println("DOORS: FL=" + fl + " FR=" + fr + " RL=" + rl + " RR=" + rr + " TRUNK=" + trunk + " HOOD=" + hood);
        int wfl = readInt("WFL", WIN_FL);
        int wfr = readInt("WFR", WIN_FR);
        int wrl = readInt("WRL", WIN_RL);
        int wrr = readInt("WRR", WIN_RR);
        System.out.println("WINDOWS: FL=" + wfl + " FR=" + wfr + " RL=" + wrl + " RR=" + wrr);
    }

    static void monitor(int seconds) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        int[] prev = new int[]{-9, -9, -9, -9, -9, -9};
        int[] props = {DOOR_FL, DOOR_FR, DOOR_RL, DOOR_RR, DOOR_TRUNK, DOOR_HOOD};
        String[] names = {"FL", "FR", "RL", "RR", "TRUNK", "HOOD"};
        StringBuilder sb = new StringBuilder("[");
        while (System.currentTimeMillis() < end) {
            sb.setLength(1);
            for (int i = 0; i < props.length; i++) {
                int v = readInt(names[i], props[i]);
                if (v != prev[i]) {
                    System.out.println((System.currentTimeMillis() / 1000) + "s  DOOR " + names[i] + ": " + prev[i] + " -> " + v);
                    prev[i] = v;
                }
                sb.append(v);
                if (i < 5) sb.append(",");
            }
            sb.append("]");
            Thread.sleep(300);
        }
        System.out.println("Monitor done. Final: " + sb);
    }
}
