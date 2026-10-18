package com.emas.test;

import java.lang.reflect.Method;

public class TestAdaptApiSensors {
    public static void main(String[] args) {
        System.out.println("=== TESTING ADAPTAPI ISENSOR ===");
        try {
            Class<?> sensorManagerClass = Class.forName("com.malaysia.xui.adaptapi.vehicle.sensor.Sensor");
            // Or get through Car/AdaptAPI
            Class<?> adaptApiClass = null;
            try {
                adaptApiClass = Class.forName("com.malaysia.xui.adaptapi.car.Car");
            } catch (Throwable t) {
                try {
                    adaptApiClass = Class.forName("com.ecarx.xui.adaptapi.car.Car");
                } catch (Throwable t2) {}
            }

            System.out.println("AdaptAPI class: " + adaptApiClass);

            // Let's also check all VHAL properties returned by CarPropertyService getPropertyList
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            Method getService = smCls.getMethod("getService", String.class);
            Object binder = getService.invoke(null, "car_service");
            Class<?> stub = Class.forName("android.car.ICar$Stub");
            Object carService = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            Method getCarService = carService.getClass().getMethod("getCarService", String.class);
            Object propBinder = getCarService.invoke(carService, "property");
            Class<?> propStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            Object propService = propStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);
            Method getPropList = propService.getClass().getMethod("getPropertyList");
            
            java.util.List<?> list = (java.util.List<?>) getPropList.invoke(propService);
            System.out.println("Total CarPropertyConfig supported by VHAL: " + (list != null ? list.size() : 0));
            if (list != null) {
                for (Object cfg : list) {
                    Method getPropertyId = cfg.getClass().getMethod("getPropertyId");
                    int pid = (int) getPropertyId.invoke(cfg);
                    String hex = "0x" + Integer.toHexString(pid);
                    
                    // Filter HVAC or Temperature or Sensor related
                    // 0x15... (HVAC), 0x11... (ENV/SENSORS), 0x28... (VENDOR MCU)
                    if ((pid & 0x10000000) != 0 || (pid & 0x20000000) != 0) {
                        Method getAreaType = cfg.getClass().getMethod("getAreaType");
                        Object areaType = getAreaType.invoke(cfg);
                        System.out.println("  Prop: " + pid + " (" + hex + "), AreaType: " + areaType);
                    }
                }
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
