package com.noor.thenoorcar;

/**
 * Decodes only the three identified OEM BYTES battery properties on area 0.
 * Rejects invalid, out-of-range, or corrupted payloads.
 */
public final class BatteryTelemetryDecoder {
    private BatteryTelemetryDecoder() {}

    public static int le16(byte[] bytes) {
        if (bytes == null || bytes.length < 2) return -1;
        return (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8);
    }

    /**
     * SOC: 0x287001AF (678429103)
     * Range: 0..1000 -> 0.0% .. 100.0%
     */
    public static Double soc(byte[] bytes) {
        int raw = le16(bytes);
        return (raw >= 0 && raw <= 1000) ? Math.round((raw / 10.0) * 10.0) / 10.0 : null;
    }

    /**
     * Voltage: 0x28700315 (678429461)
     * Range: 1000..10000 -> 100.0V .. 1000.0V
     */
    public static Double voltage(byte[] bytes) {
        int raw = le16(bytes);
        return (raw > 1000 && raw < 10000) ? Math.round((raw / 10.0) * 10.0) / 10.0 : null;
    }

    /**
     * Current: 0x287001B0 (678429104)
     * Offset 5000: 0..10000 -> -500.0A .. +500.0A
     */
    public static Double current(byte[] bytes) {
        int raw = le16(bytes);
        return (raw > 0 && raw < 10000) ? Math.round(((raw - 5000) / 10.0) * 10.0) / 10.0 : null;
    }

    /**
     * Power (kW) = Voltage (V) * Current (A) / 1000.0
     */
    public static Double powerKw(Double volts, Double amps) {
        if (volts == null || amps == null) return null;
        return Math.round((volts * amps / 1000.0) * 100.0) / 100.0;
    }
}
