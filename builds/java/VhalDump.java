package com.emas.dump;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * VhalDump - Penyenaraian penuh VHAL (Android Automotive CarService).
 *
 * STRICTLY READ-ONLY. Tiada setProperty, tiada socket, tiada sentuhan MCU.
 * Hanya memanggil getPropertyList() dan getProperty(int,int) melalui reflection.
 *
 * Dua pembetulan penting berbanding versi awal:
 *   1. setHiddenApiExemptions dipanggil dahulu (corak sama seperti
 *      EmasEnergyApp/ReadOnlyTelemetry.java yang sudah terbukti pada IHU801P).
 *   2. Method dicari merentasi interface dan superclass, bukan hanya
 *      getClass().getMethod() pada objek proxy Binder.
 */
public class VhalDump {

    private static final String OUTPUT_FILE = "/data/local/tmp/vhal_dump.json";
    private static final String TAG = "VhalDump";

    /**
     * Jeda antara bacaan (ms). Setiap getProperty ialah satu Binder call; sesetengah
     * VHAL vendor memajukannya ke MCU melalui /dev/ecp_uart_1. Jeda kecil menghalang
     * limpahan pautan serial dan memastikan telemetri kereta tidak tersekat.
     * Lalai 8 ms. Set 0 untuk laju (kereta parkir, enjin mati).
     */
    private static int readDelayMs = 8;

    public static void main(String[] args) {
        for (String a : args) {
            if (a != null && a.startsWith("--delay=")) {
                try {
                    readDelayMs = Integer.parseInt(a.substring("--delay=".length()));
                    if (readDelayMs < 0) readDelayMs = 0;
                } catch (NumberFormatException ignored) {}
            }
        }

        System.out.println("==================================================================");
        System.out.println("   PROTON e.MAS 5 / GEELY EX2 - VHAL ENUMERATOR (READ-ONLY)");
        System.out.println("   Jeda bacaan: " + readDelayMs + " ms");
        System.out.println("==================================================================");

        OutputStreamWriter writer = null;
        int written = 0;
        int totalProps = 0;
        int vendorCount = 0;
        int readOk = 0;

        try {
            ensureHiddenApiExempt();

            Object propService = connectPropertyService();
            System.out.println("[OK] ICarProperty tersambung: " + describe(propService));

            Method getPropertyList = findMethod(propService.getClass(), "getPropertyList");
            Method getProperty = findGetProperty(propService);
            if (getProperty == null) {
                System.err.println("[FAIL] getProperty(int,int) tidak dijumpai. Berhenti.");
                return;
            }

            List<?> configs = null;
            if (getPropertyList != null) {
                try {
                    Object r = getPropertyList.invoke(propService);
                    if (r instanceof List) configs = (List<?>) r;
                    System.out.println("[OK] getPropertyList() memulangkan " +
                            (configs == null ? "null" : configs.size() + " entri"));
                } catch (Throwable t) {
                    System.out.println("[!] getPropertyList() gagal: " + t);
                }
            } else {
                System.out.println("[!] getPropertyList() tiada pada kelas ini.");
            }

            if (configs != null && !configs.isEmpty()) {
                totalProps = configs.size();
            }

            File out = new File(OUTPUT_FILE);
            writer = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
            writer.write("{\n");
            writer.write("  \"tool\": \"VhalDump\",\n");
            writer.write("  \"mode\": \"read-only\",\n");
            writer.write("  \"timestamp\": \"" + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()) + "\",\n");
            writer.write("  \"total_properties\": " + totalProps + ",\n");
            writer.write("  \"properties\": [\n");

            StringBuilder body = new StringBuilder();

            if (configs != null && !configs.isEmpty()) {
                for (int i = 0; i < configs.size(); i++) {
                    Object cfg = configs.get(i);
                    if (cfg == null) continue;

                    Integer propId = asInt(invokeSafe(cfg, "getPropertyId"));
                    if (propId == null) continue;

                    if (isVendor(propId)) vendorCount++;

                    String typeName = typeNameOf(cfg);
                    Integer access = asInt(invokeSafe(cfg, "getAccess"));
                    Integer changeMode = asInt(invokeSafe(cfg, "getChangeMode"));

                    int[] areas = asIntArray(invokeSafe(cfg, "getAreaIds"));
                    if (areas == null || areas.length == 0) areas = new int[]{0};

                    for (int a : areas) {
                        String status;
                        String valueJson;
                        long ts = 0;

                        try {
                            Object cpv = getProperty.invoke(propService, propId, a);
                            if (cpv == null) {
                                status = "NULL";
                                valueJson = "null";
                            } else {
                                Object raw = invokeSafe(cpv, "getValue");
                                valueJson = jsonValue(raw);
                                Long t = asLong(invokeSafe(cpv, "getTimestamp"));
                                if (t != null) ts = t;
                                status = "OK";
                                readOk++;
                            }
                        } catch (Throwable t) {
                            status = "ERR";
                            valueJson = "\"" + escape(String.valueOf(t)) + "\"";
                        }

                        throttle();

                        if (body.length() > 0) body.append(",\n");
                        body.append("    {\n");
                        body.append("      \"prop_id_dec\": ").append(propId).append(",\n");
                        body.append("      \"prop_id_hex\": \"").append(String.format("0x%08X", propId)).append("\",\n");
                        body.append("      \"area_id\": ").append(a).append(",\n");
                        body.append("      \"is_vendor\": ").append(isVendor(propId)).append(",\n");
                        body.append("      \"data_type\": \"").append(typeName).append("\",\n");
                        body.append("      \"access_mode\": ").append(access == null ? -1 : access).append(",\n");
                        body.append("      \"change_mode\": ").append(changeMode == null ? -1 : changeMode).append(",\n");
                        body.append("      \"read_status\": \"").append(status).append("\",\n");
                        body.append("      \"timestamp\": ").append(ts).append(",\n");
                        body.append("      \"value\": ").append(valueJson).append("\n");
                        body.append("    }");
                        written++;
                    }

                    if ((i + 1) % 50 == 0 || (i + 1) == configs.size()) {
                        System.out.println("  [*] " + (i + 1) + "/" + configs.size() + " property diproses");
                    }
                }
            } else {
                System.out.println("[!] getPropertyList() kosong. Jalankan imbasan julat vendor 0x2870xxxx.");
                int[] probe = {
                        0x287001AF, 0x287001B0, 0x28700315, 0x28700314, 0x28700316,
                        0x287001A4, 0x287001A5, 0x287001A6, 0x28700001, 0x28700005,
                        0x28700006, 0x28700007, 0x28700008, 0x2870000E, 0x28700011,
                        0x15400500, 0x15600503, 0x11600201, 0x11400100
                };
                for (int p : probe) {
                    String status;
                    String valueJson;
                    try {
                        Object cpv = getProperty.invoke(propService, p, 0);
                        if (cpv == null) {
                            status = "NULL";
                            valueJson = "null";
                        } else {
                            valueJson = jsonValue(invokeSafe(cpv, "getValue"));
                            status = "OK";
                            readOk++;
                        }
                    } catch (Throwable t) {
                        status = "ERR";
                        valueJson = "\"" + escape(String.valueOf(t)) + "\"";
                    }
                    throttle();
                    if (isVendor(p)) vendorCount++;
                    if (body.length() > 0) body.append(",\n");
                    body.append("    {\n");
                    body.append("      \"prop_id_dec\": ").append(p).append(",\n");
                    body.append("      \"prop_id_hex\": \"").append(String.format("0x%08X", p)).append("\",\n");
                    body.append("      \"area_id\": 0,\n");
                    body.append("      \"is_vendor\": ").append(isVendor(p)).append(",\n");
                    body.append("      \"data_type\": \"unknown\",\n");
                    body.append("      \"access_mode\": -1,\n");
                    body.append("      \"change_mode\": -1,\n");
                    body.append("      \"read_status\": \"").append(status).append("\",\n");
                    body.append("      \"timestamp\": 0,\n");
                    body.append("      \"value\": ").append(valueJson).append("\n");
                    body.append("    }");
                    written++;
                }
            }

            writer.write(body.toString());
            writer.write("\n  ]\n}\n");
            writer.flush();
            writer.close();
            writer = null;

            System.out.println();
            System.out.println("==================================================================");
            System.out.println("  SELESAI");
            System.out.println("  Property disenarai : " + totalProps);
            System.out.println("  Property vendor    : " + vendorCount);
            System.out.println("  Nilai dibaca OK    : " + readOk);
            System.out.println("  Entri JSON ditulis : " + written);
            System.out.println("  Fail               : " + OUTPUT_FILE);
            System.out.println("==================================================================");

            if (written == 0) {
                System.err.println("[WARN] Sifar entri ditulis. Semak log di atas.");
            }

        } catch (Throwable t) {
            System.err.println("[FAIL] " + t);
            t.printStackTrace(System.err);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** Jeda antara bacaan supaya pautan serial MCU tidak dibanjiri. */
    private static void throttle() {
        if (readDelayMs <= 0) return;
        try {
            Thread.sleep(readDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Bypass sekatan hidden API (Android 9+). Corak sama seperti ReadOnlyTelemetry.java. */
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
            System.out.println("[OK] Hidden API exemption dipasang.");
        } catch (Throwable t) {
            System.out.println("[!] Hidden API exemption gagal (mungkin bukan Android): " + t);
        }
    }

    /** Sambung ke ICarProperty melalui ServiceManager -> ICar -> getCarService("property"). */
    private static Object connectPropertyService() throws Exception {
        Class<?> binderCls = Class.forName("android.os.IBinder");
        Class<?> smCls = Class.forName("android.os.ServiceManager");

        Object binder = smCls.getMethod("getService", String.class).invoke(null, "car_service");
        if (binder == null) throw new IllegalStateException("car_service binder null");

        Object car = Class.forName("android.car.ICar$Stub")
                .getMethod("asInterface", binderCls).invoke(null, binder);
        if (car == null) throw new IllegalStateException("ICar asInterface null");

        Method getCarService = findMethod(car.getClass(), "getCarService");
        if (getCarService == null) getCarService = findMethod(car.getClass(), "getCarManager");
        if (getCarService == null) throw new IllegalStateException("getCarService tidak dijumpai");

        Object propBinder = getCarService.invoke(car, "property");
        if (propBinder == null) throw new IllegalStateException("property binder null");

        Object propService = Class.forName("android.car.hardware.property.ICarProperty$Stub")
                .getMethod("asInterface", binderCls).invoke(null, propBinder);
        if (propService == null) throw new IllegalStateException("ICarProperty asInterface null");

        return propService;
    }

    /** Cari method mengikut nama merentasi kelas, interface, dan superclass. */
    private static Method findMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name)) return m;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
            for (Class<?> itf : c.getInterfaces()) {
                Method m = findMethod(itf, name);
                if (m != null) return m;
            }
        }
        return null;
    }

    /** Cari getProperty(int,int) secara eksplisit - elak salah pilih overload. */
    private static Method findGetProperty(Object service) {
        for (Class<?> c = service.getClass(); c != null; c = c.getSuperclass()) {
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

    private static Object invokeSafe(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = findMethod(target.getClass(), name);
            if (m == null) return null;
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Integer asInt(Object o) {
        if (o instanceof Integer) return (Integer) o;
        if (o instanceof Number) return ((Number) o).intValue();
        return null;
    }

    private static Long asLong(Object o) {
        if (o instanceof Long) return (Long) o;
        if (o instanceof Number) return ((Number) o).longValue();
        return null;
    }

    private static int[] asIntArray(Object o) {
        if (o instanceof int[]) return (int[]) o;
        if (o instanceof Integer[]) {
            Integer[] boxed = (Integer[]) o;
            int[] r = new int[boxed.length];
            for (int i = 0; i < boxed.length; i++) r[i] = boxed[i] == null ? 0 : boxed[i];
            return r;
        }
        return null;
    }

    private static String typeNameOf(Object cfg) {
        try {
            Method m = findMethod(cfg.getClass(), "getPropertyType");
            if (m == null) return "unknown";
            Object t = m.invoke(cfg);
            if (t instanceof Class) return ((Class<?>) t).getSimpleName();
            return String.valueOf(t);
        } catch (Throwable e) {
            return "unknown";
        }
    }

    /** 0x20000000 = VehiclePropertyGroup:VENDOR. Julat ECARX 0x2870xxxx juga vendor. */
    private static boolean isVendor(int propId) {
        return (propId & 0x20000000) != 0 || (propId & 0xFFFF0000) == 0x28700000;
    }

    private static String describe(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean || v instanceof Number) return String.valueOf(v);
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < b.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format("%02X", b[i]));
            }
            return sb.append('"').toString();
        }
        if (v instanceof int[]) {
            int[] a = (int[]) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(a[i]);
            }
            return sb.append(']').toString();
        }
        if (v instanceof float[]) {
            float[] a = (float[]) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(a[i]);
            }
            return sb.append(']').toString();
        }
        if (v instanceof long[]) {
            long[] a = (long[]) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(a[i]);
            }
            return sb.append(']').toString();
        }
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(jsonValue(a[i]));
            }
            return sb.append(']').toString();
        }
        return "\"" + escape(String.valueOf(v)) + "\"";
    }
}
