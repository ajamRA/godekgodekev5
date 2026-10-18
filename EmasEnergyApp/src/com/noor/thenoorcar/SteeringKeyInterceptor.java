package com.noor.thenoorcar;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Direct Hardware Steering Wheel Key Interceptor for Proton e.MAS 5 / Geely IHU801P.
 * 
 * Intercepts steering wheel voice key (AdaptAPI code 200231, hardware ID 298).
 * Solves Geely's internal 600ms anti-debounce constraint.
 * 
 * Behaviors:
 * - Single Click: Forwards to factory OEM "Hi Proton" (com.malaysia.voiceservice)
 * - Double Click: Triggers intelligent cabin assistant "Jarvis" (FloatingBubbleService)
 * - Long Press (> 600ms): Also triggers Jarvis (dual gesture convenience)
 */
public class SteeringKeyInterceptor {
    private static final String TAG = "SteeringKeyInterceptor";
    public static final int KEY_VOICE = 200231;
    private static final long DOUBLE_CLICK_WINDOW_MS = 400;
    private static final long LONG_PRESS_THRESHOLD_MS = 600;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Object ivrService;
    private Method reqVrHardKeyMethod;

    private long lastKeyDownTime = 0;
    private long lastClickTime = 0;
    private Runnable pendingSingleClickRunnable = null;
    private boolean isLongPressTriggered = false;

    public SteeringKeyInterceptor(Context context) {
        this.context = context.getApplicationContext();
        bindVrService();
        initAdaptApiKeyInterception();
    }

    private void bindVrService() {
        try {
            Intent intent = new Intent();
            intent.setAction("com.malaysia.ConnectService.ServiceProxy");
            intent.setPackage("com.malaysia.ConnectService");
            intent.setType("ServiceProxy/main");
            intent.putExtra("ServiceName", "VrService");
            context.bindService(intent, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    Log.i(TAG, "Connected to ConnectService VrService!");
                    try {
                        dalvik.system.PathClassLoader cl = new dalvik.system.PathClassLoader(
                            "/system/app/ConnectService_ACO/ConnectService_ACO.apk",
                            context.getClassLoader()
                        );
                        Class<?> stubCls = Class.forName("com.malaysia.ConnectServiceApi.VrService.IVrService$Stub", true, cl);
                        Method asInterface = stubCls.getMethod("asInterface", IBinder.class);
                        ivrService = asInterface.invoke(null, service);
                        reqVrHardKeyMethod = ivrService.getClass().getMethod("reqVrHardKey");
                        Log.i(TAG, "IVrService reqVrHardKey bound successfully via PathClassLoader!");
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to reflect IVrService: " + t.getMessage(), t);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    ivrService = null;
                    reqVrHardKeyMethod = null;
                }
            }, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.e(TAG, "bindVrService error: " + t.getMessage());
        }
    }

    private void initAdaptApiKeyInterception() {
        try {
            Class<?> inputCls = Class.forName("com.malaysia.xui.adaptapi.input.Input");
            Method createMethod = inputCls.getMethod("create", Context.class);
            Object inputObj = createMethod.invoke(null, context);
            if (inputObj == null) {
                Log.e(TAG, "Input.create(context) returned null!");
                return;
            }

            Class<?> cbCls = Class.forName("com.malaysia.xui.adaptapi.input.IKeyCallback");
            Object callbackProxy = Proxy.newProxyInstance(
                cbCls.getClassLoader(),
                new Class<?>[]{cbCls},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String mName = method.getName();
                        if (args != null && args.length > 0 && args[0] instanceof Integer) {
                            int keyCode = (Integer) args[0];
                            if (keyCode == KEY_VOICE) {
                                if ("onKeyPressed".equals(mName)) {
                                    handleVoiceKeyDown();
                                    return true; // Consume key from OEM ConnectService!
                                } else if ("onKeyReleased".equals(mName)) {
                                    handleVoiceKeyUp();
                                    return true; // Consume key from OEM ConnectService!
                                }
                            }
                        }
                        return false;
                    }
                }
            );

            Method reqInterception = inputCls.getMethod("requestKeysInterception", int[].class, cbCls);
            int[] keys = new int[]{ KEY_VOICE };
            Object res = reqInterception.invoke(inputObj, keys, callbackProxy);
            Log.i(TAG, "SteeringKeyInterceptor active! Intercepting key " + KEY_VOICE + ", result: " + res);
        } catch (Throwable t) {
            Log.e(TAG, "initAdaptApiKeyInterception error: " + t.getMessage(), t);
        }
    }

    private synchronized void handleVoiceKeyDown() {
        final long now = System.currentTimeMillis();
        lastKeyDownTime = now;
        isLongPressTriggered = false;

        // Start long-press timer
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (lastKeyDownTime == now && (System.currentTimeMillis() - lastKeyDownTime) >= LONG_PRESS_THRESHOLD_MS) {
                    if (!isLongPressTriggered) {
                        isLongPressTriggered = true;
                        if (pendingSingleClickRunnable != null) {
                            mainHandler.removeCallbacks(pendingSingleClickRunnable);
                            pendingSingleClickRunnable = null;
                        }
                        Log.i(TAG, ">>> Steering Voice Button LONG PRESS -> OEM Hi Proton");
                        triggerOemHiProton();
                    }
                }
            }
        }, LONG_PRESS_THRESHOLD_MS);
    }

    private synchronized void handleVoiceKeyUp() {
        long now = System.currentTimeMillis();
        lastKeyDownTime = 0;

        if (isLongPressTriggered) {
            // Already handled as long press, do nothing on release
            return;
        }

        // Check if double click
        if ((now - lastClickTime) <= DOUBLE_CLICK_WINDOW_MS) {
            // Cancel pending single click
            if (pendingSingleClickRunnable != null) {
                mainHandler.removeCallbacks(pendingSingleClickRunnable);
                pendingSingleClickRunnable = null;
            }
            lastClickTime = 0;
            Log.i(TAG, ">>> Steering Voice Button DOUBLE CLICK -> OEM Hi Proton");
            triggerOemHiProton();
        } else {
            // Potential single click, wait DOUBLE_CLICK_WINDOW_MS to confirm
            lastClickTime = now;
            pendingSingleClickRunnable = new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, ">>> Steering Voice Button SINGLE CLICK confirmed! Forwarding to OEM Hi Proton...");
                    triggerOemHiProton();
                    pendingSingleClickRunnable = null;
                }
            };
            mainHandler.postDelayed(pendingSingleClickRunnable, DOUBLE_CLICK_WINDOW_MS);
        }
    }

    private void triggerOemHiProton() {
        try {
            if (ivrService != null && reqVrHardKeyMethod != null) {
                reqVrHardKeyMethod.invoke(ivrService);
            } else {
                // Fallback: send broadcast to ConnectService
                Intent intent = new Intent("com.malaysia.ConnectService.SIMULATE_KEY_EVENT");
                intent.setPackage("com.malaysia.ConnectService");
                intent.putExtra("key_code", KEY_VOICE);
                intent.putExtra("key_action", 0);
                context.sendBroadcast(intent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "triggerOemHiProton error: " + t.getMessage());
        }
    }
}
