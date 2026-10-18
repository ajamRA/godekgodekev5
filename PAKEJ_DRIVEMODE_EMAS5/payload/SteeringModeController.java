package com.emas.steering;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Enterprise Automotive-Grade Steering Wheel Drive Mode & Regen Controller for Proton e.MAS 5 (IHU801P).
 * 
 * Target Vehicle: Proton e.MAS 5 ONLY.
 * Modes Supported:
 *   - Drive Mode: Comfort (0), Sport (2), Eco (1)
 *   - Regen Level: Low (1), Medium (2), High (3)
 * 
 * Threading and gesture invariants (vehicle integration requires OEM validation):
 *   1. Pure Non-Blocking: asyncCycle returns instantly (0ms) without any .get() blocks.
 *   2. Reentrancy & Recursion Guard: Prevents VHAL callback loops from triggering chained writes.
 *   3. Independent Event Timestamps: Timers never advance or corrupt key event sequence.
 *   4. Second-Hold Flush: Flushes 1st click cleanly before emitting 2nd long-press hold.
 *   5. Accurate downTime Consistency: Long-press holds maintain matching downTime across events.
 *   6. Dedicated Telemetry Versioning: Drive mode and Regen have separate version counters.
 *   7. Key Isolation: Unrelated keys (e.g. Mic 200231) immediately return false.
 *   8. Zero Clamping, Zero Looping, Area 0 only, 1-3 validation.
 */
public class SteeringModeController {
    public static final String TAG = "SteeringModeController";

    // Key Codes for Proton e.MAS 5
    public static final int KEY_MEDIA_NEXT = 200087;     // HW Key 305 (Right Arrow ▶)
    public static final int KEY_MEDIA_PREVIOUS = 200088; // HW Key 304 (Left Arrow ◀)
    public static final int KEY_VOLUME_DOWN = 200025;    // HW Key 302 (Down Arrow ▼ / Vol Down)

    // Official e.MAS 5 VHAL Property IDs & AdaptAPI Functions
    public static final int VHAL_PROP_DRIVE_MODE = 678428906; // 0x287000EA (Function 92)
    public static final int VHAL_PROP_REGEN_LEVEL = 678429105; // 0x287001B1 (Function 404)

    // Timing Constants (Milliseconds)
    public static final long DEBOUNCE_THRESHOLD_MS = 40;     // Hardware chatter filter
    public static final long DOUBLE_CLICK_WINDOW_MS = 350;    // Window from 1st UP to 2nd DOWN
    public static final long LONG_PRESS_THRESHOLD_MS = 600;   // Threshold to handoff to OEM hold
    public static final long HOLD_TO_CYCLE_MS = 800;          // Threshold for Hold Sikit mode cycle (800ms)
    public static final long HOLD_TO_SERVICE_MODE_MS = 5000;  // Threshold for Hold Bawah Service Mode (5000ms)

    public enum GestureTrigger {
        DOUBLE_CLICK,
        HOLD_SHORT
    }

    private volatile GestureTrigger gestureTrigger = GestureTrigger.DOUBLE_CLICK;

    public void setGestureTrigger(GestureTrigger trigger) {
        this.gestureTrigger = trigger;
    }

    public GestureTrigger getGestureTrigger() {
        return gestureTrigger;
    }

    public enum ButtonState {
        IDLE,
        PRESSED_FIRST,
        WAIT_SECOND,
        PRESSED_SECOND,
        OEM_LONG_ACTIVE
    }

    private static class ButtonContext {
        final int keyCode;
        ButtonState state = ButtonState.IDLE;
        long firstDownTime = 0;
        long firstUpTime = 0;
        long secondDownTime = 0;
        long activeHoldDownTime = 0;
        long lastActionTime = 0;
        long generation = 0;
        boolean modeTriggered = false;
        boolean serviceModeTriggered = false;
        KeyMetadata firstMetadata;
        KeyMetadata secondMetadata;
        KeyMetadata activeMetadata;
        KeyMetadata firstUpMetadata;
        KeyMetadata secondUpMetadata;

        ButtonContext(int keyCode) {
            this.keyCode = keyCode;
            firstMetadata = secondMetadata = activeMetadata = KeyMetadata.basic(keyCode);
            firstUpMetadata = secondUpMetadata = firstMetadata;
        }

        void reset() {
            state = ButtonState.IDLE;
            firstDownTime = 0;
            firstUpTime = 0;
            secondDownTime = 0;
            activeHoldDownTime = 0;
            modeTriggered = false;
            serviceModeTriggered = false;
            generation++;
        }
    }

    private final ButtonContext nextBtn = new ButtonContext(KEY_MEDIA_NEXT);
    private final ButtonContext prevBtn = new ButtonContext(KEY_MEDIA_PREVIOUS);
    private final ButtonContext downBtn = new ButtonContext(KEY_VOLUME_DOWN);

    private final AtomicInteger confirmedDriveMode = new AtomicInteger(0); // 0=Comfort, 1=Eco, 2=Sport
    private final AtomicInteger confirmedRegenLevel = new AtomicInteger(2); // 1=Low, 2=Med, 3=High

    private final AtomicLong driveTelemetryVersion = new AtomicLong(0);
    private final AtomicLong regenTelemetryVersion = new AtomicLong(0);
    private final AtomicBoolean isDriveSeeded = new AtomicBoolean(true);
    private final AtomicBoolean isRegenSeeded = new AtomicBoolean(true);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final AtomicBoolean vehicleActionsEnabled = new AtomicBoolean(true);

    private final ExecutorService vhalWorker = Executors.newSingleThreadExecutor();
    private final ReentrantLock writeLock = new ReentrantLock();

    public interface VehicleDispatcher {
        boolean sendPropertyInt(int propId, int area, int value);
        void forwardMediaKey(int keyCode, boolean isDown, long downTime, long eventTime);
        default void forwardMediaKey(int keyCode, boolean isDown, long downTime, long eventTime, int repeatCount, int flags) {
            forwardMediaKey(keyCode, isDown, downTime, eventTime);
        }
        default void forwardMediaKey(KeyEventToDispatch event) {
            forwardMediaKey(event.keyCode, event.isDown, event.downTime, event.eventTime, event.repeatCount, event.flags);
        }
        void playAudioFeedback(String prompt);
        default void toggleServiceMode() {}
        default int queryCurrentProperty(int propId, int area) { return -1; }
    }

    private final VehicleDispatcher dispatcher;

    public SteeringModeController(VehicleDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public synchronized void setRequireVehicleSeed(boolean required) {
        this.isDriveSeeded.set(!required);
        this.isRegenSeeded.set(!required);
        driveTelemetryVersion.incrementAndGet();
        regenTelemetryVersion.incrementAndGet();
    }

    public synchronized void invalidateVehicleState() {
        isDriveSeeded.set(false);
        isRegenSeeded.set(false);
        driveTelemetryVersion.incrementAndGet();
        regenTelemetryVersion.incrementAndGet();
    }

    public void setVehicleActionsEnabled(boolean enabled) { vehicleActionsEnabled.set(enabled); }

    /** Poll snapshots are applied only if no newer update/write occurred during the read. */
    public synchronized long vehicleStateVersion(int propId) {
        return propId == VHAL_PROP_DRIVE_MODE ? driveTelemetryVersion.get() : regenTelemetryVersion.get();
    }

    public synchronized void applyVehicleSnapshot(int propId, int value, long version) {
        if (vehicleStateVersion(propId) == version) onVehiclePropertyChange(propId, value);
    }

    public synchronized void seedInitialState(int driveMode, int regenLevel) {
        if (driveMode >= 0 && driveMode <= 3) {
            confirmedDriveMode.set(driveMode);
            isDriveSeeded.set(true);
            driveTelemetryVersion.incrementAndGet();
        }
        if (regenLevel >= 0 && regenLevel <= 3) {
            confirmedRegenLevel.set(regenLevel);
            isRegenSeeded.set(true);
            regenTelemetryVersion.incrementAndGet();
        }
    }

    public synchronized void onVehiclePropertyChange(int propId, int value) {
        if (propId == VHAL_PROP_DRIVE_MODE && value >= 0 && value <= 3) {
            confirmedDriveMode.set(value);
            isDriveSeeded.set(true);
            driveTelemetryVersion.incrementAndGet();
        } else if (propId == VHAL_PROP_REGEN_LEVEL && value >= 0 && value <= 3) {
            confirmedRegenLevel.set(value);
            isRegenSeeded.set(true);
            regenTelemetryVersion.incrementAndGet();
        }
    }

    public int getConfirmedDriveMode() {
        return confirmedDriveMode.get();
    }

    public synchronized void setConfirmedDriveMode(int mode) {
        if (mode >= 0 && mode <= 3) {
            confirmedDriveMode.set(mode);
            driveTelemetryVersion.incrementAndGet();
        }
    }

    public int getConfirmedRegenLevel() {
        return confirmedRegenLevel.get();
    }

    public synchronized void setConfirmedRegenLevel(int level) {
        if (level >= 1 && level <= 3) {
            confirmedRegenLevel.set(level);
            regenTelemetryVersion.incrementAndGet();
        }
    }

    private ButtonContext getContext(int keyCode) {
        if (keyCode == KEY_MEDIA_NEXT) return nextBtn;
        if (keyCode == KEY_MEDIA_PREVIOUS) return prevBtn;
        if (keyCode == KEY_VOLUME_DOWN) return downBtn;
        return null;
    }

    public synchronized boolean hasPendingGesture(int keyCode) {
        ButtonContext context = getContext(keyCode);
        return context != null && context.state != ButtonState.IDLE;
    }

    public static final class KeyMetadata {
        public final int originalKeyCode, deviceId, scanCode, source, metaState, flags, policyFlags;
        public KeyMetadata(int code, int device, int scan, int source, int meta, int flags, int policy) {
            this.originalKeyCode = code; this.deviceId = device; this.scanCode = scan;
            this.source = source; this.metaState = meta; this.flags = flags; this.policyFlags = policy;
        }
        public static KeyMetadata basic(int code) { return new KeyMetadata(code, 0, 0, 0, 0, 8, 0); }
    }

    public static class KeyEventToDispatch {
        public final int keyCode;
        public final boolean isDown;
        public final long downTime;
        public final long eventTime;
        public final int repeatCount;
        public final int flags;
        public final KeyMetadata metadata;

        public KeyEventToDispatch(int k, boolean d, long dt, long et) {
            this(k, d, dt, et, 0, 0);
        }

        public KeyEventToDispatch(int k, boolean d, long dt, long et, int repeat, int flags) {
            this(k, d, dt, et, repeat, new KeyMetadata(k, 0, 0, 0, 0, flags, 0));
        }

        public KeyEventToDispatch(int k, boolean d, long dt, long et, int repeat, KeyMetadata metadata) {
            this.keyCode = k;
            this.isDown = d;
            this.downTime = dt;
            this.eventTime = et;
            this.repeatCount = repeat;
            this.flags = metadata.flags;
            this.metadata = metadata;
        }
    }

    private final ExecutorService oemWorker = Executors.newSingleThreadExecutor();

    private void dispatchOemEvents(List<KeyEventToDispatch> events) {
        if (dispatcher == null || events == null || events.isEmpty()) return;
        if (!isShutdown.get()) {
            try { oemWorker.submit(() -> {
                for (KeyEventToDispatch ev : events) {
                    try {
                        dispatcher.forwardMediaKey(ev);
                    } catch (Throwable ignored) {}
                }
            }); } catch (java.util.concurrent.RejectedExecutionException stopped) {
                // Shutdown won the submission race. The input session releases ownership.
            }
        }
    }

    public boolean onKeyDown(int keyCode, long timestampMs, int repeatCount) {
        return onKeyDown(keyCode, timestampMs, repeatCount, KeyMetadata.basic(keyCode));
    }

    public boolean onKeyDown(int keyCode, long timestampMs, int repeatCount, KeyMetadata metadata) {
        if (metadata == null) return false;
        if (keyCode != KEY_MEDIA_NEXT && keyCode != KEY_MEDIA_PREVIOUS && keyCode != KEY_VOLUME_DOWN) {
            return false;
        }

        List<KeyEventToDispatch> eventsToForward = new ArrayList<>();
        boolean isConsumed = true;

        if (gestureTrigger == GestureTrigger.HOLD_SHORT) {
            int actionToCycle = 0;
            synchronized (this) {
                ButtonContext ctx = getContext(keyCode);
                if (ctx == null) return false;

                if (ctx.lastActionTime > 0 && timestampMs < ctx.lastActionTime) {
                    return true;
                }
                if (ctx.state == ButtonState.IDLE && ctx.lastActionTime > 0 && (timestampMs - ctx.lastActionTime) < DEBOUNCE_THRESHOLD_MS && repeatCount == 0) {
                    return true;
                }
                ctx.lastActionTime = timestampMs;

                switch (ctx.state) {
                    case IDLE:
                        ctx.firstMetadata = metadata;
                        ctx.firstDownTime = timestampMs;
                        ctx.state = ButtonState.PRESSED_FIRST;
                        ctx.modeTriggered = false;
                        ctx.generation++;
                        isConsumed = true;
                        break;

                    case PRESSED_FIRST:
                        long holdDuration = timestampMs - ctx.firstDownTime;
                        if (keyCode == KEY_VOLUME_DOWN) {
                            if (holdDuration >= HOLD_TO_SERVICE_MODE_MS && !ctx.serviceModeTriggered) {
                                ctx.serviceModeTriggered = true;
                                ctx.state = ButtonState.OEM_LONG_ACTIVE;
                                actionToCycle = 99; // Service Mode Toggle
                            }
                        } else {
                            if (holdDuration >= HOLD_TO_CYCLE_MS && !ctx.modeTriggered) {
                                ctx.modeTriggered = true;
                                ctx.state = ButtonState.OEM_LONG_ACTIVE;
                                if (keyCode == KEY_MEDIA_NEXT) actionToCycle = 1;
                                else if (keyCode == KEY_MEDIA_PREVIOUS) actionToCycle = 2;
                            }
                        }
                        isConsumed = true;
                        break;

                    case OEM_LONG_ACTIVE:
                        if (keyCode == KEY_MEDIA_PREVIOUS && (timestampMs - ctx.firstDownTime) >= 10000) {
                            eventsToForward.add(new KeyEventToDispatch(keyCode, true, ctx.firstDownTime, timestampMs, repeatCount, metadata));
                            isConsumed = false;
                        } else {
                            isConsumed = true;
                        }
                        break;

                    default:
                        isConsumed = true;
                        break;
                }
                dispatchOemEvents(eventsToForward);
            }

            if (actionToCycle == 1) {
                asyncCycleDriveMode();
            } else if (actionToCycle == 2) {
                asyncCycleRegenLevel();
            } else if (actionToCycle == 99) {
                toggleServiceMode();
            }
            return isConsumed;
        }

        synchronized (this) {
            ButtonContext ctx = getContext(keyCode);
            if (ctx == null) return false;

            // Reject backward timestamps on this button context
            if (ctx.lastActionTime > 0 && timestampMs < ctx.lastActionTime) {
                return true;
            }

            // Debounce filter for rapid repeated DOWN in IDLE
            if (ctx.state == ButtonState.IDLE && ctx.lastActionTime > 0 && (timestampMs - ctx.lastActionTime) < DEBOUNCE_THRESHOLD_MS && repeatCount == 0) {
                return true;
            }
            ctx.lastActionTime = timestampMs;

            switch (ctx.state) {
                case IDLE:
                    ctx.firstMetadata = metadata;
                    ctx.firstDownTime = timestampMs;
                    ctx.state = ButtonState.PRESSED_FIRST;
                    ctx.generation++;
                    isConsumed = true;
                    break;

                case PRESSED_FIRST:
                    long holdDuration = timestampMs - ctx.firstDownTime;
                    if (holdDuration >= LONG_PRESS_THRESHOLD_MS) {
                        ctx.state = ButtonState.OEM_LONG_ACTIVE;
                        ctx.activeHoldDownTime = ctx.firstDownTime;
                        ctx.activeMetadata = ctx.firstMetadata;
                        eventsToForward.add(new KeyEventToDispatch(keyCode, true, ctx.firstDownTime, timestampMs, repeatCount, metadata));
                        isConsumed = false;
                    } else {
                        isConsumed = true;
                    }
                    break;

                case WAIT_SECOND:
                    long gap = timestampMs - ctx.firstUpTime;
                    if (gap < DEBOUNCE_THRESHOLD_MS) {
                        // 5ms bounce -> ignore electrical chatter, remain in WAIT_SECOND
                        return true;
                    } else if (gap <= DOUBLE_CLICK_WINDOW_MS) {
                        // Valid second click
                        ctx.secondDownTime = timestampMs;
                        ctx.secondMetadata = metadata;
                        ctx.state = ButtonState.PRESSED_SECOND;
                        ctx.generation++;
                        isConsumed = true;
                    } else {
                        // Late 2nd click: Flush 1st click, start new 1st press
                        long d1 = ctx.firstDownTime;
                        long u1 = ctx.firstUpTime;
                        KeyMetadata priorMetadata = ctx.firstMetadata;
                        ctx.firstDownTime = timestampMs;
                        ctx.firstMetadata = metadata;
                        ctx.firstUpTime = 0;
                        ctx.state = ButtonState.PRESSED_FIRST;
                        ctx.generation++;
                        eventsToForward.add(new KeyEventToDispatch(keyCode, true, d1, d1, 0, priorMetadata));
                        eventsToForward.add(new KeyEventToDispatch(keyCode, false, d1, u1, 0, ctx.firstUpMetadata));
                        isConsumed = true;
                    }
                    break;

                case PRESSED_SECOND:
                    long hold2 = timestampMs - ctx.secondDownTime;
                    if (hold2 >= LONG_PRESS_THRESHOLD_MS) {
                        ctx.state = ButtonState.OEM_LONG_ACTIVE;
                        ctx.activeHoldDownTime = ctx.secondDownTime;
                        ctx.activeMetadata = ctx.secondMetadata;
                        long d1 = ctx.firstDownTime;
                        long u1 = ctx.firstUpTime;
                        eventsToForward.add(new KeyEventToDispatch(keyCode, true, d1, d1, 0, ctx.firstMetadata));
                        eventsToForward.add(new KeyEventToDispatch(keyCode, false, d1, u1, 0, ctx.firstUpMetadata));
                        eventsToForward.add(new KeyEventToDispatch(keyCode, true, ctx.secondDownTime, timestampMs, repeatCount, metadata));
                        isConsumed = false;
                    }
                    break;

                case OEM_LONG_ACTIVE:
                    long holdDt = ctx.activeHoldDownTime > 0 ? ctx.activeHoldDownTime : ctx.firstDownTime;
                    eventsToForward.add(new KeyEventToDispatch(keyCode, true, holdDt, timestampMs, repeatCount, metadata));
                    isConsumed = false;
                    break;
            }

            // Enqueue OEM events under state monitor to guarantee transition-to-submission atomicity
            dispatchOemEvents(eventsToForward);
        }

        return isConsumed;
    }

    public boolean onKeyUp(int keyCode, long timestampMs) {
        return onKeyUp(keyCode, timestampMs, KeyMetadata.basic(keyCode));
    }

    public boolean onKeyUp(int keyCode, long timestampMs, KeyMetadata metadata) {
        if (metadata == null) return false;
        if (keyCode != KEY_MEDIA_NEXT && keyCode != KEY_MEDIA_PREVIOUS && keyCode != KEY_VOLUME_DOWN) {
            return false;
        }

        List<KeyEventToDispatch> eventsToForward = new ArrayList<>();
        int actionToCycle = 0; // 0=None, 1=DriveMode, 2=Regen
        boolean isConsumed = true;

        if (gestureTrigger == GestureTrigger.HOLD_SHORT) {
            synchronized (this) {
                ButtonContext ctx = getContext(keyCode);
                if (ctx == null) return false;

                if (ctx.state == ButtonState.PRESSED_FIRST && timestampMs < ctx.firstDownTime) {
                    return false;
                }
                ctx.lastActionTime = timestampMs;

                if (ctx.state == ButtonState.OEM_LONG_ACTIVE) {
                    if (keyCode == KEY_MEDIA_PREVIOUS && (timestampMs - ctx.firstDownTime) >= 10000) {
                        eventsToForward.add(new KeyEventToDispatch(keyCode, false, ctx.firstDownTime, timestampMs, 0, metadata));
                    }
                    ctx.reset();
                    isConsumed = true;
                } else if (ctx.state == ButtonState.PRESSED_FIRST) {
                    long dt = ctx.firstDownTime;
                    KeyMetadata dMeta = ctx.firstMetadata;
                    ctx.reset();
                    eventsToForward.add(new KeyEventToDispatch(keyCode, true, dt, dt, 0, dMeta));
                    eventsToForward.add(new KeyEventToDispatch(keyCode, false, dt, timestampMs, 0, metadata));
                    isConsumed = false;
                } else {
                    ctx.reset();
                    isConsumed = false;
                }
                dispatchOemEvents(eventsToForward);
            }
            return isConsumed;
        }

        synchronized (this) {
            ButtonContext ctx = getContext(keyCode);
            if (ctx == null) return false;

            // Reject anomalous UP older than active DOWN or older than last action
            if (ctx.state == ButtonState.PRESSED_FIRST && timestampMs < ctx.firstDownTime) {
                return false;
            }
            if (ctx.state == ButtonState.PRESSED_SECOND && timestampMs < ctx.secondDownTime) {
                return false;
            }
            if (ctx.state == ButtonState.OEM_LONG_ACTIVE) {
                long holdDt = ctx.activeHoldDownTime > 0 ? ctx.activeHoldDownTime : (ctx.secondDownTime > 0 ? ctx.secondDownTime : ctx.firstDownTime);
                if (holdDt > 0 && timestampMs < holdDt) {
                    return false;
                }
            }

            ctx.lastActionTime = timestampMs;

            if (ctx.state == ButtonState.PRESSED_FIRST) ctx.firstUpMetadata = metadata;
            if (ctx.state == ButtonState.PRESSED_SECOND) ctx.secondUpMetadata = metadata;

            switch (ctx.state) {
                case PRESSED_FIRST:
                    long duration = timestampMs - ctx.firstDownTime;
                    if (duration >= LONG_PRESS_THRESHOLD_MS) {
                        long dt = ctx.firstDownTime;
                        ctx.reset();
                        eventsToForward.add(new KeyEventToDispatch(keyCode, true, dt, dt, 0, ctx.firstMetadata));
                        eventsToForward.add(new KeyEventToDispatch(keyCode, false, dt, timestampMs, 0, ctx.firstUpMetadata));
                        isConsumed = false;
                    } else {
                        ctx.firstUpTime = timestampMs;
                        ctx.state = ButtonState.WAIT_SECOND;
                        isConsumed = true;
                    }
                    break;

                case PRESSED_SECOND:
                    long duration2 = timestampMs - ctx.secondDownTime;
                    if (duration2 >= LONG_PRESS_THRESHOLD_MS) {
                        long d1 = ctx.firstDownTime;
                        long u1 = ctx.firstUpTime;
                        long d2 = ctx.secondDownTime;
                        ctx.reset();
                        eventsToForward.add(new KeyEventToDispatch(keyCode, true, d1, d1, 0, ctx.firstMetadata));
                        eventsToForward.add(new KeyEventToDispatch(keyCode, false, d1, u1, 0, ctx.firstUpMetadata));
                        eventsToForward.add(new KeyEventToDispatch(keyCode, true, d2, d2, 0, ctx.secondMetadata));
                        eventsToForward.add(new KeyEventToDispatch(keyCode, false, d2, timestampMs, 0, ctx.secondUpMetadata));
                        isConsumed = false;
                    } else {
                        ctx.reset();
                        if (keyCode == KEY_MEDIA_NEXT) actionToCycle = 1;
                        else if (keyCode == KEY_MEDIA_PREVIOUS) actionToCycle = 2;
                        isConsumed = true;
                    }
                    break;

                case OEM_LONG_ACTIVE:
                    long origDown = ctx.activeHoldDownTime > 0 ? ctx.activeHoldDownTime : (ctx.firstDownTime > 0 ? ctx.firstDownTime : ctx.secondDownTime);
                    ctx.reset();
                    eventsToForward.add(new KeyEventToDispatch(keyCode, false, origDown, timestampMs, 0, metadata));
                    isConsumed = false;
                    break;

                case WAIT_SECOND:
                case IDLE:
                default:
                    isConsumed = false;
                    break;
            }

            // Enqueue OEM events under state monitor to guarantee transition-to-submission atomicity
            dispatchOemEvents(eventsToForward);
        }

        if (actionToCycle == 1) {
            asyncCycleDriveMode();
            return true;
        } else if (actionToCycle == 2) {
            asyncCycleRegenLevel();
            return true;
        }

        return isConsumed;
    }

    public void checkHoldTimeout(long currentTimestampMs) {
        if (gestureTrigger == GestureTrigger.HOLD_SHORT) {
            boolean cycleNext = false;
            boolean cyclePrev = false;
            boolean cycleServiceMode = false;

            synchronized (this) {
                if (nextBtn.state == ButtonState.PRESSED_FIRST && nextBtn.firstDownTime > 0 && !nextBtn.modeTriggered) {
                    if ((currentTimestampMs - nextBtn.firstDownTime) >= HOLD_TO_CYCLE_MS) {
                        nextBtn.modeTriggered = true;
                        nextBtn.state = ButtonState.OEM_LONG_ACTIVE;
                        cycleNext = true;
                    }
                }
                if (prevBtn.state == ButtonState.PRESSED_FIRST && prevBtn.firstDownTime > 0 && !prevBtn.modeTriggered) {
                    if ((currentTimestampMs - prevBtn.firstDownTime) >= HOLD_TO_CYCLE_MS) {
                        prevBtn.modeTriggered = true;
                        prevBtn.state = ButtonState.OEM_LONG_ACTIVE;
                        cyclePrev = true;
                    }
                }
                if (downBtn.state == ButtonState.PRESSED_FIRST && downBtn.firstDownTime > 0 && !downBtn.serviceModeTriggered) {
                    if ((currentTimestampMs - downBtn.firstDownTime) >= HOLD_TO_SERVICE_MODE_MS) {
                        downBtn.serviceModeTriggered = true;
                        downBtn.state = ButtonState.OEM_LONG_ACTIVE;
                        cycleServiceMode = true;
                    }
                }
            }

            if (cycleNext) {
                asyncCycleDriveMode();
            }
            if (cyclePrev) {
                asyncCycleRegenLevel();
            }
            if (cycleServiceMode) {
                toggleServiceMode();
            }
            return;
        }

        List<KeyEventToDispatch> eventsToForward = new ArrayList<>();

        synchronized (this) {
            if (nextBtn.state == ButtonState.PRESSED_FIRST && (currentTimestampMs - nextBtn.firstDownTime) >= LONG_PRESS_THRESHOLD_MS && nextBtn.firstDownTime > 0) {
                nextBtn.state = ButtonState.OEM_LONG_ACTIVE;
                nextBtn.activeHoldDownTime = nextBtn.firstDownTime;
                nextBtn.activeMetadata = nextBtn.firstMetadata;
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_NEXT, true, nextBtn.firstDownTime, currentTimestampMs, 0, nextBtn.firstMetadata));
            } else if (nextBtn.state == ButtonState.PRESSED_SECOND && (currentTimestampMs - nextBtn.secondDownTime) >= LONG_PRESS_THRESHOLD_MS && nextBtn.secondDownTime > 0) {
                nextBtn.state = ButtonState.OEM_LONG_ACTIVE;
                nextBtn.activeHoldDownTime = nextBtn.secondDownTime;
                nextBtn.activeMetadata = nextBtn.secondMetadata;
                // Flush 1st click + 2nd hold DOWN
                long d1 = nextBtn.firstDownTime;
                long u1 = nextBtn.firstUpTime;
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_NEXT, true, d1, d1, 0, nextBtn.firstMetadata));
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_NEXT, false, d1, u1, 0, nextBtn.firstUpMetadata));
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_NEXT, true, nextBtn.secondDownTime, currentTimestampMs, 0, nextBtn.secondMetadata));
            }

            if (prevBtn.state == ButtonState.PRESSED_FIRST && (currentTimestampMs - prevBtn.firstDownTime) >= LONG_PRESS_THRESHOLD_MS && prevBtn.firstDownTime > 0) {
                prevBtn.state = ButtonState.OEM_LONG_ACTIVE;
                prevBtn.activeHoldDownTime = prevBtn.firstDownTime;
                prevBtn.activeMetadata = prevBtn.firstMetadata;
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_PREVIOUS, true, prevBtn.firstDownTime, currentTimestampMs, 0, prevBtn.firstMetadata));
            } else if (prevBtn.state == ButtonState.PRESSED_SECOND && (currentTimestampMs - prevBtn.secondDownTime) >= LONG_PRESS_THRESHOLD_MS && prevBtn.secondDownTime > 0) {
                prevBtn.state = ButtonState.OEM_LONG_ACTIVE;
                prevBtn.activeHoldDownTime = prevBtn.secondDownTime;
                prevBtn.activeMetadata = prevBtn.secondMetadata;
                // Flush 1st click + 2nd hold DOWN
                long d1 = prevBtn.firstDownTime;
                long u1 = prevBtn.firstUpTime;
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_PREVIOUS, true, d1, d1, 0, prevBtn.firstMetadata));
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_PREVIOUS, false, d1, u1, 0, prevBtn.firstUpMetadata));
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_PREVIOUS, true, prevBtn.secondDownTime, currentTimestampMs, 0, prevBtn.secondMetadata));
            }

            // Enqueue OEM events under state monitor to guarantee transition-to-submission atomicity
            dispatchOemEvents(eventsToForward);
        }
    }

    public void checkPendingSingleClicks(long currentTimestampMs) {
        if (gestureTrigger == GestureTrigger.HOLD_SHORT) {
            return;
        }

        List<KeyEventToDispatch> eventsToForward = new ArrayList<>();

        synchronized (this) {
            if (nextBtn.state == ButtonState.WAIT_SECOND && (currentTimestampMs - nextBtn.firstUpTime) > DOUBLE_CLICK_WINDOW_MS && nextBtn.firstUpTime > 0) {
                long d = nextBtn.firstDownTime;
                long u = nextBtn.firstUpTime;
                nextBtn.reset();
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_NEXT, true, d, d, 0, nextBtn.firstMetadata));
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_NEXT, false, d, u, 0, nextBtn.firstUpMetadata));
            }

            if (prevBtn.state == ButtonState.WAIT_SECOND && (currentTimestampMs - prevBtn.firstUpTime) > DOUBLE_CLICK_WINDOW_MS && prevBtn.firstUpTime > 0) {
                long d = prevBtn.firstDownTime;
                long u = prevBtn.firstUpTime;
                prevBtn.reset();
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_PREVIOUS, true, d, d, 0, prevBtn.firstMetadata));
                eventsToForward.add(new KeyEventToDispatch(KEY_MEDIA_PREVIOUS, false, d, u, 0, prevBtn.firstUpMetadata));
            }

            // Enqueue OEM events under state monitor to guarantee transition-to-submission atomicity
            dispatchOemEvents(eventsToForward);
        }
    }

    /**
     * Pure asynchronous one-shot dispatch to worker queue (instant return, zero input delay).
     */
    public void asyncCycleDriveMode() {
        if (!isShutdown.get()) {
            try { vhalWorker.submit(this::executeCycleDriveMode); }
            catch (java.util.concurrent.RejectedExecutionException stopped) {}
        }
    }

    /**
     * Pure asynchronous one-shot dispatch to worker queue (instant return, zero input delay).
     */
    public void asyncCycleRegenLevel() {
        if (!isShutdown.get()) {
            try { vhalWorker.submit(this::executeCycleRegenLevel); }
            catch (java.util.concurrent.RejectedExecutionException stopped) {}
        }
    }

    public int executeCycleDriveMode() {
        if (isShutdown.get() || !vehicleActionsEnabled.get()) return confirmedDriveMode.get();

        // Drop recursive callback cycles on the same thread
        if (writeLock.isHeldByCurrentThread()) {
            return confirmedDriveMode.get();
        }

        writeLock.lock();
        try {
            if (isShutdown.get() || !vehicleActionsEnabled.get()) return confirmedDriveMode.get();

            boolean needsQuery;
            long queryVersion;
            synchronized (this) {
                needsQuery = !isDriveSeeded.get();
                queryVersion = driveTelemetryVersion.get();
            }

            int queried = -1;
            if (needsQuery && dispatcher != null) {
                try {
                    queried = dispatcher.queryCurrentProperty(VHAL_PROP_DRIVE_MODE, 0);
                } catch (Throwable t) {
                    queried = -1;
                }
            }

            int expectedCurrent;
            int target;
            long expectedVer;

            synchronized (this) {
                if (!isDriveSeeded.get()) {
                    if (driveTelemetryVersion.get() != queryVersion) return confirmedDriveMode.get();
                    if (queried >= 0 && queried <= 3) {
                        confirmedDriveMode.set(queried);
                        isDriveSeeded.set(true);
                        driveTelemetryVersion.incrementAndGet();
                    } else {
                        // Query failed / unseeded -> DO NOT write unverified defaults to vehicle!
                        return confirmedDriveMode.get();
                    }
                }
                expectedCurrent = confirmedDriveMode.get();
                if (expectedCurrent == 0) {
                    target = 3; // Comfort (0) -> Sport (3)
                } else if (expectedCurrent == 2 || expectedCurrent == 3) {
                    target = 2; // Sport (2/3) -> Eco (2)
                } else {
                    target = 1; // Eco (1) -> Comfort (1)
                }
                expectedVer = driveTelemetryVersion.get();
            }

            boolean success = false;
            if (isShutdown.get() || !vehicleActionsEnabled.get()) return expectedCurrent;
            if (dispatcher != null) {
                try {
                    success = dispatcher.sendPropertyInt(VHAL_PROP_DRIVE_MODE, 0, target);
                } catch (Throwable t) {
                    success = false;
                }
            }

            if (success) {
                boolean cacheUpdated = false;
                synchronized (this) {
                    if (driveTelemetryVersion.get() == expectedVer) {
                        int stateToStore = (target == 3 ? 2 : (target == 2 ? 1 : 0));
                        confirmedDriveMode.set(stateToStore);
                        driveTelemetryVersion.incrementAndGet();
                        cacheUpdated = true;
                    }
                }
                if (cacheUpdated && dispatcher != null) {
                    try {
                        String label = (target == 3 ? "Sport" : (target == 2 ? "Eco" : "Comfort"));
                        dispatcher.playAudioFeedback("Mode: " + label);
                    } catch (Throwable ignored) {}
                }
                return target;
            } else {
                return expectedCurrent;
            }
        } finally {
            writeLock.unlock();
        }
    }

    public int executeCycleRegenLevel() {
        if (isShutdown.get() || !vehicleActionsEnabled.get()) return confirmedRegenLevel.get();

        // Drop recursive callback cycles on the same thread
        if (writeLock.isHeldByCurrentThread()) {
            return confirmedRegenLevel.get();
        }

        writeLock.lock();
        try {
            if (isShutdown.get() || !vehicleActionsEnabled.get()) return confirmedRegenLevel.get();

            boolean needsQuery;
            long queryVersion;
            synchronized (this) {
                needsQuery = !isRegenSeeded.get();
                queryVersion = regenTelemetryVersion.get();
            }

            int queried = -1;
            if (needsQuery && dispatcher != null) {
                try {
                    queried = dispatcher.queryCurrentProperty(VHAL_PROP_REGEN_LEVEL, 0);
                } catch (Throwable t) {
                    queried = -1;
                }
            }

            int expectedCurrent;
            int target;
            long expectedVer;

            synchronized (this) {
                if (!isRegenSeeded.get()) {
                    if (regenTelemetryVersion.get() != queryVersion) return confirmedRegenLevel.get();
                    if (queried >= 0 && queried <= 3) {
                        confirmedRegenLevel.set(queried);
                        isRegenSeeded.set(true);
                        regenTelemetryVersion.incrementAndGet();
                    } else {
                        // Query failed / unseeded -> DO NOT write unverified defaults to vehicle!
                        return confirmedRegenLevel.get();
                    }
                }
                expectedCurrent = confirmedRegenLevel.get();
                if (expectedCurrent == 0) target = 1;
                else if (expectedCurrent == 1) target = 2;
                else if (expectedCurrent == 2) target = 3;
                else target = 1;
                expectedVer = regenTelemetryVersion.get();
            }

            boolean success = false;
            if (isShutdown.get() || !vehicleActionsEnabled.get()) return expectedCurrent;
            if (dispatcher != null) {
                try {
                    success = dispatcher.sendPropertyInt(VHAL_PROP_REGEN_LEVEL, 0, target);
                } catch (Throwable t) {
                    success = false;
                }
            }

            if (success) {
                boolean cacheUpdated = false;
                synchronized (this) {
                    if (regenTelemetryVersion.get() == expectedVer) {
                        confirmedRegenLevel.set(target);
                        regenTelemetryVersion.incrementAndGet();
                        cacheUpdated = true;
                    }
                }
                if (cacheUpdated && dispatcher != null) {
                    try {
                        String label = (target == 1 ? "Low" : (target == 2 ? "Medium" : "High"));
                        dispatcher.playAudioFeedback("Regen: " + label);
                    } catch (Throwable ignored) {}
                }
                return target;
            } else {
                return expectedCurrent;
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void toggleServiceMode() {
        if (dispatcher != null) {
            try {
                dispatcher.playAudioFeedback("ServiceMode");
                dispatcher.toggleServiceMode();
            } catch (Throwable ignored) {}
        }
    }

    public void shutdown() {
        isShutdown.set(true);
        vhalWorker.shutdownNow();
        oemWorker.shutdownNow();
    }
}
