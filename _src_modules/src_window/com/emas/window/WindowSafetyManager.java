package com.emas.window;

/**
 * Manages safety leases for window pressure relief and ventilation sessions.
 * 
 * Rules:
 * 1. Relief crack only activates if window was originally closed (pos <= maxClosedPos, default 2%).
 * 2. Only a window opened by this specific relief session may be automatically closed.
 * 3. If window was manually moved, sensor went UNKNOWN, or daemon restarted -> lease is canceled.
 * 4. Ventilation session only closes windows cracked by that ventilation session.
 */
public final class WindowSafetyManager {
    public static final int MAX_CLOSED_POS = 2; // Tolerance for "fully closed"
    public static final int CRACK_TOLERANCE = 4; // Tolerance for target crack (e.g. 7% + 4% = 11%)

    public static class WindowSession {
        public final int index;
        public final long sessionId;
        public final int targetPercent;
        public final long openedTimestamp;
        public boolean active;

        public WindowSession(int index, long sessionId, int targetPercent, long now) {
            this.index = index;
            this.sessionId = sessionId;
            this.targetPercent = targetPercent;
            this.openedTimestamp = now;
            this.active = true;
        }
    }

    private final WindowSession[] reliefSessions = new WindowSession[4];
    private final boolean[] ventilationOwnership = new boolean[4];
    private long sessionCounter = 0;

    public synchronized WindowSession tryOpenRelief(int windowIndex, int currentPos, int targetCrack, long now) {
        if (windowIndex < 0 || windowIndex >= 4) return null;
        // Safety check 1: Only allow relief if window was fully closed originally
        if (currentPos < 0 || currentPos > MAX_CLOSED_POS) {
            return null; // Window already partially open (user cracked it manual), do NOT take ownership
        }
        WindowSession s = new WindowSession(windowIndex, ++sessionCounter, targetCrack, now);
        reliefSessions[windowIndex] = s;
        return s;
    }

    public synchronized boolean canAutoCloseRelief(int windowIndex, int currentPos) {
        if (windowIndex < 0 || windowIndex >= 4) return false;
        WindowSession s = reliefSessions[windowIndex];
        if (s == null || !s.active) return false;

        // Safety check 2: Only close if current position is within target crack tolerance
        int maxAllowed = s.targetPercent + CRACK_TOLERANCE;
        if (currentPos < 0 || currentPos > maxAllowed) {
            // User opened window further (e.g. to 30%), cancel session!
            s.active = false;
            reliefSessions[windowIndex] = null;
            return false;
        }
        return true;
    }

    public synchronized void invalidateSession(int windowIndex) {
        if (windowIndex >= 0 && windowIndex < 4) {
            if (reliefSessions[windowIndex] != null) {
                reliefSessions[windowIndex].active = false;
                reliefSessions[windowIndex] = null;
            }
        }
    }

    public synchronized void invalidateAllSessions() {
        for (int i = 0; i < 4; i++) {
            invalidateSession(i);
            ventilationOwnership[i] = false;
        }
    }

    public synchronized void setVentilationOwnership(int windowIndex, boolean owned) {
        if (windowIndex >= 0 && windowIndex < 4) {
            ventilationOwnership[windowIndex] = owned;
        }
    }

    public synchronized boolean isVentilationOwned(int windowIndex) {
        if (windowIndex >= 0 && windowIndex < 4) {
            return ventilationOwnership[windowIndex];
        }
        return false;
    }
}
