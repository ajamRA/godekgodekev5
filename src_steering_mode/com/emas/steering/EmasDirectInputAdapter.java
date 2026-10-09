package com.emas.steering;

import android.hardware.input.IKeyEventObserver;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import java.lang.reflect.Method;

/**
 * Native Direct Binder Input Adapter for Proton e.MAS 5 (IHU801P).
 * Hooks directly into Android InputManagerService via IKeyEventObserver Binder IPC.
 */
public class EmasDirectInputAdapter implements SteeringInputSession {

    private static final String TAG = "EmasDirectInputAdapter";
    private Sink sink;
    private Object inputMgr;
    private KeyObserverImpl observer;
    private volatile boolean running = false;

    private class KeyObserverImpl extends IKeyEventObserver.Stub {
        @Override
        public void onKeyEvent(boolean down, int keyCode) throws RemoteException {
            if (!running || sink == null) return;
            System.out.println("[EmasDirectInputAdapter] onKeyEvent: down=" + down + ", keyCode=" + keyCode);

            int canonicalCode = -1;
            if (keyCode == 305 || keyCode == SteeringModeController.KEY_MEDIA_NEXT || keyCode == 332 || keyCode == 340 || keyCode == 344 || keyCode == 200087) {
                canonicalCode = SteeringModeController.KEY_MEDIA_NEXT;
            } else if (keyCode == 304 || keyCode == SteeringModeController.KEY_MEDIA_PREVIOUS || keyCode == 331 || keyCode == 341 || keyCode == 345 || keyCode == 200088) {
                canonicalCode = SteeringModeController.KEY_MEDIA_PREVIOUS;
            } else if (keyCode == 302 || keyCode == 25 || keyCode == SteeringModeController.KEY_VOLUME_DOWN) {
                canonicalCode = SteeringModeController.KEY_VOLUME_DOWN;
            }

            if (canonicalCode != -1) {
                long now = SystemClock.uptimeMillis();
                SteeringModeController.KeyMetadata meta = new SteeringModeController.KeyMetadata(
                    keyCode, 0, 0, 1, 0, 0, 0
                );
                sink.onKey(new Key(canonicalCode, down, now, 0, meta));
            }
        }
    }

    @Override
    public boolean start(Sink sink) throws Exception {
        this.sink = sink;
        this.running = true;

        IBinder inputBinder = ServiceManager.getService("input");
        if (inputBinder == null) {
            throw new IllegalStateException("input service unavailable");
        }

        Class<?> stubCls = Class.forName("android.hardware.input.IInputManager$Stub");
        Method asInterface = stubCls.getMethod("asInterface", IBinder.class);
        this.inputMgr = asInterface.invoke(null, inputBinder);

        this.observer = new KeyObserverImpl();
        // Register steering wheel keys:
        // Next: 305, 332, 340, 344, 87, 200087
        // Prev: 304, 331, 341, 345, 88, 200088
        // Down (Vol Down): 302, 25, 200025
        int[] keys = new int[]{
            305, 304, 302, 87, 88, 25, 331, 332, 340, 341, 344, 345,
            286, 287, 300, 348, 298, 299, 306, 307, 200087, 200088, 200025
        };

        Class<?> observerCls = Class.forName("android.hardware.input.IKeyEventObserver");
        Method req = inputMgr.getClass().getMethod("requestKeysInterception", int[].class, observerCls);
        req.invoke(inputMgr, keys, observer);

        System.out.println("[" + TAG + "] Direct Native Android IInputManager key interception active.");
        return true;
    }

    @Override
    public void forward(SteeringModeController.KeyEventToDispatch event) throws Exception {
        if (event != null && !event.isDown) {
            if (event.keyCode == SteeringModeController.KEY_VOLUME_DOWN) {
                Runtime.getRuntime().exec(new String[]{"input", "keyevent", "25"});
            } else if (event.keyCode == SteeringModeController.KEY_MEDIA_NEXT) {
                Runtime.getRuntime().exec(new String[]{"input", "keyevent", "87"});
            } else if (event.keyCode == SteeringModeController.KEY_MEDIA_PREVIOUS) {
                Runtime.getRuntime().exec(new String[]{"input", "keyevent", "88"});
            }
        }
    }

    @Override
    public void close() throws Exception {
        running = false;
        if (inputMgr != null && observer != null) {
            try {
                Class<?> observerCls = Class.forName("android.hardware.input.IKeyEventObserver");
                Method abandon = inputMgr.getClass().getMethod("abandonKeysInterception", observerCls);
                abandon.invoke(inputMgr, observer);
            } catch (Throwable ignored) {}
        }
        System.out.println("[" + TAG + "] EmasDirectInputAdapter stopped.");
    }
}
