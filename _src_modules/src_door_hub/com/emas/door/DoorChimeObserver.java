package com.emas.door;

/** Silent baseline at startup, source changes, reconnects and UNKNOWN; bounded chime rate. */
public final class DoorChimeObserver {
    public static final int OPEN_CHIME = 1, CLOSE_CHIME = 2;
    private String source;
    private final DoorSnapshot.State[] previous = DoorStateHub.unknownStates();
    private final long[] epochs = new long[4];
    private long lastOpen = -1, lastClose = -1;
    public int observe(DoorHubClient.Reading reading, long elapsedMs) {
        boolean changedSource = !reading.source.equals(source);
        source = reading.source;
        boolean opened = false, closed = false;
        for (int i = 0; i < 4; i++) {
            DoorSnapshot.State current = reading.state(i);
            if (!changedSource && epochs[i] == reading.epoch(i)) {
                opened |= previous[i] == DoorSnapshot.State.CLOSED && current == DoorSnapshot.State.OPEN;
                closed |= previous[i] == DoorSnapshot.State.OPEN && current == DoorSnapshot.State.CLOSED;
            }
            previous[i] = current;
            epochs[i] = reading.epoch(i);
        }
        int result = 0;
        if (opened && (lastOpen < 0 || elapsedMs - lastOpen >= 2000)) { lastOpen = elapsedMs; result |= OPEN_CHIME; }
        if (closed && (lastClose < 0 || elapsedMs - lastClose >= 2000)) { lastClose = elapsedMs; result |= CLOSE_CHIME; }
        return result;
    }
}
