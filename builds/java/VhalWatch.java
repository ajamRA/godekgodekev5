package com.emas.dump;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * VhalWatch - pemantau perubahan VHAL (READ-ONLY).
 *
 * Mengambil snapshot semua property yang boleh dibaca, kemudian mengundi
 * semula dan mencetak HANYA yang berubah, dengan cap masa.
 *
 * Selamat: tiada setProperty, tiada socket, tiada sentuhan MCU.
 *
 * Guna:
 *   app_process . com.emas.dump.VhalWatch --secs=120 --delay=0 --filter=vendor
 */
public class VhalWatch {

    private static int readDelayMs = 0;
    private static int seconds = 120;
    private static String filter = "vendor";
    private static int maxBytes = 8;
    /** ID blok mentah besar yang berubah setiap detik — tidak berguna untuk pemetaan. */
    private static final java.util.Set<Integer> NOISY = new java.util.HashSet<Integer>(
            Arrays.asList(0x28700313, 0x28700314));
    /** Array lebih panjang dari ini dianggap blok mentah, bukan isyarat tunggal. */
    private static final int RAW_BLOCK_LIMIT = 32;

    private static Method getProperty;
    private static Object propService;
    private static final Map<String, String> last = new LinkedHashMap<String, String>();
    private static final Map<String, Integer> propIdByKey = new LinkedHashMap<String, Integer>();
    private static final Map<String, String> typeByKey = new LinkedHashMap<String, String>();

    public static void main(String[] args) {
        for (String a : args) {
            if (a == null) continue;
            if (a.startsWith("--delay=")) readDelayMs = parseInt(a.substring(8), 0);
            else if (a.startsWith("--secs=")) seconds = parseInt(a.substring(7), 120);
            else if (a.startsWith("--filter=")) filter = a.substring(9);
        }

        System.out.println("# VhalWatch READ-ONLY | filter=" + filter
                + " delay=" + readDelayMs + "ms secs=" + seconds);
        System.out.println("# elapsed_ms\tevent\tprop_hex\tarea\told\tnew");

        try {
            ensureHiddenApiExempt();
            propService = connectPropertyService();

            Method list = findMethod(propService.getClass(), "getPropertyList");
            getProperty = findGetProperty(propService);
            if (list == null || getProperty == null) {
                System.out.println("# FAIL: getPropertyList atau getProperty tiada");
                return;
            }

            List<?> configs = (List<?>) list.invoke(propService);
            List<int[]> targets = new ArrayList<int[]>();
            for (Object cfg : configs) {
                if (cfg == null) continue;
                Integer id = asInt(invokeSafe(cfg, "getPropertyId"));
                if (id == null) continue;
                if (NOISY.contains(id)) continue;
                Integer access = asInt(invokeSafe(cfg, "getAccess"));
                if (access == null || access == 2) continue; // 2 = WRITE sahaja
                if ("vendor".equals(filter) && !isVendor(id)) continue;

                String type = typeNameOf(cfg);
                int[] areas = asIntArray(invokeSafe(cfg, "getAreaIds"));
                if (areas == null || areas.length == 0) areas = new int[]{0};
                for (int ar : areas) {
                    String key = id + ":" + ar;
                    targets.add(new int[]{id, ar});
                    propIdByKey.put(key, id);
                    typeByKey.put(key, type);
                }
            }

            System.out.println("# " + targets.size() + " sasaran dipantau");

            // snapshot awal
            for (int[] t : targets) {
                String key = t[0] + ":" + t[1];
                try {
                    Object cpv = getProperty.invoke(propService, t[0], t[1]);
                    String v = cpv == null ? null : fmt(invokeSafe(cpv, "getValue"));
                    if (v != null) last.put(key, v);
                } catch (Throwable ignored) {}
                throttle();
            }
            System.out.println("# snapshot awal: " + last.size() + " nilai");

            long start = System.currentTimeMillis();
            long endAt = start + seconds * 1000L;
            int cycle = 0;

            while (System.currentTimeMillis() < endAt) {
                cycle++;
                for (int[] t : targets) {
                    String key = t[0] + ":" + t[1];
                    String prev = last.get(key);
                    String cur;
                    try {
                        Object cpv = getProperty.invoke(propService, t[0], t[1]);
                        if (cpv == null) continue;
                        cur = fmt(invokeSafe(cpv, "getValue"));
                    } catch (Throwable e) {
                        continue;
                    }
                    if (cur == null) continue;
                    if (!cur.equals(prev)) {
                        // Buang blok mentah besar: terlalu banyak nilai, bukan isyarat tunggal
                        int tokens = cur.indexOf(' ') < 0 && cur.indexOf(',') < 0
                                ? 1 : cur.split("[ ,]").length;
                        if (tokens > RAW_BLOCK_LIMIT) {
                            last.put(key, cur);
                            continue;
                        }
                        if (prev != null) {
                            long el = System.currentTimeMillis() - start;
                            System.out.println(el + "\tCHG\t0x"
                                    + String.format("%08X", t[0]) + "\t" + t[1]
                                    + "\t" + shortv(prev) + "\t" + shortv(cur));
                        }
                        last.put(key, cur);
                    }
                    throttle();
                }
                Thread.sleep(120);
            }

            System.out.println("# selesai, " + cycle + " kitaran");

        } catch (Throwable t) {
            System.out.println("# FAIL: " + t);
            t.printStackTrace(System.out);
        }
    }

    private static int parseInt(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; }
    }

    private static void throttle() {
        if (readDelayMs <= 0) return;
        try { Thread.sleep(readDelayMs); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void ensureHiddenApiExempt() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", (Class[]) null);
            Method setExempt = (Method) getDeclaredMethod.invoke(vmRuntime,
                    "setHiddenApiExemptions", new Class<?>[]{String[].class});
            Object rt = getRuntime.invoke(null);
            setExempt.invoke(rt, new Object[]{new String[]{"L"}});
            System.out.println("# hidden API exemption ok");
        } catch (Throwable t) {
            System.out.println("# hidden API exemption gagal: " + t);
        }
    }

    private static Object connectPropertyService() throws Exception {
        Class<?> binderCls = Class.forName("android.os.IBinder");
        Class<?> smCls = Class.forName("android.os.ServiceManager");
        Object binder = smCls.getMethod("getService", String.class).invoke(null, "car_service");
        if (binder == null) throw new IllegalStateException("car_service null");
        Object car = Class.forName("android.car.ICar$Stub")
                .getMethod("asInterface", binderCls).invoke(null, binder);
        Method gcs = findMethod(car.getClass(), "getCarService");
        if (gcs == null) gcs = findMethod(car.getClass(), "getCarManager");
        Object pb = gcs.invoke(car, "property");
        Object ps = Class.forName("android.car.hardware.property.ICarProperty$Stub")
                .getMethod("asInterface", binderCls).invoke(null, pb);
        if (ps == null) throw new IllegalStateException("ICarProperty null");
        return ps;
    }

    private static Method findMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) if (m.getName().equals(name)) return m;
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name)) return m;
            for (Class<?> itf : c.getInterfaces()) {
                Method m = findMethod(itf, name);
                if (m != null) return m;
            }
        }
        return null;
    }

    private static Method findGetProperty(Object s) {
        for (Class<?> c = s.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (!"getProperty".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == int.class && p[1] == int.class) return m;
            }
            for (Class<?> itf : c.getInterfaces()) {
                for (Method m : itf.getMethods()) {
                    if (!"getProperty".equals(m.getName())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 2 && p[0] == int.class && p[1] == int.class) return m;
                }
            }
        }
        return null;
    }

    private static Object invokeSafe(Object t, String n) {
        if (t == null) return null;
        try {
            Method m = findMethod(t.getClass(), n);
            return m == null ? null : m.invoke(t);
        } catch (Throwable e) { return null; }
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        return null;
    }

    private static int[] asIntArray(Object o) {
        if (o instanceof int[]) return (int[]) o;
        if (o instanceof Integer[]) {
            Integer[] b = (Integer[]) o;
            int[] r = new int[b.length];
            for (int i = 0; i < b.length; i++) r[i] = b[i] == null ? 0 : b[i];
            return r;
        }
        return null;
    }

    private static String typeNameOf(Object cfg) {
        try {
            Method m = findMethod(cfg.getClass(), "getPropertyType");
            if (m == null) return "?";
            Object t = m.invoke(cfg);
            return t instanceof Class ? ((Class<?>) t).getSimpleName() : String.valueOf(t);
        } catch (Throwable e) { return "?"; }
    }

    private static boolean isVendor(int id) {
        return (id & 0x20000000) != 0 || (id & 0xFFFF0000) == 0x28700000;
    }

    private static String shortv(String v) {
        if (v == null) return "null";
        return v.length() <= 48 ? v : v.substring(0, 45) + "...";
    }

    private static String fmt(Object v) {
        if (v == null) return null;
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format("%02X", b[i]));
            }
            return sb.toString();
        }
        if (v instanceof int[]) return Arrays.toString((int[]) v);
        if (v instanceof float[]) return Arrays.toString((float[]) v);
        if (v instanceof long[]) return Arrays.toString((long[]) v);
        if (v instanceof Object[]) return Arrays.toString((Object[]) v);
        return String.valueOf(v);
    }
}
