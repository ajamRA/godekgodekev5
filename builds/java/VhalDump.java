package com.emas.dump;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * VhalDump - Alat Penyenaraian Lengkap VHAL (Android Automotive CarService)
 * 
 * Menggunakan Reflection Java untuk memanggil CarPropertyManager.getPropertyList()
 * secara 100% Read-Only tanpa memerlukan sebarang kebenaran root tambahan atau
 * mengusik port perkakasan serial MCU (/dev/ecp_uart_1).
 */
public class VhalDump {

    private static final String OUTPUT_FILE = "/data/local/tmp/vhal_dump.json";

    public static void main(String[] args) {
        System.out.println("==================================================================");
        System.out.println("        PROTON e.MAS 5 / GEELY EX2 — VHAL ENUMERATOR & DUMPER     ");
        System.out.println("==================================================================");
        System.out.println("[*] Memulakan proses sambungan ke android.car.ICar / car_service...");

        try {
            // 1. Sambung ke ServiceManager -> "car_service"
            Class<?> iBinderCls = Class.forName("android.os.IBinder");
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Method getService = smCls.getMethod("getService", String.class);
            Object binder = getService.invoke(null, "car_service");

            if (binder == null) {
                System.err.println("[FAIL] GAGAL: Tidak dapat menyambung ke 'car_service'. Adakah peranti sedang boot?");
                return;
            }

            Class<?> stub = Class.forName("android.car.ICar$Stub");
            Object carService = stub.getMethod("asInterface", iBinderCls).invoke(null, binder);

            // 2. Ambil ICarProperty service binder
            Method getCarService = carService.getClass().getMethod("getCarService", String.class);
            Object propBinder = getCarService.invoke(carService, "property");

            Class<?> propStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = propStub.getMethod("asInterface", iBinderCls).invoke(null, propBinder);

            System.out.println("[OK] Sambungan ke ICarProperty berjaya!");

            // 3. Panggil getPropertyList() untuk menyenaraikan SEMUA property
            Method getPropertyListMethod = null;
            try {
                getPropertyListMethod = propService.getClass().getMethod("getPropertyList");
            } catch (NoSuchMethodException e) {
                System.out.println("[!] getPropertyList() tiada, mencuba getPropertyConfigList()...");
                try {
                    getPropertyListMethod = propService.getClass().getMethod("getPropertyConfigList", int[].class);
                } catch (Exception ex) {
                    // Ignore
                }
            }

            List<?> configList = null;
            if (getPropertyListMethod != null) {
                if (getPropertyListMethod.getParameterTypes().length == 0) {
                    configList = (List<?>) getPropertyListMethod.invoke(propService);
                } else {
                    configList = (List<?>) getPropertyListMethod.invoke(propService, new Object[]{null});
                }
            }

            int totalProps = (configList != null) ? configList.size() : 0;
            System.out.println("[OK] Jumlah Property Ditemui dari VHAL: " + totalProps + " property.");

            // 4. Siapkan fail output JSON
            File outFile = new File(OUTPUT_FILE);
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8);
            writer.write("{\n");
            writer.write("  \"timestamp\": \"" + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()) + "\",\n");
            writer.write("  \"total_properties\": " + totalProps + ",\n");
            writer.write("  \"properties\": [\n");

            Method getProp = propService.getClass().getMethod("getProperty", int.class, int.class);

            int readSuccessCount = 0;
            int vendorPropCount = 0;

            if (configList != null && !configList.isEmpty()) {
                for (int i = 0; i < configList.size(); i++) {
                    Object cfg = configList.get(i);
                    Class<?> cfgCls = cfg.getClass();

                    int propId = (int) cfgCls.getMethod("getPropertyId").invoke(cfg);
                    String propIdHex = String.format("0x%08X", propId);

                    int access = (int) cfgCls.getMethod("getAccess").invoke(cfg);
                    int changeMode = (int) cfgCls.getMethod("getChangeMode").invoke(cfg);

                    Class<?> propType = (Class<?>) cfgCls.getMethod("getPropertyType").invoke(cfg);
                    String typeName = (propType != null) ? propType.getSimpleName() : "Unknown";

                    boolean isVendor = (propId & 0x20000000) != 0 || propIdHex.startsWith("0x2870");
                    if (isVendor) vendorPropCount++;

                    // Dapatkan area IDs yang disokong (cth: tingkap, pintu, zon suhu)
                    int[] areaIds = null;
                    try {
                        areaIds = (int[]) cfgCls.getMethod("getAreaIds").invoke(cfg);
                    } catch (Exception ignored) {}

                    if (areaIds == null || areaIds.length == 0) {
                        areaIds = new int[]{0};
                    }

                    for (int aIdx = 0; aIdx < areaIds.length; aIdx++) {
                        int areaId = areaIds[aIdx];
                        Object valObj = null;
                        long valTimestamp = 0;
                        int valStatus = -1;

                        try {
                            Object cpv = getProp.invoke(propService, propId, areaId);
                            if (cpv != null) {
                                Class<?> cpvCls = cpv.getClass();
                                valObj = cpvCls.getMethod("getValue").invoke(cpv);
                                try {
                                    valTimestamp = (long) cpvCls.getMethod("getTimestamp").invoke(cpv);
                                    valStatus = (int) cpvCls.getMethod("getStatus").invoke(cpv);
                                } catch (Exception ignored) {}
                                readSuccessCount++;
                            }
                        } catch (Throwable t) {
                            valObj = "ERR: " + t.getMessage();
                        }

                        // Format entri JSON
                        writer.write("    {\n");
                        writer.write("      \"prop_id_dec\": " + propId + ",\n");
                        writer.write("      \"prop_id_hex\": \"" + propIdHex + "\",\n");
                        writer.write("      \"area_id\": " + areaId + ",\n");
                        writer.write("      \"is_vendor\": " + isVendor + ",\n");
                        writer.write("      \"data_type\": \"" + typeName + "\",\n");
                        writer.write("      \"access_mode\": " + access + ",\n");
                        writer.write("      \"change_mode\": " + changeMode + ",\n");
                        writer.write("      \"status\": " + valStatus + ",\n");
                        writer.write("      \"timestamp\": " + valTimestamp + ",\n");
                        writer.write("      \"value\": " + formatJsonValue(valObj) + "\n");
                        writer.write("    }");

                        boolean isLast = (i == configList.size() - 1) && (aIdx == areaIds.length - 1);
                        if (!isLast) writer.write(",");
                        writer.write("\n");
                    }

                    if ((i + 1) % 50 == 0 || (i + 1) == configList.size()) {
                        System.out.println("  [+] Diproses: " + (i + 1) + "/" + configList.size() + " property...");
                    }
                }
            } else {
                System.out.println("[!] getPropertyList() kosong. Menjalankan imbasan imbasan zon Vendor 0x2870xxxx...");
                // Fallback scan vendor range if getPropertyList is not implemented by vendor
                // (ECARX proprietary range 0x28700000 - 0x28700400)
                int[] vendorProbeIds = {
                    678429103, 678429104, 678429461, 678429412, 678429413, 678429414,
                    356517120, 358614275, 678429101, 678428835
                };
                for (int idx = 0; idx < vendorProbeIds.length; idx++) {
                    int p = vendorProbeIds[idx];
                    try {
                        Object cpv = getProp.invoke(propService, p, 0);
                        if (cpv != null) {
                            Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                            writer.write("    {\"prop_id_dec\": " + p + ", \"prop_id_hex\": \"" + String.format("0x%08X", p) + "\", \"value\": " + formatJsonValue(val) + "}");
                            if (idx < vendorProbeIds.length - 1) writer.write(",");
                            writer.write("\n");
                            readSuccessCount++;
                        }
                    } catch (Exception ignored) {}
                }
            }

            writer.write("  ]\n");
            writer.write("}\n");
            writer.close();

            System.out.println("\n==================================================================");
            System.out.println(">> PROSES DUMP SELESAI DENGAN JAYANYA!");
            System.out.println("   * Total Property Disemak : " + totalProps);
            System.out.println("   * Property Vendor (ECARX) : " + vendorPropCount);
            System.out.println("   * Nilai Berjaya Dibaca    : " + readSuccessCount);
            System.out.println("   * Lokasi Fail JSON        : " + OUTPUT_FILE);
            System.out.println("==================================================================");

        } catch (Throwable t) {
            System.err.println("[FAIL] Ralat tidak dijangka:");
            t.printStackTrace();
        }
    }

    private static String formatJsonValue(Object val) {
        if (val == null) return "null";
        if (val instanceof Number || val instanceof Boolean) {
            return val.toString();
        }
        if (val instanceof byte[]) {
            byte[] bytes = (byte[]) val;
            StringBuilder sb = new StringBuilder("\"");
            for (byte b : bytes) {
                sb.append(String.format("%02X ", b));
            }
            sb.append("\"");
            return sb.toString().trim() + "\"";
        }
        if (val instanceof Object[]) {
            Object[] arr = (Object[]) val;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                sb.append(formatJsonValue(arr[i]));
                if (i < arr.length - 1) sb.append(", ");
            }
            sb.append("]");
            return sb.toString();
        }
        // Default escape string
        String s = val.toString().replace("\"", "\\\"").replace("\n", " ");
        return "\"" + s + "\"";
    }
}
