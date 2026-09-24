package com.emas.camprobe;

import java.lang.reflect.Method;

public class CameraProbe {
    public static void main(String[] args) {
        System.out.println("=== Testing CONFIG_VEHI_DVR Property Values ===");
        try {
            Class<?> vc = Class.forName("ecarx.config.ECarXVehicleConfig");
            Method m = vc.getMethod("getVehicleConfig", int.class);

            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method spGet = sp.getMethod("get", String.class);
            Method spSet = sp.getMethod("set", String.class, String.class);

            String orig = (String) spGet.invoke(null, "persist.sys.vehi.config");
            System.out.println("Original: " + orig);
            System.out.println("Original CONFIG_VEHI_DVR: " + m.invoke(null, 16430));

            // Test each bit in Byte 9
            for (int bit = 0; bit < 8; bit++) {
                int val_hex = 1 << bit;
                String[] parts = orig.split("\\|");
                parts[9] = String.format("0X%x", val_hex);
                String test = String.join("|", parts);
                spSet.invoke(null, "persist.sys.vehi.config", test);
                int val = (int) m.invoke(null, 16430);
                System.out.println("Byte 9 bit " + bit + " (0X" + Integer.toHexString(val_hex) + "): CONFIG_VEHI_DVR = " + val);
            }

            // Restore original
            spSet.invoke(null, "persist.sys.vehi.config", orig);
            System.out.println("Restored original: CONFIG_VEHI_DVR = " + m.invoke(null, 16430));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
