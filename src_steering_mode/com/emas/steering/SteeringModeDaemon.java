package com.emas.steering;

import java.io.File;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone lifecycle and VHAL bridge. No raw evdev observer masquerades as interception. */
public final class SteeringModeDaemon implements SteeringModeController.VehicleDispatcher {
    public interface TimeProvider { long uptimeMillis(); }
    public interface VehicleLink extends AutoCloseable {
        int read(int propId, int area) throws Exception;
        boolean write(int propId, int area, int value) throws Exception;
        default void close() throws Exception {}
    }
    public interface VehicleLinkFactory { VehicleLink connect() throws Exception; }
    private static final TimeProvider DEFAULT_TIME_PROVIDER = () -> {
        try {
            return (Long) Class.forName("android.os.SystemClock").getMethod("uptimeMillis").invoke(null);
        } catch (ReflectiveOperationException e) { return System.nanoTime() / 1_000_000L; }
    };
    private final TimeProvider timeProvider;
    private final VehicleLinkFactory linkFactory;
    private final SteeringInputSession input;
    private final SteeringModeController controller;
    private final ScheduledExecutorService ticker = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean running = new AtomicBoolean(true), started = new AtomicBoolean();
    private final Object connectionLock = new Object();
    private volatile VehicleLink connection;
    private long nextConnectTime;
    private volatile boolean inputReady;
    private final AtomicBoolean inputClosed = new AtomicBoolean();

    public SteeringModeDaemon() { this(DEFAULT_TIME_PROVIDER); }
    public SteeringModeDaemon(TimeProvider clock) { this(clock, ReflectionLink::new, null); }
    public SteeringModeDaemon(TimeProvider clock, VehicleLinkFactory factory, SteeringInputSession input) {
        this.timeProvider = clock == null ? DEFAULT_TIME_PROVIDER : clock;
        this.linkFactory = factory;
        this.input = input;
        controller = new SteeringModeController(this);
        controller.setRequireVehicleSeed(true);
        controller.setVehicleActionsEnabled(false);
        pollVehicleState();
    }
    public long now() { return timeProvider.uptimeMillis(); }
    public SteeringModeController getController() { return controller; }
    public boolean isInputReady() { return inputReady; }

    private VehicleLink link() {
        synchronized (connectionLock) {
            if (!running.get()) return null;
            if (connection != null) return connection;
            if (now() < nextConnectTime) return null;
            nextConnectTime = now() + 500;
            try {
                connection = linkFactory.connect();
                if (connection == null) throw new IllegalStateException("No vehicle service");
            } catch (Exception e) {
                log("Vehicle service unavailable; read will retry: " + rootCause(e));
                connection = null;
            }
            return connection;
        }
    }
    private void discard(VehicleLink failed) {
        synchronized (connectionLock) {
            if (connection != failed) return;
            connection = null;
            nextConnectTime = now() + 500;
            controller.invalidateVehicleState();
        }
        try { failed.close(); } catch (Exception e) { log("Vehicle close: " + rootCause(e)); }
    }
    public int readIntProp(int propId, int area, int fallback) {
        if (!validProperty(propId) || area != 0) return fallback;
        VehicleLink service = link();
        if (service == null) return fallback;
        try {
            int value = service.read(propId, area);
            if (propId == SteeringModeController.VHAL_PROP_DRIVE_MODE) {
                return (value >= 0 && value <= 3) ? value : fallback;
            } else {
                return (value >= 1 && value <= 3) ? value : fallback;
            }
        } catch (Exception e) {
            discard(service);
            log("Vehicle read failed; state invalidated: " + rootCause(e));
            return fallback;
        }
    }
    public int queryCurrentProperty(int propId, int area) { return readIntProp(propId, area, -1); }
    public boolean sendPropertyInt(int propId, int area, int value) {
        if (!running.get() || !inputReady || area != 0 || !validProperty(propId)) return false;
        if (propId == SteeringModeController.VHAL_PROP_DRIVE_MODE && (value < 0 || value > 3)) return false;
        if (propId == SteeringModeController.VHAL_PROP_REGEN_LEVEL && (value < 1 || value > 3)) return false;
        VehicleLink service = link();
        if (service == null) return false;
        try { return service.write(propId, area, value); }
        catch (Exception e) {
            discard(service);
            log("Vehicle write failed; command not retried: " + rootCause(e));
            return false;
        }
    }
    public void pollVehicleState() {
        long regenVersion = controller.vehicleStateVersion(SteeringModeController.VHAL_PROP_REGEN_LEVEL);
        int regenVal = readIntProp(SteeringModeController.VHAL_PROP_REGEN_LEVEL, 0, -1);
        if (regenVal >= 1 && regenVal <= 3) controller.applyVehicleSnapshot(SteeringModeController.VHAL_PROP_REGEN_LEVEL, regenVal, regenVersion);

        long driveVersion = controller.vehicleStateVersion(SteeringModeController.VHAL_PROP_DRIVE_MODE);
        int driveVal = readIntProp(SteeringModeController.VHAL_PROP_DRIVE_MODE, 0, -1);
        if (driveVal >= 0 && driveVal <= 3) controller.applyVehicleSnapshot(SteeringModeController.VHAL_PROP_DRIVE_MODE, driveVal, driveVersion);
    }

    /** Adapter calls this only for intercepted Android KeyEvents, with uptime metadata. */
    public boolean onInterceptedKey(SteeringInputSession.Key key) {
        if (!running.get() || !inputReady || key == null || key.metadata == null) return false;
        log("Intercepted key: code=" + key.keyCode + " down=" + key.down + " time=" + key.eventTime + " now=" + now());
        if (key.keyCode != SteeringModeController.KEY_MEDIA_NEXT && key.keyCode != SteeringModeController.KEY_MEDIA_PREVIOUS && key.keyCode != SteeringModeController.KEY_VOLUME_DOWN) return false;
        if (key.eventTime <= 0 || key.eventTime > now() + 2000) {
            disableInput("Timestamp anomaly: " + key.eventTime + " vs " + now());
            return false;
        }
        if (!key.down && !controller.hasPendingGesture(key.keyCode)) return false;
        if (key.down) controller.onKeyDown(key.keyCode, key.eventTime, key.repeatCount, key.metadata);
        else controller.onKeyUp(key.keyCode, key.eventTime, key.metadata);
        // Controller is the sole owner of OEM replay, including long-press callbacks.
        return true;
    }
    public void forwardMediaKey(int keyCode, boolean down, long dt, long et) {
        forwardMediaKey(new SteeringModeController.KeyEventToDispatch(keyCode, down, dt, et));
    }
    public void forwardMediaKey(int keyCode, boolean down, long dt, long et, int repeat, int flags) {
        forwardMediaKey(new SteeringModeController.KeyEventToDispatch(keyCode, down, dt, et, repeat, flags));
    }
    public void forwardMediaKey(SteeringModeController.KeyEventToDispatch event) {
        if (!inputReady || input == null) return;
        try { input.forward(event); }
        catch (Exception e) { disableInput("OEM forwarding failed: " + rootCause(e)); }
    }
    private void disableInput(String reason) {
        inputReady = false;
        controller.setVehicleActionsEnabled(false);
        log(reason);
        if (input != null && inputClosed.compareAndSet(false, true)) try { input.close(); }
        catch (Exception e) { log("Input release failed: " + rootCause(e)); }
    }
    public void playAudioFeedback(String prompt) {
        log(prompt);
        File tone = null;
        float vol = 0.25f; // User confirmed 25% volume
        if (prompt != null && prompt.startsWith("Mode")) {
            File f = new File("/data/local/window/mode_chime.wav");
            if (f.isFile()) tone = f;
        } else if (prompt != null && prompt.startsWith("Regen")) {
            File f = new File("/data/local/window/regen_chime.wav");
            if (f.isFile()) tone = f;
        } else if (prompt != null && prompt.startsWith("ServiceMode")) {
            File f = new File("/data/local/window/mode_chime.wav");
            if (f.isFile()) tone = f;
            vol = 0.35f;
        }
        if (tone != null && tone.isFile()) {
            playWavAudioAsync(tone, vol);
        }
    }

    @Override
    public void toggleServiceMode() {
        log("Toggling Service Mode via 5-second hold on button BAWAH...");
        try {
            Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "/data/local/window/service_mode.sh", "toggle"});
        } catch (Exception e) {
            log("Error executing service_mode.sh: " + e.getMessage());
        }
    }

    private static void playWavAudioAsync(File file, float volume) {
        if (file == null || !file.isFile()) return;
        new Thread(() -> {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                if (bytes.length < 44) return;
                int channels = (bytes[22] & 0xFF) | ((bytes[23] & 0xFF) << 8);
                int sampleRate = (bytes[24] & 0xFF) | ((bytes[25] & 0xFF) << 8) | ((bytes[26] & 0xFF) << 16) | ((bytes[27] & 0xFF) << 24);
                int dataPos = 12;
                int dataLen = 0;
                while (dataPos < bytes.length - 8) {
                    String chunkId = new String(bytes, dataPos, 4, "US-ASCII");
                    int chunkLen = (bytes[dataPos + 4] & 0xFF) | ((bytes[dataPos + 5] & 0xFF) << 8) | ((bytes[dataPos + 6] & 0xFF) << 16) | ((bytes[dataPos + 7] & 0xFF) << 24);
                    if ("data".equals(chunkId)) {
                        dataPos += 8;
                        dataLen = Math.min(chunkLen, bytes.length - dataPos);
                        break;
                    }
                    dataPos += 8 + chunkLen;
                    if ((chunkLen & 1) != 0) dataPos++;
                }
                if (dataLen <= 0) return;

                Class<?> audioAttrsBuilderCls = Class.forName("android.media.AudioAttributes$Builder");
                Object attrsBuilder = audioAttrsBuilderCls.getDeclaredConstructor().newInstance();
                audioAttrsBuilderCls.getMethod("setUsage", int.class).invoke(attrsBuilder, 4); // USAGE_ASSISTANCE_SONIFICATION
                audioAttrsBuilderCls.getMethod("setContentType", int.class).invoke(attrsBuilder, 4); // CONTENT_TYPE_SONIFICATION
                Object audioAttrs = audioAttrsBuilderCls.getMethod("build").invoke(attrsBuilder);

                Class<?> audioFormatBuilderCls = Class.forName("android.media.AudioFormat$Builder");
                Object formatBuilder = audioFormatBuilderCls.getDeclaredConstructor().newInstance();
                int channelMask = (channels == 1) ? 4 : 12; // CHANNEL_OUT_MONO : CHANNEL_OUT_STEREO
                audioFormatBuilderCls.getMethod("setEncoding", int.class).invoke(formatBuilder, 2); // ENCODING_PCM_16BIT
                audioFormatBuilderCls.getMethod("setSampleRate", int.class).invoke(formatBuilder, sampleRate);
                audioFormatBuilderCls.getMethod("setChannelMask", int.class).invoke(formatBuilder, channelMask);
                Object audioFormat = audioFormatBuilderCls.getMethod("build").invoke(formatBuilder);

                Class<?> audioTrackCls = Class.forName("android.media.AudioTrack");
                Method getMinBufferSize = audioTrackCls.getMethod("getMinBufferSize", int.class, int.class, int.class);
                int minBuf = (Integer) getMinBufferSize.invoke(null, sampleRate, channelMask, 2);

                Class<?> trackBuilderCls = Class.forName("android.media.AudioTrack$Builder");
                Object trackBuilder = trackBuilderCls.getDeclaredConstructor().newInstance();
                Class<?> audioAttrsCls = Class.forName("android.media.AudioAttributes");
                Class<?> audioFormatCls = Class.forName("android.media.AudioFormat");
                trackBuilderCls.getMethod("setAudioAttributes", audioAttrsCls).invoke(trackBuilder, audioAttrs);
                trackBuilderCls.getMethod("setAudioFormat", audioFormatCls).invoke(trackBuilder, audioFormat);
                trackBuilderCls.getMethod("setBufferSizeInBytes", int.class).invoke(trackBuilder, Math.max(minBuf, 32768));
                trackBuilderCls.getMethod("setTransferMode", int.class).invoke(trackBuilder, 1); // MODE_STREAM
                Object track = trackBuilderCls.getMethod("build").invoke(trackBuilder);

                audioTrackCls.getMethod("setVolume", float.class).invoke(track, volume);
                audioTrackCls.getMethod("play").invoke(track);
                audioTrackCls.getMethod("write", byte[].class, int.class, int.class).invoke(track, bytes, dataPos, dataLen);

                int bytesPerFrame = channels * 2;
                long durMs = (dataLen * 1000L) / (sampleRate * bytesPerFrame);
                Thread.sleep(Math.max(100, durMs + 50));
                audioTrackCls.getMethod("stop").invoke(track);
                audioTrackCls.getMethod("release").invoke(track);
            } catch (Throwable t) {
                // Non-Android environments or audio errors fail silently
            }
        }, "steering-chime").start();
    }
    public void start() {
        if (!running.get() || !started.compareAndSet(false, true)) return;
        if (input != null) try {
            inputReady = input.start(this::onInterceptedKey);
            controller.setVehicleActionsEnabled(inputReady);
            if (!inputReady) disableInput("Interception rejected; factory controls remain active");
        } catch (Exception e) { disableInput("Interception unavailable: " + rootCause(e)); }
        if (!inputReady) log("MONITOR ONLY: no verified input adapter; vehicle writes disabled");
        ticker.scheduleAtFixedRate(() -> {
            if (!running.get() || !inputReady) return;
            long time = now();
            controller.checkHoldTimeout(time);
            controller.checkPendingSingleClicks(time);
        }, 20, 20, TimeUnit.MILLISECONDS);
        ticker.scheduleWithFixedDelay(this::pollVehicleState, 500, 500, TimeUnit.MILLISECONDS);
    }
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        disableInput("Stopping; releasing interception");
        ticker.shutdownNow();
        controller.shutdown();
        VehicleLink service;
        synchronized (connectionLock) { service = connection; connection = null; }
        if (service != null) try { service.close(); } catch (Exception e) { log("Close failed: " + rootCause(e)); }
    }
    private static boolean validProperty(int prop) {
        return prop == SteeringModeController.VHAL_PROP_DRIVE_MODE || prop == SteeringModeController.VHAL_PROP_REGEN_LEVEL;
    }
    private static void log(String message) { System.out.println("[SteeringModeDaemon] " + message); }
    private static String rootCause(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.toString();
    }

    private static final class ReflectionLink implements VehicleLink {
        private final Object service;
        private final Method read, write, value, status;
        private final Constructor<?> constructor;
        ReflectionLink() throws Exception {
            Class<?> binderClass = Class.forName("android.os.IBinder");
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "car_service");
            if (binder == null) throw new IllegalStateException("car_service is not ready");
            Object car = Class.forName("android.car.ICar$Stub").getMethod("asInterface", binderClass).invoke(null, binder);
            Object propertyBinder = car.getClass().getMethod("getCarService", String.class).invoke(car, "property");
            Class<?> stub;
            try { stub = Class.forName("android.car.hardware.property.ICarProperty$Stub"); }
            catch (ClassNotFoundException e) { stub = Class.forName("android.car.ICarProperty$Stub"); }
            service = stub.getMethod("asInterface", binderClass).invoke(null, propertyBinder);
            if (service == null) throw new IllegalStateException("property service is not ready");
            Class<?> cpv = Class.forName("android.car.hardware.CarPropertyValue");
            constructor = cpv.getConstructor(int.class, int.class, Object.class);
            read = service.getClass().getMethod("getProperty", int.class, int.class);
            write = service.getClass().getMethod("setProperty", cpv);
            value = cpv.getMethod("getValue");
            status = cpv.getMethod("getStatus");
        }
        public int read(int prop, int area) throws Exception {
            Object property = read.invoke(service, prop, area);
            if (property == null || ((Number) status.invoke(property)).intValue() != 0) return -1;
            Object payload = value.invoke(property);
            if (payload instanceof Integer) return (Integer) payload;
            if (payload instanceof byte[] && ((byte[]) payload).length == 1) return ((byte[]) payload)[0] & 255;
            return -1;
        }
        public boolean write(int prop, int area, int target) throws Exception {
            write.invoke(service, constructor.newInstance(prop, area, new byte[]{(byte) target}));
            return true; // Binder accepted; vehicle confirmation is supplied by later telemetry.
        }
    }
    public static void main(String[] args) throws Exception {
        SteeringInputSession input = null;
        if (args.length != 0) {
            if (args.length != 1 || !args[0].startsWith("--input-adapter=")) throw new IllegalArgumentException("Expected --input-adapter=qualified.OemSession");
            String adapterClassName = args[0].substring("--input-adapter=".length());
            if ("none".equalsIgnoreCase(adapterClassName)) {
                input = null; // Monitor only mode
            } else {
                input = (SteeringInputSession) Class.forName(adapterClassName).getDeclaredConstructor().newInstance();
            }
        } else {
            // Default active vehicle adapter: Native Android InputManager Binder
            input = new EmasDirectInputAdapter();
        }
        SteeringModeDaemon daemon = new SteeringModeDaemon(DEFAULT_TIME_PROVIDER, ReflectionLink::new, input);
        daemon.getController().setGestureTrigger(SteeringModeController.GestureTrigger.HOLD_SHORT);
        Runtime.getRuntime().addShutdownHook(new Thread(daemon::stop));
        daemon.start();
        try { while (daemon.running.get()) Thread.sleep(1000); }
        finally { daemon.stop(); }
    }
}
