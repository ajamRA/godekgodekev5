package com.emas.probe;

import android.os.IBinder;
import android.os.ServiceManager;
import java.lang.reflect.Method;

public class ProbeInput {
    public static void main(String[] args) {
        System.out.println("=== PROBING INPUT DISPATCHER SERVICES ===");
        probeService("EcarxInputDispatcherService");
        probeService("MultiGestureMonitor");
        probeService("car_service");
        probeService("input");
    }

    private static void probeService(String name) {
        try {
            IBinder binder = ServiceManager.getService(name);
            if (binder == null) {
                System.out.println("Service " + name + ": NULL");
                return;
            }
            System.out.println("\n--- Service " + name + " (Interface: " + binder.getInterfaceDescriptor() + ") ---");
            Class<?> stubClass = null;
            String desc = binder.getInterfaceDescriptor();
            if (desc != null) {
                try {
                    stubClass = Class.forName(desc + "$Stub");
                } catch (Exception e) {
                    try {
                        stubClass = Class.forName(desc);
                    } catch (Exception ignored) {}
                }
            }
            if (stubClass != null) {
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                Object iface = asInterface.invoke(null, binder);
                for (Method m : iface.getClass().getMethods()) {
                    if (m.getDeclaringClass() != Object.class) {
                        System.out.println("  Method: " + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()) + " -> " + m.getReturnType().getSimpleName());
                    }
                }
            } else {
                for (Method m : binder.getClass().getMethods()) {
                    if (m.getDeclaringClass() != Object.class) {
                        System.out.println("  Binder Method: " + m.getName() + " " + java.util.Arrays.toString(m.getParameterTypes()));
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("Error probing " + name + ": " + t.getMessage());
        }
    }
}
