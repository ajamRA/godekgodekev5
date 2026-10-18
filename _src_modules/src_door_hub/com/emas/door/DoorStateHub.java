package com.emas.door;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** One sampler per host process; HTTP and window control only consume its published snapshot. */
public final class DoorStateHub implements AutoCloseable {
    public interface Clock { long elapsedMillis(); long wallMillis(); }
    public interface Reader { DoorSnapshot.State read(int door) throws Exception; }
    public static final long POLL_MS = 80;
    private final Clock clock;
    private final String generation = UUID.randomUUID().toString().replace("-", "");
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object pollLock = new Object();
    private final Object changed = new Object();
    private volatile DoorSnapshot snapshot;
    private volatile Thread sampler;
    private long sequence;

    public DoorStateHub(Clock clock) {
        this.clock = clock;
        snapshot = new DoorSnapshot(generation, 0, 0, 0, unknownStates());
    }
    public static DoorSnapshot.State[] unknownStates() {
        return new DoorSnapshot.State[]{DoorSnapshot.State.UNKNOWN, DoorSnapshot.State.UNKNOWN,
                DoorSnapshot.State.UNKNOWN, DoorSnapshot.State.UNKNOWN};
    }
    public DoorSnapshot snapshot() { return snapshot; }
    public DoorSnapshot awaitNext(long afterSequence, long timeoutMs) throws InterruptedException {
        synchronized (changed) {
            if (snapshot.sequence == afterSequence) changed.wait(timeoutMs);
            return snapshot;
        }
    }
    public void pollOnce(Reader reader) {
        synchronized (pollLock) {
            // Stamp the beginning of the sweep: a slow/hung property must not make earlier reads look new.
            long sampledMs = clock.elapsedMillis();
            DoorSnapshot.State[] states = unknownStates();
            for (int i = 0; i < 4; i++) {
                try { DoorSnapshot.State state = reader.read(i); if (state != null) states[i] = state; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                catch (Exception ignored) { /* Only this door becomes UNKNOWN. */ }
            }
            long[] epochs = snapshot.epochs();
            boolean gap = snapshot.sequence > 0 && sampledMs - snapshot.sampledElapsedMs > DoorSnapshot.MAX_AGE_MS;
            for (int i = 0; i < 4; i++) {
                if (gap || (states[i] != snapshot.state(i)
                        && (states[i] == DoorSnapshot.State.UNKNOWN || snapshot.state(i) == DoorSnapshot.State.UNKNOWN))) epochs[i]++;
            }
            snapshot = new DoorSnapshot(generation, ++sequence, sampledMs, clock.wallMillis(), states, epochs);
            synchronized (changed) { changed.notifyAll(); }
        }
    }
    public synchronized void start(Reader reader) {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Sampler already started");
        sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                long begin = clock.elapsedMillis();
                pollOnce(reader);
                // No catch-up bursts after slow Binder calls.
                long delay = Math.max(1, POLL_MS - (clock.elapsedMillis() - begin));
                try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, "door-hub-poller");
        sampler.setDaemon(true);
        sampler.start();
    }
    @Override public void close() { Thread thread = sampler; if (thread != null) thread.interrupt(); }
}
