package com.emas.test;

import android.content.Context;
import java.lang.reflect.Method;

public class TestAdaptApiHvac {
    public static void main(String[] args) {
        System.out.println("=== TESTING ADAPTAPI HVAC ===");
        try {
            if (android.os.Looper.getMainLooper() == null) {
                android.os.Looper.prepareMainLooper();
            }
            // Get system context via ActivityThread
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method currentAT = atCls.getMethod("currentActivityThread");
            Object at = currentAT.invoke(null);
            if (at == null) {
                Method systemMain = atCls.getMethod("systemMain");
                at = systemMain.invoke(null);
            }
            Method getSystemContext = at.getClass().getMethod("getSystemContext");
            Context context = (Context) getSystemContext.invoke(at);
            System.out.println("Context: " + context);

            Class<?> hvacCls = Class.forName("com.malaysia.xui.adaptapi.hvac.Hvac");
            System.out.println("Hvac class found: " + hvacCls);
            Method create = hvacCls.getMethod("create", Context.class);
            Object hvac = create.invoke(null, context);
            System.out.println("Hvac instance: " + hvac);

            if (hvac != null) {
                // Check if connectable
                Class<?> connCls = Class.forName("com.malaysia.xui.adaptapi.binder.IConnectable");
                if (connCls.isInstance(hvac)) {
                    System.out.println("Connecting HVAC...");
                    connCls.getMethod("connect").invoke(hvac);
                    Thread.sleep(1000);
                }

                // Get current state
                Method getState = hvac.getClass().getMethod("getHvacState");
                Object state = getState.invoke(hvac);
                System.out.println("HvacState: " + state);

                if (state != null) {
                    try {
                        Method getLeftTemp = state.getClass().getMethod("getLeftAreaTemperature");
                        System.out.println("Current Left Temp: " + getLeftTemp.invoke(state));
                    } catch (Throwable t) {
                        System.out.println("getLeftTemp err: " + t);
                    }
                    try {
                        Method getBlowing = state.getClass().getMethod("getBlowingLevel");
                        System.out.println("Current Blowing Level: " + getBlowing.invoke(state));
                    } catch (Throwable t) {
                        System.out.println("getBlowing err: " + t);
                    }
                    try {
                        Method isHvacOn = state.getClass().getMethod("isHvacOn");
                        System.out.println("isHvacOn: " + isHvacOn.invoke(state));
                    } catch (Throwable t) {
                        System.out.println("isHvacOn err: " + t);
                    }
                }

                // If args provided, set temperature and fan!
                if (args.length > 0) {
                    double targetTemp = Double.parseDouble(args[0]);
                    System.out.println("Setting Left Temp to " + targetTemp + "...");
                    Method setLeftTemp = hvac.getClass().getMethod("setLeftAreaTemperature", double.class);
                    Object res = setLeftTemp.invoke(hvac, targetTemp);
                    System.out.println("setLeftAreaTemperature result: " + res);
                }

                if (args.length > 1) {
                    int fan = Integer.parseInt(args[1]);
                    System.out.println("Setting Fan Speed to " + fan + "...");
                    Method setBlowing = hvac.getClass().getMethod("setBlowingLevel", int.class);
                    Object res = setBlowing.invoke(hvac, fan);
                    System.out.println("setBlowingLevel result: " + res);
                }
            }
            System.out.println("TestAdaptApiHvac finished.");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
