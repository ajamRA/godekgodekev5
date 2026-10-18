package com.emas.window;

/** No polling, retries or desired-state enforcement. Success means dispatch, not ECU confirmation. */
public final class SafeVehicleCommands {
    public interface Writer { boolean write(int property, int area, Object value); }
    private final Writer writer;
    private final boolean candidateContractsVerified;
    public SafeVehicleCommands(Writer writer, boolean candidateContractsVerified) {
        this.writer = writer;
        this.candidateContractsVerified = candidateContractsVerified;
    }
    public boolean regen(int level) { return oneByte(0x287001B1, level, 1, 3, true); }
    // UNVERIFIED e.MAS 5 candidate: Comfort=1, Sport=2, Eco=3, Eco Plus=4.
    public boolean driveMode(int mode) { return oneByte(0x287000EA, mode, 1, 4, candidateContractsVerified); }
    public boolean brakePedal(int mode) { return oneByte(0x287002D8, mode, 1, 2, candidateContractsVerified); }
    public boolean comfortBrake(boolean on) { return oneByte(0x287002D5, on ? 1 : 0, 0, 1, candidateContractsVerified); }
    private boolean oneByte(int property, int value, int min, int max, boolean verified) {
        if (value < min || value > max || !verified) return false;
        return writer.write(property, 0, new byte[]{(byte) value});
    }
    /** Conservative build policy: nonzero-area writes are rejected, never redirected. */
    public static boolean validWrite(int property, int area, Object value) {
        if (area != 0 || value == null) return false;
        switch (property & 0x00ff0000) {
            case 0x00700000: return value instanceof byte[] && ((byte[]) value).length > 0;
            case 0x00400000: return value instanceof Integer;
            case 0x00600000: return value instanceof Float && Float.isFinite((Float) value);
            case 0x00200000: return value instanceof Boolean;
            default: return false;
        }
    }
}
