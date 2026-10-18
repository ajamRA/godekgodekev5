package com.emas.telemetry;

import org.json.JSONObject;

/**
 * Smart Climate Controller for Proton e.MAS 5 (IHU801P).
 *
 * Implements:
 * 1. Mode QUICK_COOL: Timed cooldown profile for hot cabin entries
 *    - Phase 1 (0-3 min): Fan 6, Temp 19.0°C (raw 4)
 *    - Phase 2 (3-7 min): Fan 4, Temp 21.0°C (raw 8)
 *    - Phase 3 (>7 min): Fan 2, Temp 22.0°C (raw 10)
 * 2. Mode COMFORT: Steady cruising profile (Fan 2, Temp 22.0°C)
 * 3. Mode ADAPTIVE: Ambient temperature-driven adjustments
 * 4. Driver Override Guard: Automatically detects when driver manually touches
 *    the OEM HVAC screen sliders, pausing auto adjustments for 10 minutes.
 * 5. Single Master Execution: Integrates into TelemetryDaemon polling loop,
 *    eliminating race conditions and multiple-writer collisions.
 */
public final class SmartClimateController {

    public interface HvacActuator {
        boolean setFanSpeed(int fanLevel);
        boolean setDriverTemp(double targetTemp);
        boolean setPower(boolean on);
    }

    public enum Mode {
        OFF,
        QUICK_COOL,
        COMFORT,
        ADAPTIVE;

        public static Mode fromString(String str) {
            if (str == null) return OFF;
            String s = str.trim().toUpperCase();
            if (s.equals("QUICK_COOL") || s.equals("QUICK") || s.equals("COOL")) return QUICK_COOL;
            if (s.equals("COMFORT") || s.equals("CRUISE")) return COMFORT;
            if (s.equals("ADAPTIVE") || s.equals("AUTO")) return ADAPTIVE;
            return OFF;
        }
    }

    private final HvacActuator actuator;
    private Mode currentMode = Mode.OFF;
    private boolean enabled = false;

    private long modeStartTimeMs = 0;
    private long lastAppliedTimeMs = 0;
    private int lastAppliedFan = -1;
    private double lastAppliedTemp = -1.0;

    // Driver Override Guard state
    private long overridePausedUntilMs = 0;
    private static final long OVERRIDE_HOLD_MS = 10 * 60 * 1000L; // 10 minutes
    private static final long MIN_STEP_INTERVAL_MS = 15 * 1000L; // min 15s between steps

    public SmartClimateController(HvacActuator actuator) {
        this.actuator = actuator;
    }

    public synchronized void setMode(Mode mode, boolean enable) {
        this.currentMode = mode;
        this.enabled = enable && (mode != Mode.OFF);
        if (this.enabled) {
            this.modeStartTimeMs = System.currentTimeMillis();
            this.overridePausedUntilMs = 0; // clear override on explicit user mode toggle
            this.lastAppliedTimeMs = 0;
            this.lastAppliedFan = -1;
            this.lastAppliedTemp = -1.0;
            System.out.println("[SmartClimateController] Activated mode=" + mode);
        } else {
            System.out.println("[SmartClimateController] Disabled");
        }
    }

    public synchronized void tick(int currentFan, double currentTempDrv, double ambientTemp, boolean hvacPwr, String gear, double speedKmh) {
        if (!enabled || currentMode == Mode.OFF) return;
        long now = System.currentTimeMillis();

        // Check if driver override is active
        if (now < overridePausedUntilMs) {
            return;
        }

        // Driver Override Detection:
        // If we previously applied a value, and at least 4 seconds have passed (grace period for MCU reflection),
        // and current observed MCU values differ from what we set -> user touched OEM screen!
        if (lastAppliedTimeMs > 0 && (now - lastAppliedTimeMs > 4000)) {
            boolean fanMismatched = (lastAppliedFan > 0 && currentFan > 0 && currentFan != lastAppliedFan);
            boolean tempMismatched = (lastAppliedTemp > 0 && Math.abs(currentTempDrv - lastAppliedTemp) >= 0.5);
            if (fanMismatched || tempMismatched) {
                overridePausedUntilMs = now + OVERRIDE_HOLD_MS;
                System.out.println("[SmartClimateController] Driver manual override detected! (Observed fan=" 
                    + currentFan + " vs expected=" + lastAppliedFan + ", temp=" + currentTempDrv + " vs " + lastAppliedTemp 
                    + "). Pausing auto for 10 min.");
                return;
            }
        }

        // Must not command changes too frequently
        if (lastAppliedTimeMs > 0 && (now - lastAppliedTimeMs < MIN_STEP_INTERVAL_MS)) {
            return;
        }

        // Calculate targets based on current mode
        int targetFan = currentFan;
        double targetTemp = currentTempDrv;

        if (currentMode == Mode.QUICK_COOL) {
            long elapsedSec = (now - modeStartTimeMs) / 1000;
            if (elapsedSec < 180) { // 0 - 3 min
                targetFan = 6;
                targetTemp = 19.0;
            } else if (elapsedSec < 420) { // 3 - 7 min
                targetFan = 4;
                targetTemp = 21.0;
            } else { // > 7 min
                targetFan = 2;
                targetTemp = 22.0;
            }
        } else if (currentMode == Mode.COMFORT) {
            targetFan = 2;
            targetTemp = 22.0;
        } else if (currentMode == Mode.ADAPTIVE) {
            if (ambientTemp >= 33.0) {
                targetFan = 4;
                targetTemp = 20.0;
            } else if (ambientTemp >= 28.0) {
                targetFan = 3;
                targetTemp = 21.5;
            } else if (ambientTemp >= 24.0) {
                targetFan = 2;
                targetTemp = 22.5;
            } else {
                targetFan = 1;
                targetTemp = 23.5;
            }
        }

        // Apply targets if needed
        boolean fanChanged = (targetFan > 0 && targetFan != currentFan);
        boolean tempChanged = (targetTemp > 0 && Math.abs(targetTemp - currentTempDrv) >= 0.5);

        if (fanChanged || tempChanged) {
            if (actuator != null) {
                if (fanChanged) actuator.setFanSpeed(targetFan);
                if (tempChanged) actuator.setDriverTemp(targetTemp);
                lastAppliedFan = targetFan;
                lastAppliedTemp = targetTemp;
                lastAppliedTimeMs = now;
                System.out.println("[SmartClimateController] Applied target: fan=" + targetFan + ", temp=" + targetTemp + "°C (mode=" + currentMode + ")");
            }
        }
    }

    public synchronized JSONObject getStatusJson(int currentFan, double currentTempDrv, double ambientTemp) {
        long now = System.currentTimeMillis();
        boolean isOverride = (now < overridePausedUntilMs);
        long overrideSecRemaining = isOverride ? (overridePausedUntilMs - now) / 1000 : 0;
        long elapsedSec = (enabled && modeStartTimeMs > 0) ? (now - modeStartTimeMs) / 1000 : 0;

        JSONObject j = new JSONObject();
        try {
            j.put("ok", true);
            j.put("enabled", enabled);
            j.put("mode", currentMode.name().toLowerCase());
            j.put("state", isOverride ? "override_paused" : (enabled ? "active" : "off"));
            j.put("elapsed_sec", elapsedSec);
            j.put("current_fan", currentFan);
            j.put("current_temp", currentTempDrv);
            j.put("ambient_temp", ambientTemp);
            j.put("override_paused", isOverride);
            j.put("override_remaining_sec", overrideSecRemaining);
        } catch (Throwable ignored) {}
        return j;
    }

    public synchronized void resetOverride() {
        this.overridePausedUntilMs = 0;
    }

    public Mode getMode() { return currentMode; }
    public boolean isEnabled() { return enabled; }
}
