package com.emas.door;

/** Same elapsed-time base across processes on this Android boot, including device sleep. */
public final class AndroidDoorClock implements DoorStateHub.Clock {
    @Override public long elapsedMillis() { return android.os.SystemClock.elapsedRealtime(); }
    @Override public long wallMillis() { return System.currentTimeMillis(); }
}
