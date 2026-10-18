package com.noor.thenoorcar;

import java.io.IOException;
import java.lang.reflect.Method;
import org.json.JSONObject;

/**
 * Strictly In-Process Read-Only VHAL Telemetry Reader for Proton e.MAS 5.
 * Reads ONLY identified battery properties (SOC 0x287001AF, Volts 0x28700315, Amps 0x287001B0) on area 0.
 * Contains no setters, no write calls, no sockets, and no daemon dependency.
 */
public final class ReadOnlyTelemetry {
    public static final int PROP_SOC     = 0x287001AF; // 678429103
    public static final int PROP_VOLTAGE = 0x28700315; // 678429461
    public static final int PROP_CURRENT = 0x287001B0; // 678429104
    public static final int AREA_ZERO    = 0;

    private static volatile boolean hiddenApiExemptDone = false;
    private static volatile Object cachedPropertyService = null;
    private static volatile Method cachedGetPropertyMethod = null;

    private ReadOnlyTelemetry() {}

    /** Pluggable reader interface for laptop mock testing and unit tests. */
    public interface PropertyReader {
        byte[] readBytes(int propId, int areaId);
    }

    /** Ensure Android 9/10/11 non-SDK hidden API restrictions are bypassed for this process. */
    private static void ensureHiddenApiExempt() {
        if (hiddenApiExemptDone) return;
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", (Class[]) null);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", new Class<?>[]{String[].class});
            Object vmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(vmRuntime, new Object[]{new String[]{"L"}});
            hiddenApiExemptDone = true;
        } catch (Throwable ignored) {
            hiddenApiExemptDone = true;
        }
    }

    /** Connect to CarPropertyService via CarService reflection. */
    private static synchronized Method getCarPropertyGetter() throws Exception {
        if (cachedGetPropertyMethod != null && cachedPropertyService != null) {
            return cachedGetPropertyMethod;
        }

        ensureHiddenApiExempt();

        Class<?> binderClass = Class.forName("android.os.IBinder");
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Object binder = serviceManager.getMethod("getService", String.class).invoke(null, "car_service");
        android.util.Log.d("ReadOnlyTelemetry", "car_service binder: " + binder);
        if (binder == null) throw new IOException("car_service unavailable");

        Object car = Class.forName("android.car.ICar$Stub").getMethod("asInterface", binderClass).invoke(null, binder);
        android.util.Log.d("ReadOnlyTelemetry", "ICar interface: " + car);
        if (car == null) throw new IOException("ICar interface unavailable");

        Object propertyBinder = car.getClass().getMethod("getCarService", String.class).invoke(car, "property");
        android.util.Log.d("ReadOnlyTelemetry", "propertyBinder: " + propertyBinder);
        if (propertyBinder == null) throw new IOException("property binder unavailable");

        Object propertyService = Class.forName("android.car.hardware.property.ICarProperty$Stub")
                .getMethod("asInterface", binderClass).invoke(null, propertyBinder);
        android.util.Log.d("ReadOnlyTelemetry", "propertyService: " + propertyService);
        if (propertyService == null) throw new IOException("ICarProperty service unavailable");

        Method getter = null;
        for (Method m : propertyService.getClass().getMethods()) {
            if ("getProperty".equals(m.getName()) && m.getParameterTypes().length == 2
                    && m.getParameterTypes()[0] == int.class && m.getParameterTypes()[1] == int.class) {
                getter = m;
                break;
            }
        }
        if (getter == null) throw new IOException("getProperty(int, int) method not found");

        cachedPropertyService = propertyService;
        cachedGetPropertyMethod = getter;
        return getter;
    }

    /** Default in-process VHAL reader accessing CarPropertyService on area 0. */
    private static class InProcessVhalReader implements PropertyReader {
        @Override
        public byte[] readBytes(int propId, int areaId) {
            if (areaId != 0) return null; // Area MUST be 0 only
            try {
                Method getter = getCarPropertyGetter();
                Object cpv = getter.invoke(cachedPropertyService, propId, 0);
                if (cpv == null) {
                    android.util.Log.w("ReadOnlyTelemetry", "cpv is null for prop 0x" + Integer.toHexString(propId));
                    return null;
                }
                Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                if (val instanceof byte[]) {
                    byte[] bytes = (byte[]) val;
                    android.util.Log.i("ReadOnlyTelemetry", "Read prop 0x" + Integer.toHexString(propId) + " (" + bytes.length + " bytes)");
                    return bytes;
                } else {
                    android.util.Log.w("ReadOnlyTelemetry", "prop 0x" + Integer.toHexString(propId) + " val is " + (val == null ? "null" : val.getClass().getName()));
                    return null;
                }
            } catch (Throwable e) {
                android.util.Log.e("ReadOnlyTelemetry", "readBytes failed for prop 0x" + Integer.toHexString(propId), e);
                return null;
            }
        }
    }

    public static final int PROP_EV_BATTERY_LEVEL = 0x11600201; // 291504641
    public static final int PROP_INFO_EV_BATTERY_CAPACITY = 0x11400100; // 289407232

    public static Object readRawProperty(int propId, int areaId) {
        if (areaId != 0) return null;
        try {
            Method getter = getCarPropertyGetter();
            Object cpv = getter.invoke(cachedPropertyService, propId, 0);
            if (cpv == null) {
                android.util.Log.d("ReadOnlyTelemetry", "cpv is null for prop 0x" + Integer.toHexString(propId));
                return null;
            }
            Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
            android.util.Log.i("ReadOnlyTelemetry", "Prop 0x" + Integer.toHexString(propId) + " val=" + val + " (" + (val == null ? "null" : val.getClass().getName()) + ")");
            return val;
        } catch (Throwable e) {
            android.util.Log.w("ReadOnlyTelemetry", "readRawProperty failed for 0x" + Integer.toHexString(propId) + ": " + e.getMessage());
            return null;
        }
    }

    private static final PropertyReader DEFAULT_READER = new InProcessVhalReader();

    /** Read battery telemetry using the default in-process VHAL reader. */
    public static JSONObject read() throws Exception {
        return read(DEFAULT_READER);
    }

    /** Read and decode battery telemetry using any given reader (for mock testing or live VHAL). */
    public static JSONObject read(PropertyReader reader) throws Exception {
        if (reader == null) throw new IOException("reader unavailable");

        byte[] rawSoc = reader.readBytes(PROP_SOC, AREA_ZERO);
        byte[] rawVolts = reader.readBytes(PROP_VOLTAGE, AREA_ZERO);
        byte[] rawAmps = reader.readBytes(PROP_CURRENT, AREA_ZERO);

        Double soc = BatteryTelemetryDecoder.soc(rawSoc);
        if (soc == null) {
            // Option A: Fallback to standard Android Automotive CAR_ENERGY property (EV_BATTERY_LEVEL)
            Object stdVal = readRawProperty(PROP_EV_BATTERY_LEVEL, AREA_ZERO);
            if (stdVal instanceof Number) {
                double level = ((Number) stdVal).doubleValue();
                Object capVal = readRawProperty(PROP_INFO_EV_BATTERY_CAPACITY, AREA_ZERO);
                if (capVal instanceof Number && ((Number) capVal).doubleValue() > 0) {
                    double cap = ((Number) capVal).doubleValue();
                    double pct = (level / cap) * 100.0;
                    if (pct >= 0.0 && pct <= 100.0) {
                        soc = Math.round(pct * 10.0) / 10.0;
                    }
                } else if (level >= 0.0 && level <= 100.0) {
                    soc = Math.round(level * 10.0) / 10.0;
                }
            }
        }
        Double volts = BatteryTelemetryDecoder.voltage(rawVolts);
        Double amps = BatteryTelemetryDecoder.current(rawAmps);
        Double power = BatteryTelemetryDecoder.powerKw(volts, amps);

        if (soc == null && volts == null && amps == null) {
            throw new IOException("battery readings unavailable");
        }

        JSONObject battery = new JSONObject();
        JSONObject result = new JSONObject();

        if (soc != null) {
            battery.put("soc_pct", soc);
            result.put("soc_pct", soc);
        }
        if (volts != null) {
            battery.put("voltage_v", volts);
            result.put("voltage_v", volts);
        }
        if (amps != null) {
            battery.put("current_a", amps);
            result.put("current_a", amps);
        }
        if (power != null) {
            battery.put("power_kw", power);
            result.put("power_kw", power);
        }

        result.put("source", "ihu_vhal_read_only");
        result.put("battery", battery);
        result.put("observed_at_ms", System.currentTimeMillis());
        result.put("ok", true);
        return result;
    }
}
