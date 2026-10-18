package com.emas.door;

import java.lang.reflect.Method;

/** Read-only OEM door adapter. One reconnect attempt per second, never one per failed door. */
public final class DoorVhalReader implements DoorStateHub.Reader {
    private static final int[] PROPS = {678428815, 678428816, 678428817, 678428818};
    private final DoorStateHub.Clock clock;
    private Object service;
    private Method getProperty;
    private long nextConnectMs;
    public DoorVhalReader(DoorStateHub.Clock clock) { this.clock = clock; }
    @Override public DoorSnapshot.State read(int door) {
        if (door < 0 || door >= 4) throw new IllegalArgumentException("door");
        if (service == null && !connect()) return DoorSnapshot.State.UNKNOWN;
        try { return decode(getProperty.invoke(service, PROPS[door], 0)); }
        catch (Exception e) {
            service = null; getProperty = null; nextConnectMs = clock.elapsedMillis() + 1000;
            return DoorSnapshot.State.UNKNOWN;
        }
    }
    private boolean connect() {
        long now = clock.elapsedMillis();
        if (now < nextConnectMs) return false;
        nextConnectMs = now + 1000;
        try {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "car_service");
            if (binder == null) return false;
            Class<?> ibinder = Class.forName("android.os.IBinder");
            Object car = Class.forName("android.car.ICar$Stub").getMethod("asInterface", ibinder).invoke(null, binder);
            Object propBinder = car.getClass().getMethod("getCarService", String.class).invoke(car, "property");
            Object candidate = Class.forName("android.car.hardware.property.ICarProperty$Stub").getMethod("asInterface", ibinder).invoke(null, propBinder);
            Method method = candidate.getClass().getMethod("getProperty", int.class, int.class);
            service = candidate; getProperty = method;
            return true;
        } catch (Exception e) { return false; }
    }
    public static DoorSnapshot.State decode(Object property) {
        if (property == null) return DoorSnapshot.State.UNKNOWN;
        try {
            Object status = property.getClass().getMethod("getStatus").invoke(property);
            if (!(status instanceof Integer) || ((Integer) status) != 0) return DoorSnapshot.State.UNKNOWN;
            Object value = property.getClass().getMethod("getValue").invoke(property);
            int raw;
            if (value instanceof Integer) raw = (Integer) value;
            else if (value instanceof byte[] && ((byte[]) value).length == 1) raw = ((byte[]) value)[0] & 255;
            else return DoorSnapshot.State.UNKNOWN;
            return raw == 0 ? DoorSnapshot.State.OPEN : raw == 1 ? DoorSnapshot.State.CLOSED : DoorSnapshot.State.UNKNOWN;
        } catch (Exception e) { return DoorSnapshot.State.UNKNOWN; }
    }
}
