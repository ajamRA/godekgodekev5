package com.emas.evstest;

import android.os.IBinder;
import android.os.Parcel;

public class TestEvs {
    public static void main(String[] args) {
        System.out.println("=== TESTING BDSTAR.RENDER.ENGINE ===");
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "bdstar.render.engine");
            if (binder == null) {
                System.out.println("FAILED: bdstar.render.engine is NULL!");
                return;
            }
            System.out.println("SUCCESS: bdstar.render.engine binder found: " + binder);
            System.out.println("Binder alive: " + binder.isBinderAlive());

            // Test openCamera("avm")
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            data.writeInterfaceToken("bdstar.render.engine");
            
            // C-string "avm"
            byte[] b = "avm".getBytes("UTF-8");
            byte[] z = new byte[((b.length + 1 + 3) / 4) * 4];
            System.arraycopy(b, 0, z, 0, b.length);
            for (int i = 0; i < z.length; i += 4) {
                data.writeInt((z[i] & 0xFF) | ((z[i + 1] & 0xFF) << 8) | ((z[i + 2] & 0xFF) << 16) | ((z[i + 3] & 0xFF) << 24));
            }

            boolean ok = binder.transact(1, data, reply, 0);
            System.out.println("openCamera('avm') transaction result: " + ok);
            data.recycle();
            reply.recycle();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}