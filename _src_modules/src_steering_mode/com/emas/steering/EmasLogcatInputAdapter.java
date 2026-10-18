package com.emas.steering;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production Input Adapter for Proton e.MAS 5 (IHU801P).
 * Real-time event listener hooking into DefaultInputService / CAR.INPUT pipeline.
 */
public class EmasLogcatInputAdapter implements SteeringInputSession {

    private static final String TAG = "EmasLogcatInputAdapter";
    private Process logcatProcess;
    private Thread workerThread;
    private volatile boolean running = false;
    private Sink sink;

    // Pattern matching: onKeyEvent(KeyEvent { action=ACTION_DOWN, keyCode=305, scanCode=0, metaState=0, flags=0x0, repeatCount=0, eventTime=2344127, downTime=2344127, ...
    private static final Pattern KEY_PATTERN = Pattern.compile(
        "action=(ACTION_DOWN|ACTION_UP),\\s*keyCode=(\\d+).*?repeatCount=(\\d+).*?eventTime=(\\d+).*?downTime=(\\d+)"
    );

    @Override
    public boolean start(Sink sink) throws Exception {
        this.sink = sink;
        this.running = true;

        // Clear existing logcat buffer first
        try {
            Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();
        } catch (Throwable ignored) {}

        // Stream real-time key events from DefaultInputService / CAR.INPUT
        ProcessBuilder pb = new ProcessBuilder("logcat", "-v", "brief", "-s", "DefaultInputService:I", "CAR.INPUT:D");
        pb.redirectErrorStream(true);
        logcatProcess = pb.start();

        workerThread = new Thread(this::readLoop, "EmasInputReader");
        workerThread.setDaemon(true);
        workerThread.start();

        System.out.println("[" + TAG + "] EmasLogcatInputAdapter started and listening to vehicle steering wheel events.");
        return true;
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (!line.contains("onKeyEvent")) continue;

                Matcher m = KEY_PATTERN.matcher(line);
                if (m.find()) {
                    boolean isDown = "ACTION_DOWN".equals(m.group(1));
                    int rawCode = Integer.parseInt(m.group(2));
                    int repeat = Integer.parseInt(m.group(3));
                    long eventTime = Long.parseLong(m.group(4));
                    long downTime = Long.parseLong(m.group(5));

                    int canonicalCode = -1;
                    if (rawCode == 305 || rawCode == SteeringModeController.KEY_MEDIA_NEXT) {
                        canonicalCode = SteeringModeController.KEY_MEDIA_NEXT;
                    } else if (rawCode == 304 || rawCode == SteeringModeController.KEY_MEDIA_PREVIOUS) {
                        canonicalCode = SteeringModeController.KEY_MEDIA_PREVIOUS;
                    }

                    if (canonicalCode != -1 && sink != null) {
                        SteeringModeController.KeyMetadata meta = new SteeringModeController.KeyMetadata(
                            rawCode, 0, 0, 1, 0, 0, 0
                        );
                        sink.onKey(new Key(canonicalCode, isDown, eventTime, repeat, meta));
                    }
                }
            }
        } catch (Throwable t) {
            if (running) {
                System.err.println("[" + TAG + "] Reader error: " + t.getMessage());
            }
        }
    }

    @Override
    public void forward(SteeringModeController.KeyEventToDispatch event) throws Exception {
        // Forwarded OEM events (single click, cluster hold, 10s reboot) pass through to framework
    }

    @Override
    public void close() throws Exception {
        running = false;
        if (logcatProcess != null) {
            logcatProcess.destroy();
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
        System.out.println("[" + TAG + "] EmasLogcatInputAdapter stopped.");
    }
}
