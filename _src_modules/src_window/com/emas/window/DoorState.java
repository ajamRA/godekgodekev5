package com.emas.window;

/**
 * Three-state Door Model for Proton e.MAS 5 (IHU801P).
 * - OPEN: Door physically unlatched (value 0).
 * - CLOSED: Door physically latched/closed (value 1).
 * - UNKNOWN: VHAL error, disconnect, reading failure, or invalid value.
 *
 * CRITICAL RULE: Transitions involving UNKNOWN never trigger chimes or window operations.
 */
public enum DoorState {
    OPEN,
    CLOSED,
    UNKNOWN;

    public static DoorState fromVhalValue(int value) {
        if (value == 0) return OPEN;
        if (value == 1) return CLOSED;
        return UNKNOWN;
    }

    public static DoorState fromBoolean(Boolean b) {
        if (b == null) return UNKNOWN;
        return b ? OPEN : CLOSED;
    }
}
