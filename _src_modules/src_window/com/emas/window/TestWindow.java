package com.emas.window;

import java.lang.reflect.Method;
import android.content.Context;

public class TestWindow {
    public static void main(String[] args) {
        System.out.println("=== ACO/PROTON WINDOW CONTROL HARNESS ===");
        try {
            int targetWindow = 816; // All 4 windows by default
            int targetPercent = 10; // 10% default
            if (args.length > 0) {
                targetWindow = Integer.parseInt(args[0]);
            }
            if (args.length > 1) {
                targetPercent = Integer.parseInt(args[1]);
            }

            System.out.println("Target Window Mask: " + targetWindow + " (16=FL, 32=FR, 256=RL, 512=RR, 816=ALL)");
            System.out.println("Target Position: " + targetPercent + "%");

            // 1. Prepare Main Looper so Looper.getMainLooper() is initialized
            if (android.os.Looper.getMainLooper() == null) {
                android.os.Looper.prepareMainLooper();
            }
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object activityThread = atClass.getMethod("systemMain").invoke(null);
            Context ctx = (Context) atClass.getMethod("getSystemContext").invoke(activityThread);
            System.out.println("System Context: " + ctx);

            // 2. Instantiate VehicleImpl directly
            Class<?> vehicleImplClass = Class.forName("com.malaysia.xui.adaptapi.vehicle.impl.VehicleImpl");
            java.lang.reflect.Constructor<?> ctor = vehicleImplClass.getConstructor(Context.class);
            Object vehicle = null;
            try {
                vehicle = ctor.newInstance(ctx);
                System.out.println("Vehicle instance: " + vehicle);
            } catch (java.lang.reflect.InvocationTargetException e) {
                System.out.println("VehicleImpl instantiation failed: " + e.getCause());
                if (e.getCause() != null) {
                    e.getCause().printStackTrace(System.out);
                }
                return;
            }

            if (vehicle == null) {
                System.out.println("ERROR: Vehicle.create returned null!");
                return;
            }

            // Wait 500ms for car service binder connection
            Thread.sleep(800);

            // 3. vehicle.getBcm()
            Object bcm = vehicle.getClass().getMethod("getBcm").invoke(vehicle);
            System.out.println("BCM instance: " + bcm);
            if (bcm == null) {
                System.out.println("ERROR: getBcm returned null!");
                return;
            }

            // 4. bcm.getDoorAndWindowState()
            Object doorAndWindow = bcm.getClass().getMethod("getDoorAndWindowState").invoke(bcm);
            System.out.println("DoorAndWindow instance: " + doorAndWindow);
            if (doorAndWindow == null) {
                System.out.println("ERROR: getDoorAndWindowState returned null!");
                return;
            }

            // 5. Query current status
            try {
                Method getWinStatus = doorAndWindow.getClass().getMethod("getWindowStatus", int.class);
                System.out.println("FL (16) status: " + getWinStatus.invoke(doorAndWindow, 16));
                System.out.println("FR (32) status: " + getWinStatus.invoke(doorAndWindow, 32));
                System.out.println("RL (256) status: " + getWinStatus.invoke(doorAndWindow, 256));
                System.out.println("RR (512) status: " + getWinStatus.invoke(doorAndWindow, 512));
            } catch (Throwable t) {
                System.out.println("getWindowStatus query error: " + t);
            }

            // 6. Call setWindowStatus(int window, int percent)
            System.out.println("Calling setWindowStatus(" + targetWindow + ", " + targetPercent + ")...");
            Method setWinStatus = doorAndWindow.getClass().getMethod("setWindowStatus", int.class, int.class);
            Object res = setWinStatus.invoke(doorAndWindow, targetWindow, targetPercent);
            System.out.println("setWindowStatus returned: " + res);

            System.out.println("=== EXECUTION COMPLETE ===");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
