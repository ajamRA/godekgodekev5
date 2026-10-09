package com.emas.steering;

/**
 * Boundary implemented by an OEM interceptor, not an evdev observer.
 * A successful start MUST mean owned key events can be withheld from OEM dispatch.
 * Return false from Sink for unrelated events: the adapter forwards the original once.
 * Return true: only forward() may deliver OEM events. Forwarded events must bypass
 * interception, preserve metadata, and never be delivered to Sink again.
 * The adapter must use Android uptime event timestamps, recover OEM delivery if it
 * dies, and release interception/reconcile any held OEM keys on close. start must
 * not deliver callbacks synchronously before returning. close must return promptly.
 * Kernel realtime records are not accepted.
 */
public interface SteeringInputSession extends AutoCloseable {
    interface Sink { boolean onKey(Key key); }
    final class Key {
        public final int keyCode, repeatCount;
        public final boolean down;
        public final long eventTime;
        public final SteeringModeController.KeyMetadata metadata;
        public Key(int canonicalCode, boolean down, long uptime, int repeat,
                   SteeringModeController.KeyMetadata metadata) {
            this.keyCode = canonicalCode; this.down = down; this.eventTime = uptime;
            this.repeatCount = repeat; this.metadata = metadata;
        }
    }
    boolean start(Sink sink) throws Exception;
    void forward(SteeringModeController.KeyEventToDispatch event) throws Exception;
    void close() throws Exception;
}
