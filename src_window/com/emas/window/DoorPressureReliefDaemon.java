package com.emas.window;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Enhanced Cabin Pressure Relief & Ventilation Daemon for Proton e.MAS / Geely IHU801P.
 * 
 * Features:
 * 1. Door Pressure Relief: drops window to crackPercent (default 7%, configurable 3-20%) when door opens,
 *    and closes it when door closes.
 * 2. Dynamic Config: reads /data/local/window/config.json (on/off toggle, crack percentage).
 * 3. Cabin Ventilation Mode: opens all 4 windows to crackPercent for a set time (default 30 mins)
 *    and auto-closes them when time expires.
 * 4. Embedded HTTP REST API Server on port 8888 (with CORS) for instant webapp / phone control.
 */
public class DoorPressureReliefDaemon {

    static final String CONFIG_PATH = "/data/local/window/config.json";
    static final int HTTP_PORT = 8888;
    private final java.util.concurrent.ThreadPoolExecutor httpWorkers = new java.util.concurrent.ThreadPoolExecutor(
            4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<Runnable>(16));

    // Door sensor prop IDs (1 = CLOSED, 0 = OPEN)
    static final int PROP_DOOR_FL = 678428815; // 0x2870008F
    static final int PROP_DOOR_FR = 678428816; // 0x28700090
    static final int PROP_DOOR_RL = 678428817; // 0x28700091
    static final int PROP_DOOR_RR = 678428818; // 0x28700092

    // Window READ prop IDs
    static final int PROP_READ_FL = 678428829; // 0x2870009D
    static final int PROP_READ_FR = 678428830; // 0x2870009E
    static final int PROP_READ_RL = 678428831; // 0x2870009F
    static final int PROP_READ_RR = 678428832; // 0x287000A0

    // Window WRITE prop IDs (FL/FR swapped in OEM VHAL)
    static final int PROP_WRITE_FL = 678428830; // 0x2870009E
    static final int PROP_WRITE_FR = 678428829; // 0x2870009D
    static final int PROP_WRITE_RL = 678428831; // 0x2870009F
    static final int PROP_WRITE_RR = 678428832; // 0x287000A0
    static final int PROP_WRITE_ALL = 678428834; // 0x287000A2

    // Energy, Powertrain & Climate VHAL IDs
    static final int PROP_BATTERY_SOC = 557885165;       // float %
    static final int PROP_VEHICLE_SPEED = 291504647;     // float km/h
    static final int PROP_VEHICLE_GEAR = 289408001;      // int: 1=N, 2=R, 4=P, 8=D
    static final int PROP_GEAR_MCU = 678428908;          // 0x287000EC INFO_ID_VDRIVEINFO_GEAR_POSITION (5=P, !=5 D/R/N)
    static final int PROP_OUTSIDE_TEMP = 557884279;      // int: (raw - 80) / 2 = °C
    static final int PROP_HVAC_AC_ON = 354419973;        // boolean
    static final int PROP_HVAC_BLOWER = 356517120;       // int (0..8)
    static final int PROP_HVAC_SETPOINT = 0x15600503;    // float (°C)
    static final int PROP_HVAC_DEFROST = 354419975;      // boolean
    static final int PROP_REAR_DEFROST = 354419988;      // boolean

    // MCU HVAC byte properties (0x2870xxxx)
    static final int PROP_HVAC_TEMP_DRIVER = 678428679; // 0x28700007 zone 1, byte (C-17)*2
    static final int PROP_HVAC_TEMP_PASS = 678428680;   // 0x28700008 zone 4, byte (C-17)*2
    static final int PROP_HVAC_TEMP_DUAL = 678428682;   // 0x2870000A zone 0, 1 = dual on
    static final int PROP_HVAC_FAN_SPEED = 678428677;   // 0x28700005 zone 0, byte 0..8
    static final int PROP_HVAC_POWER_ON = 678428689;    // 0x28700011 zone 0, byte 0/1
    static final int PROP_HVAC_AC_COMP = 678428673;     // 0x28700001 zone 0, byte 0/1
    static final int PROP_HVAC_CIRC_MODE = 678428686;   // 0x2870000e

    // Trunk / Tailgate motor control
    static final int PROP_TRUNK_ACTION_REQ = 0x28700049; // 678428745
    static final int PROP_DOOR_TRUNK = 678428819;       // 0x28700093
    static final int PROP_LOCK_TRUNK = 678428825;       // 0x28700099

    // Door Lock prop IDs (1 = LOCKED, 0 = UNLOCKED)
    static final int PROP_LOCK_FL = 678428821;         // 0x28700095
    static final int PROP_LOCK_FR = 678428822;         // 0x28700096
    static final int PROP_LOCK_RL = 678428823;         // 0x28700097
    static final int PROP_LOCK_RR = 678428824;         // 0x28700098
    static final int PROP_CAR_DOOR_LOCK = 0x16200B02;   // 371198722

    // Drive Mode & Powertrain Dynamics VHAL IDs
    static final int PROP_DRIVE_MODE = 0x287000EA;        // 678428906 INFO_ID_VDRIVEINFO_DRIVER_MODE (UNVERIFIED candidate: 1=Comfort, 2=Sport, 3=Eco, 4=Eco Plus)
    static final int PROP_REGEN_LEVEL = 0x287001B1;       // 678429105 INFO_ID_TAXIING_FEEDBACK_LEVEL (1=Low, 2=Medium, 3=High)
    static final int PROP_VBF_STATUS = 0x287002D6;        // 678429398 INFO_ID_ESC_VBF_STATUS (1=Comfort, 2=Sport)
    static final int PROP_VBF_SET = 0x287002D8;           // 678429400 INFO_ID_VBF_SWITCH_SET (1=Comfort, 2=Sport)
    static final int PROP_CST_STATUS = 0x287002D3;        // 678429395 INFO_ID_ESC_CST_STATUS (0=Off, 1=On)
    static final int PROP_CST_SET = 0x287002D5;           // 678429397 INFO_ID_CST_SWITCH_SET (0=Off, 1=On)
    static final int PROP_EPS_MODE_SET = 0x28700289;      // 678429321 INFO_ID_MMI_EPS_MODSETSELECTION (1=Light, 2=Comfort, 3=Sport)

    static final int[] DOOR_PROPS = {PROP_DOOR_FL, PROP_DOOR_FR, PROP_DOOR_RL, PROP_DOOR_RR};
    static final int[] WIN_READ_PROPS = {PROP_READ_FL, PROP_READ_FR, PROP_READ_RL, PROP_READ_RR};
    static final int[] WIN_WRITE_PROPS = {PROP_WRITE_FL, PROP_WRITE_FR, PROP_WRITE_RL, PROP_WRITE_RR};
    static final String[] NAMES = {"FL (Driver)", "FR (Passenger)", "RL (Rear Left)", "RR (Rear Right)"};

    // Configuration state
    public volatile boolean enabled = true;
    public volatile int crackPercent = 7; // default 7% (gentle crack)
    public volatile boolean ventilationMode = false;
    public volatile int ventilationDurationMin = 30;
    public volatile long ventilationStartTime = 0;
    private static String lastAppliedAvasDeployId = "";
    private static String lastAppliedLockDeployId = "";
    private static String lastAppliedUnlockDeployId = "";

    // Candidate drive/CST/VBF contracts remain disabled pending factory trace validation.
    private final SafeVehicleCommands commands = new SafeVehicleCommands(this::dispatchProperty, false);

    // Welcome / Unlock sound settings
    public volatile boolean unlockSoundEnabled = true;
    public volatile String unlockSoundPreset = "mario_coin";
    public volatile String unlockSoundPath = "/data/local/window/locksounds/unlock_active.wav";
    public volatile String unlockSoundOutput = "both"; // "external", "cabin", "both"
    private static volatile boolean carWasLocked = false;
    private static volatile boolean unlockEventPending = false;
    private static volatile long unlockTimestamp = 0;
    private static volatile boolean hasPlayedDoaThisSession = false;
    private static volatile long parkedStartTime = 0;

    // ABRP Live Telemetry
    public static volatile String abrpToken = "afe5e7d9-1bf9-41ef-b438-143905977494";
    public static final String ABRP_API_KEY = "32b2162f-9599-4647-8139-66e9f9528370";
    private static volatile long lastAbrpSyncTime = 0;

    private Object propService;
    private Method getPropMethod;
    private Method setPropMethod;
    private Constructor<?> cpvCtor;
    private long lastConfigMtime = 0;

    public static void main(String[] args) {
        System.out.println("=== EMAS CABIN PRESSURE RELIEF & VENTILATION DAEMON v2.2 ===");
        DoorPressureReliefDaemon daemon = new DoorPressureReliefDaemon();
        daemon.loadConfig();
        daemon.initLockSound();
        daemon.initUnlockSound();
        daemon.startHttpServer();
        daemon.run();
    }

    public void initLockSound() {
        try {
            File activeLock = new File("/data/local/window/locksounds/lock_active.wav");
            if (activeLock.exists() && activeLock.length() > 1000) {
                applyLockSound(activeLock.getAbsolutePath());
            }
        } catch (Throwable t) {
            System.err.println("initLockSound error: " + t.getMessage());
        }
    }

    public void initUnlockSound() {
        try {
            File locksoundsDir = new File("/data/local/window/locksounds");
            if (!locksoundsDir.exists()) locksoundsDir.mkdirs();
            File activeUnlock = new File("/data/local/window/locksounds/unlock_active.wav");
            if (!activeUnlock.exists() || activeUnlock.length() < 500) {
                File preset = new File("/data/local/window/locksounds/unlock_mario_coin.wav");
                if (preset.exists() && preset.length() > 500) {
                    copyFile(preset, activeUnlock);
                    System.out.println("initUnlockSound: Initialized active unlock sound with Mario Coin preset");
                }
            } else {
                System.out.println("initUnlockSound: Active unlock sound ready (" + activeUnlock.length() + " bytes)");
            }
        } catch (Throwable t) {
            System.err.println("initUnlockSound error: " + t.getMessage());
        }
    }

    public synchronized boolean isCarLocked() {
        int lFL = readIntProp(PROP_LOCK_FL, 0, -1);
        int lFR = readIntProp(PROP_LOCK_FR, 0, -1);
        if (lFL == 1 || lFR == 1) return true;
        if (lFL == 0 && lFR == 0) return false;

        return readBoolProp(PROP_CAR_DOOR_LOCK, 1, false);
    }

    public synchronized void loadConfig() {
        File f = new File(CONFIG_PATH);
        if (!f.exists()) {
            saveConfig();
            return;
        }
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            String json = sb.toString();

            // Simple parser
            if (json.contains("\"enabled\"")) {
                enabled = json.contains("\"enabled\":true") || json.contains("\"enabled\": true");
            }
            int crackIdx = json.indexOf("\"crack_percent\":");
            if (crackIdx != -1) {
                int start = crackIdx + 16;
                while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
                int end = start;
                while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
                if (end > start) {
                    crackPercent = Math.max(3, Math.min(25, Integer.parseInt(json.substring(start, end))));
                }
            }
            if (json.contains("\"ventilation_mode\"")) {
                ventilationMode = json.contains("\"ventilation_mode\":true") || json.contains("\"ventilation_mode\": true");
            }
            int durIdx = json.indexOf("\"ventilation_duration_min\":");
            if (durIdx != -1) {
                int start = durIdx + 27;
                while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
                int end = start;
                while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
                if (end > start) {
                    ventilationDurationMin = Math.max(1, Math.min(180, Integer.parseInt(json.substring(start, end))));
                }
            }
            if (json.contains("\"unlock_sound_enabled\"")) {
                unlockSoundEnabled = json.contains("\"unlock_sound_enabled\":true") || json.contains("\"unlock_sound_enabled\": true");
            }
            String outMode = extractJsonString(json, "unlock_sound_output");
            if (outMode != null && !outMode.isEmpty()) {
                unlockSoundOutput = outMode;
            }
            String preset = extractJsonString(json, "unlock_sound_preset");
            if (preset != null && !preset.isEmpty()) {
                unlockSoundPreset = preset;
            }
            String aTok = extractJsonString(json, "abrp_token");
            if (aTok != null && !aTok.isEmpty()) {
                abrpToken = aTok;
            }
            lastConfigMtime = f.lastModified();
        } catch (Throwable t) {
            System.err.println("loadConfig error: " + t.getMessage());
        }
    }

    public synchronized void saveConfig() {
        try {
            File f = new File(CONFIG_PATH);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileWriter fw = new FileWriter(f);
            fw.write("{\n" +
                    "  \"enabled\": " + enabled + ",\n" +
                    "  \"crack_percent\": " + crackPercent + ",\n" +
                    "  \"ventilation_mode\": " + ventilationMode + ",\n" +
                    "  \"ventilation_duration_min\": " + ventilationDurationMin + ",\n" +
                    "  \"ventilation_start_time\": " + ventilationStartTime + ",\n" +
                    "  \"unlock_sound_enabled\": " + unlockSoundEnabled + ",\n" +
                    "  \"unlock_sound_output\": \"" + unlockSoundOutput + "\",\n" +
                    "  \"unlock_sound_preset\": \"" + unlockSoundPreset + "\",\n" +
                    "  \"abrp_token\": \"" + (abrpToken != null ? abrpToken : "") + "\"\n" +
                    "}\n");
            fw.close();
            lastConfigMtime = f.lastModified();
        } catch (Throwable t) {
            System.err.println("saveConfig error: " + t.getMessage());
        }
    }

    private static void downloadFile(String fileUrl, File dest) {
        try {
            if (fileUrl.startsWith("/")) {
                fileUrl = "https://emas.43-159-63-29.nip.io" + fileUrl;
            }
            URL u = new URL(fileUrl);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(20000);
            if (conn.getResponseCode() == 200) {
                InputStream in = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(dest);
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                }
                fos.close();
                in.close();
                System.out.println("Downloaded file -> " + dest.getAbsolutePath() + " (" + dest.length() + " bytes)");
            }
        } catch (Throwable t) {
            System.err.println("downloadFile error: " + t.getMessage());
        }
    }

    private static void copyFile(File src, File dst) {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                fos.write(buf, 0, r);
            }
        } catch (Throwable t) {
            System.err.println("copyFile error: " + t.getMessage());
        }
    }

    public static boolean applyLockSound(String wavPath) {
        try {
            java.nio.file.Path active = java.nio.file.Paths.get("/sdcard/V3_Vehicle_Locked_Single.wav");
            java.nio.file.Path approved = java.nio.file.Paths.get(active + ".known-good.sha256");
            if (!java.nio.file.Files.isRegularFile(approved, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Provision a reviewed known-good SHA-256 before audio deployment");
            }
            if (java.nio.file.Files.size(approved) > 128) throw new IOException("Invalid approval file");
            String goodHash = new String(java.nio.file.Files.readAllBytes(approved), "US-ASCII").trim();
            java.nio.file.Path source = java.nio.file.Paths.get(wavPath);
            AtomicWavDeployment.validate(source);
            AtomicWavDeployment.deploy(source, active, AtomicWavDeployment.sha256(source), goodHash);
            System.out.println("Lock WAV installed atomically; service reload/playback NOT performed");
            return true;
        } catch (Exception e) {
            System.err.println("Lock WAV deployment failed: " + e);
            for (Throwable suppressed : e.getSuppressed()) System.err.println("Rollback error: " + suppressed);
            return false;
        }
    }

    private static volatile String lastPlayedMegaphoneId = "";

    public static void playInCabinAudio(File f) {
        try {
            if (!f.exists()) return;
            f.setReadable(true, false);
            byte[] fileBytes;
            try (FileInputStream fis = new FileInputStream(f)) {
                fileBytes = new byte[(int) f.length()];
                int readTotal = 0;
                while (readTotal < fileBytes.length) {
                    int r = fis.read(fileBytes, readTotal, fileBytes.length - readTotal);
                    if (r == -1) break;
                    readTotal += r;
                }
            }

            if (fileBytes.length < 44) return;
            String riff = new String(fileBytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
            String wave = new String(fileBytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (!"RIFF".equals(riff) || !"WAVE".equals(wave)) return;

            int sampleRate = 48000;
            short channels = 1;
            short bitsPerSample = 16;
            int dataPos = -1;
            int dataLen = 0;

            int pos = 12;
            while (pos < fileBytes.length - 8) {
                String chunkId = new String(fileBytes, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
                int chunkLen = (fileBytes[pos + 4] & 0xFF) |
                        ((fileBytes[pos + 5] & 0xFF) << 8) |
                        ((fileBytes[pos + 6] & 0xFF) << 16) |
                        ((fileBytes[pos + 7] & 0xFF) << 24);
                if ("fmt ".equals(chunkId)) {
                    channels = (short) ((fileBytes[pos + 10] & 0xFF) | ((fileBytes[pos + 11] & 0xFF) << 8));
                    sampleRate = (fileBytes[pos + 12] & 0xFF) |
                            ((fileBytes[pos + 13] & 0xFF) << 8) |
                            ((fileBytes[pos + 14] & 0xFF) << 16) |
                            ((fileBytes[pos + 15] & 0xFF) << 24);
                    bitsPerSample = (short) ((fileBytes[pos + 22] & 0xFF) | ((fileBytes[pos + 23] & 0xFF) << 8));
                } else if ("data".equals(chunkId)) {
                    dataPos = pos + 8;
                    dataLen = Math.min(chunkLen, fileBytes.length - dataPos);
                    break;
                }
                pos += 8 + chunkLen;
                if ((chunkLen & 1) != 0) pos++;
            }

            if (dataPos == -1 || dataLen <= 0) return;
            if (sampleRate <= 0 || sampleRate > 96000) sampleRate = 48000;
            if (channels <= 0 || channels > 2) channels = 1;

            int channelConfig = (channels == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int audioFormat = (bitsPerSample == 8) ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;

            // Apply 10ms micro fade-in and 20ms fade-out directly to buffer in memory
            int bytesPerFrame = channels * (bitsPerSample / 8);
            if (bitsPerSample == 16 && bytesPerFrame >= 2) {
                int totalFrames = dataLen / bytesPerFrame;
                int fadeInFrames = Math.min(sampleRate / 100, totalFrames / 4);
                for (int i = 0; i < fadeInFrames; i++) {
                    float gain = (float) i / (float) fadeInFrames;
                    for (int c = 0; c < channels; c++) {
                        int idx = dataPos + (i * bytesPerFrame) + (c * 2);
                        short val = (short) ((fileBytes[idx] & 0xFF) | (fileBytes[idx + 1] << 8));
                        short ramped = (short) Math.round(val * gain);
                        fileBytes[idx] = (byte) (ramped & 0xFF);
                        fileBytes[idx + 1] = (byte) ((ramped >> 8) & 0xFF);
                    }
                }
                int fadeOutFrames = Math.min((sampleRate * 2) / 100, totalFrames / 4);
                for (int i = 0; i < fadeOutFrames; i++) {
                    int frameIdx = totalFrames - 1 - i;
                    float gain = (float) i / (float) fadeOutFrames;
                    for (int c = 0; c < channels; c++) {
                        int idx = dataPos + (frameIdx * bytesPerFrame) + (c * 2);
                        short val = (short) ((fileBytes[idx] & 0xFF) | (fileBytes[idx + 1] << 8));
                        short ramped = (short) Math.round(val * gain);
                        fileBytes[idx] = (byte) (ramped & 0xFF);
                        fileBytes[idx + 1] = (byte) ((ramped >> 8) & 0xFF);
                    }
                }
            }

            int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufSize = Math.max(minBuf, 32768);

            AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

            track.setVolume(1.0f);
            track.play();
            track.write(fileBytes, dataPos, dataLen);
            Thread.sleep(Math.max(100, (dataLen * 1000L) / (sampleRate * bytesPerFrame) + 50));
            track.stop();
            track.release();
            System.out.println("[AUDIO] Played " + dataLen + " clean PCM bytes via AudioTrack successfully!");
        } catch (Throwable t) {
            System.err.println("[AUDIO] AudioTrack playback error: " + t.getMessage());
        }
    }

    public static void playDoaNaikKenderaan() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File doaFile = new File("/data/local/window/locksounds/unlock_omar_hana.wav");
                    if (doaFile.exists() && doaFile.length() > 1000) {
                        System.out.println("[DOA] Playing Doa Naik Kenderaan in cabin...");
                        playInCabinAudio(doaFile);
                    } else {
                        System.err.println("[DOA] Doa file not found: " + doaFile.getAbsolutePath());
                    }
                } catch (Throwable t) {
                    System.err.println("[DOA] Error playing Doa: " + t.getMessage());
                }
            }
        }).start();
    }

    private static volatile long lastUnlockSoundPlayedTime = 0;

    public static void triggerUnlockSound(final String reason, final String path, final String outputMode) {
        long now = System.currentTimeMillis();
        if (now - lastUnlockSoundPlayedTime < 15000) {
            System.out.println("[UNLOCK-SOUND] Debounced (already played " + (now - lastUnlockSoundPlayedTime) + "ms ago, skip reason=" + reason + ")");
            return;
        }
        lastUnlockSoundPlayedTime = now;
        System.out.println("[UNLOCK-SOUND] Triggering (" + reason + "): " + path + " -> " + outputMode);
        new Thread(new Runnable() {
            @Override
            public void run() {
                // If triggered by remote button unlock from sleep, give the vehicle audio amplifier 1.2s to power on and unmute!
                if ("button_unlock".equals(reason)) {
                    try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                }
                playUnlockSound(path, outputMode);
            }
        }).start();
    }

    public static void playUnlockSound(final String path, final String outputMode) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File f = new File(path);
                    if (!f.exists() || f.length() < 500) {
                        System.out.println("[UNLOCK-SOUND] File not found or too small: " + path);
                        return;
                    }
                    f.setReadable(true, false);

                    boolean playExt = !"cabin".equalsIgnoreCase(outputMode);
                    boolean playCab = !"external".equalsIgnoreCase(outputMode);

                    if (playExt) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                File tinyplay = new File("/data/local/window/tinyplay");
                                if (tinyplay.exists() && tinyplay.canExecute()) {
                                    try {
                                        System.out.println("[UNLOCK-SOUND] Playing via external bumper speaker (/dev/snd/pcmC0D29p)...");
                                        String cmd = "stop avas_service; sleep 0.15; /data/local/window/tinyplay " + path + " -D 0 -d 29; start avas_service";
                                        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                                        int exit = p.waitFor();
                                        System.out.println("[UNLOCK-SOUND] External speaker finished (exit=" + exit + ")");
                                    } catch (Throwable t) {
                                        System.err.println("[UNLOCK-SOUND] External playback error: " + t.getMessage());
                                    } finally {
                                        try { Runtime.getRuntime().exec("start avas_service"); } catch (Throwable ignored) {}
                                    }
                                } else {
                                    System.out.println("[UNLOCK-SOUND] tinyplay not available, falling back to cabin");
                                    playInCabinAudio(f);
                                }
                            }
                        }).start();
                    }

                    if (playCab) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                System.out.println("[UNLOCK-SOUND] Playing in-cabin AudioTrack...");
                                playInCabinAudio(f);
                            }
                        }).start();
                    }
                } catch (Throwable t) {
                    System.err.println("[UNLOCK-SOUND] Error: " + t.getMessage());
                }
            }
        }).start();
    }

    public static void playMegaphoneAudio(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return;
            f.setReadable(true, false);

            File tinyplay = new File("/data/local/window/tinyplay");
            if (tinyplay.exists() && tinyplay.canExecute()) {
                System.out.println("[MEGAFON] Playing audio via external AVAS bumper speaker (/dev/snd/pcmC0D29p)...");
                String cmd = "stop avas_service; sleep 0.15; /data/local/window/tinyplay " + path + " -D 0 -d 29; start avas_service";
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    int exitCode = p.waitFor();
                    System.out.println("[MEGAFON] External AVAS speaker playback finished (exit=" + exitCode + ")");
                } finally {
                    try {
                        Runtime.getRuntime().exec("start avas_service");
                    } catch (Throwable ignored) {}
                }
                return;
            }

            System.out.println("[MEGAFON] tinyplay not found, falling back to in-cabin AudioTrack...");
            playInCabinAudio(f);
        } catch (Throwable t) {
            System.err.println("[MEGAFON] Audio playback error: " + t.getMessage());
        }
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx == -1) {
            search = "\"" + key + "\": \"";
            idx = json.indexOf(search);
            if (idx == -1) return null;
        }
        int start = idx + search.length();
        int end = json.indexOf("\"", start);
        return end != -1 ? json.substring(start, end) : null;
    }

    private static int extractJsonInt(String json, String key, int def) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return def;
        int start = idx + search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (Exception e) {
            return def;
        }
    }

    private static int extractQueryInt(String path, String key, int def) {
        try {
            int idx = path.indexOf(key + "=");
            if (idx != -1) {
                int start = idx + key.length() + 1;
                int end = path.indexOf('&', start);
                if (end == -1) end = path.length();
                return Integer.parseInt(path.substring(start, end));
            }
        } catch (Throwable ignored) {}
        return def;
    }

    private static float extractJsonFloat(String json, String key, float def) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return def;
        int start = idx + search.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) end++;
        try {
            return Float.parseFloat(json.substring(start, end));
        } catch (Exception e) {
            return def;
        }
    }

    public static String getLocalIpAddress() {
        try {
            try {
                NetworkInterface wlan = NetworkInterface.getByName("wlan0");
                if (wlan != null && wlan.isUp()) {
                    java.util.Enumeration<InetAddress> addrs = wlan.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            } catch (Throwable ignored) {}

            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                String name = iface.getName();
                if (name.startsWith("tbox") || name.startsWith("apn")) continue;
                java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (!host.startsWith("127.")) {
                            return host;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static int[] extractJsonIntArray(String json, String key, int[] def) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return def;
        int start = json.indexOf("[", idx);
        int end = json.indexOf("]", start);
        if (start == -1 || end == -1) return def;
        String inside = json.substring(start + 1, end).trim();
        if (inside.isEmpty()) return def;
        String[] parts = inside.split(",");
        int[] res = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                res[i] = Integer.parseInt(parts[i].trim());
            } catch (Exception e) {
                res[i] = 0;
            }
        }
        return res;
    }

    private static String readFileToString(File f) {
        if (!f.exists()) return "{}";
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            fis.read(b);
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static void writeStringToFile(File f, String content) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}
    }

    private static final int[] CRC_TABLE_AVAS = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int curr = (i << 8) & 0xFFFF;
            for (int j = 0; j < 8; j++) {
                if ((curr & 0x8000) != 0) {
                    curr = ((curr << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    curr = (curr << 1) & 0xFFFF;
                }
            }
            CRC_TABLE_AVAS[i] = curr;
        }
    }

    private static int computeCrc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            int ub = b & 0xFF;
            if (ub == 0x0D) continue;
            crc = ((crc << 8) & 0xFF00) ^ CRC_TABLE_AVAS[(ub ^ (crc >> 8)) & 0xFF];
        }
        return crc & 0xFFFF;
    }

    private static byte[] encodeTuning(int slot, String trackPath, int[] volumes, int[] pitches, boolean muteChime) {
        StringBuilder volSb = new StringBuilder();
        for (int i = 0; i < volumes.length; i++) {
            if (i > 0) volSb.append(",");
            volSb.append(volumes[i]);
        }
        StringBuilder pitchSb = new StringBuilder();
        for (int i = 0; i < pitches.length; i++) {
            if (i > 0) pitchSb.append(",");
            pitchSb.append(pitches[i]);
        }
        String chimeVol = muteChime ? "0,0,0,0,0,0,0" : "0,2,5,6,7,2,0";
        String body = "Sound_Mode:" + slot + "\r\n"
                + "Pedal:0\r\n"
                + "Break:0\r\n"
                + "Volume_type:31,31,31\r\n"
                + "Track:/vendor/etc/avas/sound_type_" + slot + "/bDM_01_01.wav\r\n"
                + "Mixer_Volume:10,10,10\r\n"
                + "Pitch:100,108,116,124,132,140,148\r\n"
                + "Volume:" + chimeVol + "\r\n"
                + "Track:" + trackPath + "\r\n"
                + "Mixer_Volume:10,10,10\r\n"
                + "Pitch:" + pitchSb.toString() + "\r\n"
                + "Volume:" + volSb.toString() + "\r\n"
                + "[stop]\r\n";
        byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int crc = computeCrc16(bodyBytes);
        String header = "Encryption_Code:" + crc + "\r\n";
        byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        byte[] full = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, full, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, full, headerBytes.length, bodyBytes.length);

        byte[] out = new byte[full.length];
        for (int i = 0; i < full.length; i++) {
            int b = full[i] & 0xFF;
            if (b == 0x0D || b == 0x0A || b == 0x70 || b == 0x7D) {
                out[i] = (byte) b;
            } else {
                out[i] = (byte) (b ^ 0x70);
            }
        }
        return out;
    }

    private static void scanAudioFiles(File dir, int depth, List<File> results) {
        if (dir == null || !dir.exists() || !dir.canRead() || depth > 4) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !f.getName().equals("LOST.DIR")) {
                    scanAudioFiles(f, depth + 1, results);
                }
            } else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".ogg") || n.endsWith(".flac")) {
                    results.add(f);
                }
            }
        }
    }

    private static synchronized void updateAvasSlotConfig(int slot, int[] vols, int[] pitches, String customName) {
        try {
            File f = new File("/data/local/avas/config.json");
            String json = readFileToString(f);
            if (json.isEmpty() || json.equals("{}")) {
                json = "{\"active_slot\":3,\"slots\":{\"1\":{\"name\":\"Slot 1 (Galaxy)\",\"custom_name\":\"Paddle Pop\",\"volume\":[0,2,5,6,7,2,0],\"pitch\":[100,100,100,100,100,100,100],\"mute_chime\":true},\"2\":{\"name\":\"Slot 2 (Space)\",\"custom_name\":\"Space Pulse\",\"volume\":[0,6,8,11,14,6,0],\"pitch\":[100,100,100,100,100,100,100],\"mute_chime\":false},\"3\":{\"name\":\"Slot 3 (Pixel)\",\"custom_name\":\"Super Mario Bros Theme\",\"volume\":[0,3,5,6,7,3,0],\"pitch\":[100,102,105,108,112,116,120],\"mute_chime\":true}}}";
            }
            if (vols != null && vols.length >= 7) {
                String key = "\"" + slot + "\":";
                int kIdx = json.indexOf(key);
                if (kIdx != -1) {
                    int vKey = json.indexOf("\"volume\"", kIdx);
                    if (vKey != -1) {
                        int bStart = json.indexOf("[", vKey);
                        int bEnd = json.indexOf("]", bStart);
                        if (bStart != -1 && bEnd != -1) {
                            StringBuilder sbV = new StringBuilder();
                            for (int i = 0; i < 7; i++) {
                                if (i > 0) sbV.append(",");
                                sbV.append(vols[i]);
                            }
                            json = json.substring(0, bStart + 1) + sbV.toString() + json.substring(bEnd);
                        }
                    }
                }
            }
            if (pitches != null && pitches.length >= 7) {
                String key = "\"" + slot + "\":";
                int kIdx = json.indexOf(key);
                if (kIdx != -1) {
                    int pKey = json.indexOf("\"pitch\"", kIdx);
                    if (pKey != -1) {
                        int bStart = json.indexOf("[", pKey);
                        int bEnd = json.indexOf("]", bStart);
                        if (bStart != -1 && bEnd != -1) {
                            StringBuilder sbP = new StringBuilder();
                            for (int i = 0; i < 7; i++) {
                                if (i > 0) sbP.append(",");
                                sbP.append(pitches[i]);
                            }
                            json = json.substring(0, bStart + 1) + sbP.toString() + json.substring(bEnd);
                        }
                    }
                }
            }
            if (customName != null && !customName.isEmpty()) {
                String key = "\"" + slot + "\":";
                int kIdx = json.indexOf(key);
                if (kIdx != -1) {
                    int cIdx = json.indexOf("\"custom_name\"", kIdx);
                    if (cIdx != -1) {
                        int colon = json.indexOf(":", cIdx);
                        int q1 = json.indexOf("\"", colon);
                        int q2 = json.indexOf("\"", q1 + 1);
                        if (q1 != -1 && q2 != -1) {
                            json = json.substring(0, q1 + 1) + customName + json.substring(q2);
                        }
                    }
                }
            }
            writeStringToFile(f, json);
        } catch (Throwable ignored) {}
    }

    private static volatile long lastAvasBroadcastTime = 0;

    public static void ensureAvasPersistence(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastAvasBroadcastTime < 25000)) return;
        lastAvasBroadcastTime = now;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int slot = 3;
                    File cfg = new File("/data/local/avas/config.json");
                    if (cfg.exists()) {
                        String json = readFileToString(cfg);
                        int idx = json.indexOf("\"active_slot\":");
                        if (idx != -1) {
                            int end = json.indexOf(",", idx);
                            if (end == -1) end = json.indexOf("}", idx);
                            if (end != -1) {
                                try { slot = Integer.parseInt(json.substring(idx + 14, end).trim()); } catch (Exception ignored) {}
                            }
                        }
                    }
                    String cmd = "setprop persist.avas.mode " + slot + "; setprop persist.avas.switch 1; setprop persist.sys.avas.mode " + slot + "; setprop persist.sys.avas.switch 1";
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    p.waitFor();
                    System.out.println("[AVAS-PERSIST] Ensured AVAS active on Slot " + slot + " (silent setprop, no preview chime)");
                } catch (Throwable t) {
                    System.err.println("[AVAS-PERSIST] Error: " + t.getMessage());
                }
            }
        }).start();
    }

    private static synchronized void updateAvasActiveSlot(int slot) {
        try {
            File f = new File("/data/local/avas/config.json");
            String json = readFileToString(f);
            if (json.contains("\"active_slot\"")) {
                int aIdx = json.indexOf("\"active_slot\"");
                int colon = json.indexOf(":", aIdx);
                int aEnd = json.indexOf(",", colon);
                if (aEnd == -1) aEnd = json.indexOf("}", colon);
                if (colon != -1 && aEnd != -1) {
                    json = json.substring(0, colon + 1) + " " + slot + json.substring(aEnd);
                    writeStringToFile(f, json);
                }
            }
            ensureAvasPersistence(true);
        } catch (Throwable ignored) {}
    }

    private boolean connectCarService() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Object binder = smClass.getMethod("getService", String.class).invoke(null, "car_service");
            if (binder == null) return false;

            Class<?> iCarStub = Class.forName("android.car.ICar$Stub");
            Object icar = iCarStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            Object propBinder = icar.getClass().getMethod("getCarService", String.class).invoke(icar, "property");

            Class<?> iPropStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
            propService = iPropStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);

            for (Method m : propService.getClass().getMethods()) {
                if (m.getName().equals("getProperty") && m.getParameterTypes().length == 2) {
                    getPropMethod = m;
                }
                if (m.getName().equals("setProperty")) {
                    System.out.println("[METHOD] " + m);
                    if (m.getParameterTypes().length == 1) {
                        setPropMethod = m;
                    }
                }
            }

            Class<?> cpvClass = Class.forName("android.car.hardware.CarPropertyValue");
            for (Constructor<?> c : cpvClass.getConstructors()) {
                System.out.println("[CPV-CTOR] " + c);
                Class<?>[] pTypes = c.getParameterTypes();
                if (pTypes.length == 3 && pTypes[0] == int.class && pTypes[1] == int.class) {
                    cpvCtor = c;
                    break;
                }
            }

            return (propService != null && getPropMethod != null && setPropMethod != null && cpvCtor != null);
        } catch (Throwable t) {
            System.err.println("connectCarService error: " + t.getMessage());
            return false;
        }
    }

    private static final int[] VHAL_AREAS = {0, 0x8000000, 75, 117, 1, 2};

    public synchronized int readIntProp(int propId) {
        if (propService == null || getPropMethod == null) return -1;
        for (int a : VHAL_AREAS) {
            try {
                Object cpv = getPropMethod.invoke(propService, propId, a);
                if (cpv != null) {
                    Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                    if (val instanceof byte[]) {
                        byte[] b = (byte[]) val;
                        if (b.length > 0) return (b[0] & 0xFF);
                    } else if (val instanceof Integer) {
                        return ((Integer) val).intValue();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    public synchronized float readFloatProp(int propId, float def) {
        if (propService == null || getPropMethod == null) return def;
        for (int a : VHAL_AREAS) {
            try {
                Object cpv = getPropMethod.invoke(propService, propId, a);
                if (cpv != null) {
                    Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                    if (val instanceof Float) return ((Float) val).floatValue();
                    if (val instanceof Double) return ((Double) val).floatValue();
                    if (val instanceof Integer) return ((Integer) val).floatValue();
                }
            } catch (Throwable ignored) {}
        }
        return def;
    }

    public synchronized float readFloatProp(int propId, int area, float def) {
        if (propService == null || getPropMethod == null) return def;
        try {
            Object cpv = getPropMethod.invoke(propService, propId, area);
            if (cpv != null) {
                Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                if (val instanceof Float) return ((Float) val).floatValue();
                if (val instanceof Double) return ((Double) val).floatValue();
                if (val instanceof Integer) return ((Integer) val).floatValue();
            }
        } catch (Throwable ignored) {}
        return def;
    }

    public synchronized int readIntProp(int propId, int area, int def) {
        if (propService == null || getPropMethod == null) return def;
        try {
            Object cpv = getPropMethod.invoke(propService, propId, area);
            if (cpv != null) {
                Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                if (val instanceof Integer) return ((Integer) val).intValue();
                if (val instanceof byte[]) {
                    byte[] b = (byte[]) val;
                    if (b.length > 0) return (b[0] & 0xFF);
                }
            }
        } catch (Throwable ignored) {}
        return def;
    }

    public synchronized boolean readBoolProp(int propId, int area, boolean def) {
        if (propService == null || getPropMethod == null) return def;
        try {
            Object cpv = getPropMethod.invoke(propService, propId, area);
            if (cpv != null) {
                Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                if (val instanceof Boolean) return ((Boolean) val).booleanValue();
                if (val instanceof Integer) return ((Integer) val).intValue() != 0;
            }
        } catch (Throwable ignored) {}
        return def;
    }

    public synchronized boolean readBoolProp(int propId, boolean def) {
        if (propService == null || getPropMethod == null) return def;
        for (int a : VHAL_AREAS) {
            try {
                Object cpv = getPropMethod.invoke(propService, propId, a);
                if (cpv != null) {
                    Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                    if (val instanceof Boolean) return ((Boolean) val).booleanValue();
                    if (val instanceof Integer) return ((Integer) val).intValue() != 0;
                }
            } catch (Throwable ignored) {}
        }
        return def;
    }

    /** Little-endian unsigned int, same decode as the cluster TripInfo reader. */
    int readLeUnsigned(int propId) {
        byte[] b = readBytesProp(propId, 0);
        if (b == null || b.length == 0) return -1;
        int n = 0;
        for (int i = b.length - 1; i >= 0; i--) n = (n << 8) | (b[i] & 0xFF);
        return n;
    }

    public synchronized byte[] readBytesProp(int propId, int area) {
        if (propService == null || getPropMethod == null) return null;
        try {
            Object cpv = getPropMethod.invoke(propService, propId, area);
            if (cpv != null) {
                Object val = cpv.getClass().getMethod("getValue").invoke(cpv);
                if (val instanceof byte[]) return (byte[]) val;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void sendAbrpTelemetry(float soc, float packVolt, float packCurr, float speed, boolean isCharging) {
        if (abrpToken == null || abrpToken.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAbrpSyncTime < 5000) return;
        lastAbrpSyncTime = now;

        new Thread(() -> {
            try {
                float powerKw = (packVolt * packCurr) / 1000.0f;
                long utc = System.currentTimeMillis() / 1000L;
                String tlmJson = "{"
                        + "\"utc\":" + utc + ","
                        + "\"soc\":" + String.format(java.util.Locale.US, "%.1f", soc) + ","
                        + "\"power\":" + String.format(java.util.Locale.US, "%.2f", powerKw) + ","
                        + "\"speed\":" + String.format(java.util.Locale.US, "%.1f", speed) + ","
                        + "\"voltage\":" + String.format(java.util.Locale.US, "%.1f", packVolt) + ","
                        + "\"current\":" + String.format(java.util.Locale.US, "%.1f", packCurr) + ","
                        + "\"batt_temp\":31.0,"
                        + "\"is_charging\":" + isCharging
                        + "}";
                String encodedTlm = URLEncoder.encode(tlmJson, "UTF-8");
                String urlStr = "https://api.iternio.com/1/tlm/send?api_key=" + ABRP_API_KEY + "&token=" + abrpToken + "&tlm=" + encodedTlm;

                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setFixedLengthStreamingMode(0);
                conn.setDoOutput(true);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Throwable ignored) {}
        }).start();
    }

    public synchronized String getEnergyJson() {
        // OEM Battery SOC from BMS (0x287001AF = 678429103)
        float soc = 87.0f;
        byte[] bSoc = readBytesProp(678429103, 0);
        if (bSoc != null && bSoc.length >= 2) {
            int rawSoc = ((bSoc[1] & 0xFF) << 8) | (bSoc[0] & 0xFF);
            if (rawSoc > 0 && rawSoc <= 1000) soc = rawSoc / 10.0f;
        }

        // OEM HV Bus Voltage (0x28700315 = 678429461)
        float packVolt = 398.0f;
        byte[] bVolt = readBytesProp(678429461, 0);
        if (bVolt != null && bVolt.length >= 2) {
            int rawVolt = ((bVolt[1] & 0xFF) << 8) | (bVolt[0] & 0xFF);
            if (rawVolt > 1000) packVolt = rawVolt / 10.0f;
        }

        // OEM Battery Current (0x287001B0 = 678429104, 5000 offset, 0.1A precision)
        // Positive = Discharge (Driving/Traction), Negative = Charge/Regen
        float packCurr = 0.0f;
        boolean hasCurr = false;
        byte[] bCurr = readBytesProp(678429104, 0);
        if (bCurr != null && bCurr.length >= 2) {
            int rawCurr = ((bCurr[1] & 0xFF) << 8) | (bCurr[0] & 0xFF);
            if (rawCurr > 0 && rawCurr < 10000) {
                packCurr = (rawCurr - 5000) / 10.0f;
                hasCurr = true;
            }
        }

        // Vehicle Speed & Gear
        // e.MAS 5 ECARX CAN Vehicle Speed: 0x287000A8 = 678428840 (speed in 0.01 km/h)
        float speed = 0.0f;
        int mcuSpeed = readIntProp(678428840);
        if (mcuSpeed > 0) {
            speed = mcuSpeed * 0.01f;
        } else {
            speed = readFloatProp(PROP_VEHICLE_SPEED, 0.0f);
        }

        int mcuGear = readIntProp(PROP_GEAR_MCU); // 678428908 (0x287000EC)
        int gearRaw = readIntProp(PROP_VEHICLE_GEAR);
        if (gearRaw <= 0) gearRaw = readIntProp(289408000);

        String gear = "P";
        // Geely ECARX MCU CAN signal mapping (verified from TcuStatusImpl / Dashboard):
        // 2 = D (101), 3 = N (0), 4 = R (-1), 5 = P (102)
        if (mcuGear == 2) {
            gear = "D";
        } else if (mcuGear == 4) {
            gear = "R";
        } else if (mcuGear == 3) {
            gear = "N";
        } else if (mcuGear == 5) {
            gear = "P";
        } else if (gearRaw == 8 || gearRaw == 0x08) {
            gear = "D";
        } else if (gearRaw == 2 || gearRaw == 0x02) {
            gear = "R";
        } else if (gearRaw == 1 || gearRaw == 0x01) {
            gear = "N";
        } else if (gearRaw == 4 || gearRaw == 0x04) {
            gear = "P";
        }

        // OEM Thermal / HVAC Power (0x287002E5 = 678429413)
        int hvacW = 0;
        byte[] bTherm = readBytesProp(678429413, 0);
        if (bTherm != null && bTherm.length >= 1) {
            int rawTherm = (bTherm[0] & 0xFF) | ((bTherm.length > 1 ? (bTherm[1] & 0xFF) : 0) << 8);
            if (rawTherm > 0 && rawTherm < 100) {
                hvacW = rawTherm * 100;
            } else if (rawTherm >= 100) {
                hvacW = rawTherm;
            }
        }

        int acByte = firstByte(PROP_HVAC_AC_COMP, 0);
        boolean acOn = acByte < 0 ? readBoolProp(PROP_HVAC_AC_ON, 117, true) : (acByte != 0);
        int mcuFan = firstByte(PROP_HVAC_FAN_SPEED, 0);
        int blower = (mcuFan >= 0 && mcuFan <= 8) ? mcuFan : readIntProp(PROP_HVAC_BLOWER, 117, 1);
        if (blower < 0) blower = 1;
        float setpoint = readMcuTemp(PROP_HVAC_TEMP_DRIVER, readFloatProp(PROP_HVAC_SETPOINT, 49, 22.0f));
        float setpointPass = readMcuTemp(PROP_HVAC_TEMP_PASS, readFloatProp(PROP_HVAC_SETPOINT, 68, 22.0f));
        int pwrByte = firstByte(PROP_HVAC_POWER_ON, 0);
        boolean hvacPowerOn = pwrByte < 0 ? (blower > 0) : (pwrByte != 0);

        int loopMode = 0; // 0=fresh, 1=recirc
        byte[] bCirc = readBytesProp(PROP_HVAC_CIRC_MODE, 0);
        if (bCirc != null && bCirc.length > 0) {
            loopMode = bCirc[0] & 0xFF;
        }

        // If OEM property is silent but A/C is ON, reflect real compressor and blower draw
        if (hvacW <= 50 && acOn) {
            int estCompressor = 950;
            if (setpoint < 21.0f) estCompressor = 1650;
            else if (setpoint > 24.0f) estCompressor = 650;
            hvacW = estCompressor + (blower * 40);
        }

        // OEM 12V DCDC Power (0x287002E6 = 678429414)
        int accW = 200;
        byte[] bDcdc = readBytesProp(678429414, 0);
        if (bDcdc != null && bDcdc.length >= 1) {
            int rawDcdc = (bDcdc[0] & 0xFF) * 100;
            if (rawDcdc > 0) accW = rawDcdc;
        }

        // True Battery Net Power (P = V * I)
        int totalBatteryW = (int) (packVolt * packCurr);

        // Master Total Power & Dynamic Motor Power
        int totalW = totalBatteryW;
        int motorW = 0;

        // 1. Check OEM VCU Inverter Drive Power (0x287002E4 = 678429412)
        boolean hasVcu = false;
        byte[] bMotor = readBytesProp(678429412, 0);
        if (bMotor != null && bMotor.length >= 2) {
            int rawMotor = ((bMotor[1] & 0xFF) << 8) | (bMotor[0] & 0xFF);
            if (rawMotor > 0 && rawMotor < 6000) {
                float mKw = (rawMotor / 10.0f) - 204.0f;
                if (Math.abs(mKw) > 0.15f) {
                    motorW = (int) (mKw * 1000.0f);
                    totalW = motorW + hvacW + accW;
                    hasVcu = true;
                }
            }
        }

        // 2. If VCU property is standby, compute directly from High Voltage BMS Net Draw
        if (!hasVcu && hasCurr) {
            int netTraction = totalBatteryW - hvacW - accW;
            // Filter standby baseline (< 250W is idle parasitic load)
            if (Math.abs(netTraction) > 250) {
                motorW = netTraction;
                totalW = totalBatteryW;
            } else {
                motorW = 0;
                totalW = hvacW + accW;
            }
        }

        // 3. Dynamic Gear Inference:
        // When driving or accelerating, motor draws power -> Gear is D
        // When decelerating or regenerating, motor produces power -> Gear is D
        if (motorW > 350) {
            gear = "D";
        } else if (motorW < -250) {
            gear = "D";
        } else if (speed > 1.5f && "P".equals(gear)) {
            gear = "D";
        }

        float totalKw = totalW / 1000.0f;
        float motorKw = motorW / 1000.0f;
        float hvacKw = hvacW / 1000.0f;

        // Drive Mode Determination
        String mode = "IDLE";
        if (motorW > 350 || (speed > 1.5f && totalW > 500)) {
            mode = "DRIVING";
        } else if (motorW < -250 || totalW < -250) {
            mode = "REGEN";
        } else if ("D".equals(gear) && speed > 1.5f) {
            mode = "COASTING";
        } else if (totalKw < -1.5f) {
            mode = "CHARGING";
        } else {
            mode = "IDLE";
        }

        // Real-time EV Telemetry to ABRP
        sendAbrpTelemetry(soc, packVolt, packCurr, speed, "CHARGING".equals(mode));

        // Range (0x287001AD = 678429101)
        int rangeKm = 284;
        byte[] bRange = readBytesProp(678429101, 0);
        if (bRange != null && bRange.length >= 2) {
            rangeKm = ((bRange[1] & 0xFF) << 8) | (bRange[0] & 0xFF);
        }

        int compW = Math.max(0, hvacW - (blower * 40));

        int trip1Raw = readLeUnsigned(678429315); // INFO_ID_IPKINFO_TRIPMETER1, 0.1 km
        int trip2Raw = readLeUnsigned(678429313);
        int dur1 = readLeUnsigned(678429290);     // minutes
        int spd1 = readLeUnsigned(678429292);     // km/h
        int avgE = readLeUnsigned(678429417);     // INFO_ID_TRIP1_AVG_ENERGY, 0.1 kWh/100km
        boolean trip1Ok = trip1Raw >= 0 && trip1Raw < 100001;
        boolean trip2Ok = trip2Raw >= 0 && trip2Raw < 100001;
        boolean durOk = dur1 >= 0 && dur1 < 60001;
        boolean spdOk = spd1 >= 0 && spd1 < 255;
        boolean avgOk = avgE > 0 && avgE < 8191;
        float tripKm = trip1Ok ? trip1Raw / 10.0f : 0f;
        float tripUse = avgOk ? avgE / 10.0f : 0f;
        boolean energyOk = trip1Ok && avgOk;
        float tripKwh = energyOk ? tripKm * tripUse / 100.0f : 0f;

        return "{"
            + "\"total_power_kw\":" + String.format(java.util.Locale.US, "%.2f", totalKw) + ","
            + "\"total_power_w\":" + totalW + ","
            + "\"motor_power_kw\":" + String.format(java.util.Locale.US, "%.2f", motorKw) + ","
            + "\"motor_power_w\":" + motorW + ","
            + "\"motor_mode\":\"" + mode + "\","
            + "\"hvac_power_kw\":" + String.format(java.util.Locale.US, "%.2f", hvacKw) + ","
            + "\"hvac_power_w\":" + hvacW + ","
            + "\"hvac_details\":{"
                + "\"ac_on\":" + acOn + ","
                + "\"power_on\":" + hvacPowerOn + ","
                + "\"blower_speed\":" + blower + ","
                + "\"setpoint_c\":" + String.format(java.util.Locale.US, "%.1f", setpoint) + ","
                + "\"setpoint_pass_c\":" + String.format(java.util.Locale.US, "%.1f", setpointPass) + ","
                + "\"loop_mode\":" + loopMode + ","
                + "\"compressor_w\":" + compW + ","
                + "\"blower_w\":" + (blower * 40) + ","
                + "\"ptc_w\":0"
            + "},"
            + "\"accessories_12v_w\":" + accW + ","
            + "\"accessories_details\":{\"screen_and_ecu_w\":160,\"audio_w\":" + (accW > 160 ? accW - 160 : 40) + "},"
            + "\"battery\":{"
                + "\"voltage_v\":" + String.format(java.util.Locale.US, "%.1f", packVolt) + ","
                + "\"current_a\":" + String.format(java.util.Locale.US, "%.1f", packCurr) + ","
                + "\"soc_pct\":" + String.format(java.util.Locale.US, "%.1f", soc) + ","
                + "\"temp_c\":31.0,"
                + "\"range_km\":" + rangeKm
            + "},"
            + "\"ambient_temp_c\":32.0,"
            + "\"speed_kmh\":" + String.format(java.util.Locale.US, "%.1f", speed) + ","
            + "\"gear\":\"" + gear + "\","
            + "\"trip\":{"
                + "\"distance_km\":" + (trip1Ok ? String.format(java.util.Locale.US, "%.1f", tripKm) : "null") + ","
                + "\"duration_min\":" + (durOk ? Integer.toString(dur1) : "null") + ","
                + "\"avg_speed_kmh\":" + (spdOk ? Integer.toString(spd1) : "null") + ","
                + "\"consumption_kwh_100km\":" + (avgOk ? String.format(java.util.Locale.US, "%.1f", tripUse) : "null") + ","
                + "\"energy_kwh\":" + (energyOk ? String.format(java.util.Locale.US, "%.2f", tripKwh) : "null") + ","
                + "\"distance2_km\":" + (trip2Ok ? String.format(java.util.Locale.US, "%.1f", trip2Raw / 10.0f) : "null")
            + "}"
            + "}";
    }

    protected synchronized boolean dispatchProperty(int propId, int area, Object value) {
        if (!SafeVehicleCommands.validWrite(propId, area, value)) return false;
        if (propService == null || setPropMethod == null || cpvCtor == null) return false;
        try {
            setPropMethod.invoke(propService, cpvCtor.newInstance(propId, area, value));
            return true; // Dispatch accepted, not confirmation of physical state.
        } catch (Exception e) {
            System.err.println("VHAL dispatch failed for 0x" + Integer.toHexString(propId) + ": " + e);
            return false;
        }
    }

    public synchronized boolean writeWindow(int writePropId, int percent) {
        if (percent < 0 || percent > 100) return false;
        boolean allowed = false;
        for (int id : WIN_WRITE_PROPS) if (id == writePropId) allowed = true;
        return allowed && dispatchProperty(writePropId, 0, new byte[]{(byte) percent});
    }

    public synchronized boolean setTrunk(int action) {
        // Previously guessed four-byte payload and duplicate areas: disabled pending verification.
        return false;
    }

    public synchronized boolean writeHvacByte(int propId, int area, int val) {
        if (val < 0 || val > 255) return false;
        return dispatchProperty(propId, area, new byte[]{(byte) val});
    }

    public synchronized boolean writeFloatProp(int propId, int area, float val) {
        return dispatchProperty(propId, area, Float.valueOf(val));
    }

    public synchronized boolean writeIntProp(int propId, int area, int val) {
        return dispatchProperty(propId, area, Integer.valueOf(val));
    }

    public synchronized boolean writeBoolProp(int propId, int area, boolean val) {
        return dispatchProperty(propId, area, Boolean.valueOf(val));
    }

    int firstByte(int prop, int area) {
        byte[] value = readBytesProp(prop, area);
        return value == null || value.length == 0 ? -1 : value[0] & 255;
    }

    float readMcuTemp(int prop, float fallback) {
        int value = firstByte(prop, 0);
        return value < 0 || value > 30 ? fallback : 17.0f + value * 0.5f;
    }

    public synchronized boolean setHvacZone(int area, float temp) {
        // Existing temperature writes require nonzero zones; do not silently remap to area 0.
        return false;
    }

    public synchronized boolean setHvacTemperature(float temp) {
        boolean a = setHvacZone(49, temp);
        boolean b = setHvacZone(68, temp);
        return a && b;
    }

    public synchronized boolean setHvacFan(int level) {
        int speed = Math.max(0, Math.min(8, level));
        boolean ok = writeHvacByte(PROP_HVAC_FAN_SPEED, 0, speed);
        System.out.println("[HVAC] Fan " + speed + " mcu ok=" + ok);
        return ok;
    }

    public synchronized boolean setHvacPower(boolean on) {
        boolean powerOk = writeHvacByte(PROP_HVAC_POWER_ON, 0, on ? 1 : 0);
        if (!on) {
            writeHvacByte(PROP_HVAC_FAN_SPEED, 0, 0);
            writeHvacByte(PROP_HVAC_AC_COMP, 0, 0);
        } else if (firstByte(PROP_HVAC_FAN_SPEED, 0) <= 0) {
            writeHvacByte(PROP_HVAC_FAN_SPEED, 0, 3);
            writeHvacByte(PROP_HVAC_AC_COMP, 0, 1);
        }
        System.out.println("[HVAC] Power " + on + " mcu ok=" + powerOk);
        return powerOk;
    }

    public synchronized boolean setHvacAc(boolean on) {
        boolean ok = writeHvacByte(PROP_HVAC_AC_COMP, 0, on ? 1 : 0);
        System.out.println("[HVAC] AC " + on + " mcu ok=" + ok);
        return ok;
    }

    public synchronized boolean setHvacRecirc(int mode) {
        writeHvacByte(PROP_HVAC_CIRC_MODE, 0, mode == 1 ? 1 : 0);
        System.out.println("[HVAC] Set Circulation to mode " + mode);
        return true;
    }

    public synchronized boolean writeAllWindows(int percent) {
        if (percent < 0 || percent > 100) return false;
        boolean ok = true;
        // One write per window; no speculative group write followed by duplicate commands.
        for (int w : WIN_WRITE_PROPS) ok = writeWindow(w, percent) && ok;
        return ok;
    }

    public void startVentilation(int minutes) {
        ventilationDurationMin = Math.max(1, Math.min(180, minutes));
        ventilationStartTime = System.currentTimeMillis();
        ventilationMode = true;
        saveConfig();
        System.out.println("[VENTILATION] Started for " + ventilationDurationMin + " mins at " + crackPercent + "%");
        writeAllWindows(crackPercent);
    }

    public void stopVentilation() {
        ventilationMode = false;
        ventilationStartTime = 0;
        saveConfig();
        System.out.println("[VENTILATION] Stopped. Closing all windows.");
        writeAllWindows(0);
    }

    public synchronized boolean setDriveMode(int mode) { return commands.driveMode(mode); }
    public synchronized boolean setRegenLevel(int level) { return commands.regen(level); }
    public synchronized boolean setBrakePedal(int mode) { return commands.brakePedal(mode); }
    public synchronized boolean setComfortBrake(boolean on) { return commands.comfortBrake(on); }

    private void startHttpServer() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try (ServerSocket server = new ServerSocket(HTTP_PORT, 16, InetAddress.getByName("127.0.0.1"))) {
                    System.out.println("HTTP REST API Server running on port " + HTTP_PORT);
                    while (true) {
                        try {
                            Socket sock = server.accept();
                            sock.setSoTimeout(3000);
                            handleClient(sock);
                        } catch (Throwable e) {
                            System.err.println("HTTP accept error: " + e.getMessage());
                        }
                    }
                } catch (Throwable t1) {
                    System.err.println("HTTP Server failed to bind port " + HTTP_PORT + ": " + t1.getMessage());
                }
            }
        });
        t.setDaemon(true);
        t.start();

        startCloudSync();
    }

    private void startCloudSync() {
        Thread syncThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // Use Android default TLS certificate and hostname verification.

                System.out.println("Cloud Sync thread started -> https://emas.43-159-63-29.nip.io/api/car/sync");

                while (true) {
                    try {
                        Thread.sleep(2000);
                        URL url = new URL("https://emas.43-159-63-29.nip.io/api/car/sync");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(3000);
                        conn.setReadTimeout(3000);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/json");

                        int wFL = readIntProp(PROP_READ_FL);
                        int wFR = readIntProp(PROP_READ_FR);
                        int wRL = readIntProp(PROP_READ_RL);
                        int wRR = readIntProp(PROP_READ_RR);
                        int dFL = readIntProp(PROP_DOOR_FL);
                        int dFR = readIntProp(PROP_DOOR_FR);
                        int dRL = readIntProp(PROP_DOOR_RL);
                        int dRR = readIntProp(PROP_DOOR_RR);
                        int dTrunk = readIntProp(PROP_DOOR_TRUNK);

                        long remSec = 0;
                        if (ventilationMode && ventilationStartTime > 0) {
                            long elapsed = (System.currentTimeMillis() - ventilationStartTime) / 1000;
                            long total = (long) ventilationDurationMin * 60;
                            remSec = Math.max(0, total - elapsed);
                        }

                        String localIp = getLocalIpAddress();
                        String payload = "{\"windows\":{\"fl\":" + wFL + ",\"fr\":" + wFR + ",\"rl\":" + wRL + ",\"rr\":" + wRR + "},"
                                + "\"doors\":{\"fl\":" + dFL + ",\"fr\":" + dFR + ",\"rl\":" + dRL + ",\"rr\":" + dRR + ",\"trunk\":" + (dTrunk == 1 ? 1 : 0) + "},"
                                + "\"ventilation_mode\":" + ventilationMode + ","
                                + "\"ventilation_remaining_sec\":" + remSec + ","
                                + "\"local_ip\":\"" + localIp + "\","
                                + "\"energy\":" + getEnergyJson()
                                + ",\"drive_mode\":{\"mode\":" + readIntProp(PROP_DRIVE_MODE, 0, -1)
                                + ",\"regen\":" + readIntProp(PROP_REGEN_LEVEL, 0, -1)
                                + ",\"vbf\":" + readIntProp(PROP_VBF_STATUS, 0, -1)
                                + ",\"cst\":" + readIntProp(PROP_CST_STATUS, 0, -1)
                                + ",\"lock\":" + false + "}"
                                + (lastAppliedAvasDeployId.isEmpty() ? "" : ",\"avas_applied\":\"" + lastAppliedAvasDeployId + "\"")
                                + (lastAppliedLockDeployId.isEmpty() ? "" : ",\"lock_applied\":\"" + lastAppliedLockDeployId + "\"")
                                + (lastAppliedUnlockDeployId.isEmpty() ? "" : ",\"unlock_applied\":\"" + lastAppliedUnlockDeployId + "\"")
                                + "}";

                        byte[] outBytes = payload.getBytes("UTF-8");
                        conn.getOutputStream().write(outBytes);

                        if (conn.getResponseCode() == 200) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line);
                            String resp = sb.toString();

                            if (resp.contains("\"enabled\":false") && enabled) {
                                System.out.println("[SYNC] Cloud requested: DISABLED unpressurized");
                                enabled = false;
                                saveConfig();
                            } else if (resp.contains("\"enabled\":true") && !enabled) {
                                System.out.println("[SYNC] Cloud requested: ENABLED unpressurized");
                                enabled = true;
                                saveConfig();
                            }

                            int cpIdx = resp.indexOf("\"crack_percent\":");
                            if (cpIdx != -1) {
                                int end = resp.indexOf(',', cpIdx);
                                if (end == -1) end = resp.indexOf('}', cpIdx);
                                if (end != -1) {
                                    try {
                                        int p = Integer.parseInt(resp.substring(cpIdx + 16, end).trim());
                                        if (p != crackPercent && p >= 3 && p <= 25) {
                                            System.out.println("[SYNC] Cloud requested crack percent: " + p);
                                            crackPercent = p;
                                            saveConfig();
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }

                            if (resp.contains("\"close_requested\":true")) {
                                System.out.println("[SYNC] Cloud requested: CLOSE ALL WINDOWS");
                                stopVentilation();
                            } else if (resp.contains("\"ventilation_mode\":true") && !ventilationMode) {
                                System.out.println("[SYNC] Cloud requested: START VENTILATION");
                                startVentilation(30);
                            }

                            // Trunk command handling from cloud
                            String trunkCmd = extractJsonString(resp, "trunk_cmd");
                            if (trunkCmd != null && !trunkCmd.isEmpty() && !"null".equalsIgnoreCase(trunkCmd)) {
                                System.out.println("[SYNC] Cloud requested TRUNK command: " + trunkCmd);
                                if ("open".equalsIgnoreCase(trunkCmd)) {
                                    setTrunk(1);
                                } else if ("close".equalsIgnoreCase(trunkCmd)) {
                                    setTrunk(2);
                                } else if ("toggle".equalsIgnoreCase(trunkCmd)) {
                                    int curTrunk = readIntProp(PROP_DOOR_TRUNK);
                                    int nextAct = (curTrunk == 1) ? 2 : 1;
                                    System.out.println("[SYNC] Toggle trunk: current=" + curTrunk + " -> next action=" + nextAct);
                                    setTrunk(nextAct);
                                }
                            }

                            // Cloud drive/regen/brake commands disabled: no command IDs/expiry/replay contract.

                            // AVAS OTA deployment handling
                            if (resp.contains("\"avas_deploy\":{")) {
                                int sIdx = resp.indexOf("\"avas_deploy\":{");
                                int eIdx = resp.indexOf("}", sIdx);
                                if (sIdx != -1 && eIdx != -1) {
                                    String obj = resp.substring(sIdx, eIdx + 1);
                                    String deployId = extractJsonString(obj, "deploy_id");
                                    if (deployId != null && !deployId.equals(lastAppliedAvasDeployId)) {
                                        System.out.println("[SYNC] New AVAS deployment detected: " + deployId);
                                        int slot = extractJsonInt(obj, "slot", 3);
                                        String audioUrl = extractJsonString(obj, "audio_url");
                                        String tuningUrl = extractJsonString(obj, "tuning_url");
                                        boolean muteChime = obj.contains("\"mute_chime\":true");

                                        File targetAudio = new File("/data/local/avas/slot" + slot + ".wav");
                                        File targetTuning = new File("/data/local/avas/tuning" + slot + ".txt");

                                        if (audioUrl != null && !audioUrl.isEmpty()) {
                                            downloadFile(audioUrl, targetAudio);
                                        }
                                        if (tuningUrl != null && !tuningUrl.isEmpty()) {
                                            downloadFile(tuningUrl, targetTuning);
                                        }

                                        try {
                                            String shCmd = "sh /data/local/avas/apply_ota_avas.sh " + slot + " "
                                                    + targetAudio.getAbsolutePath() + " "
                                                    + targetTuning.getAbsolutePath() + " "
                                                    + (muteChime ? "1" : "0");
                                            Process p = Runtime.getRuntime().exec(shCmd);
                                            p.waitFor();
                                            System.out.println("[SYNC] AVAS deployment applied successfully: " + deployId);
                                            lastAppliedAvasDeployId = deployId;
                                        } catch (Throwable t) {
                                            System.err.println("Failed to execute AVAS OTA: " + t.getMessage());
                                        }
                                    }
                                }
                            }

                            // Lock Sound OTA deployment handling
                            if (resp.contains("\"lock_deploy\":{")) {
                                int sIdx = resp.indexOf("\"lock_deploy\":{");
                                int eIdx = resp.indexOf("}", sIdx);
                                if (sIdx != -1 && eIdx != -1) {
                                    String obj = resp.substring(sIdx, eIdx + 1);
                                    String deployId = extractJsonString(obj, "deploy_id");
                                    if (deployId != null && !deployId.equals(lastAppliedLockDeployId)) {
                                        System.out.println("[SYNC] New Lock Sound deployment detected: " + deployId);
                                        String audioUrl = extractJsonString(obj, "audio_url");
                                        if (audioUrl != null && !audioUrl.isEmpty()) {
                                            File cacheDir = new File("/data/local/window/locksounds");
                                            if (!cacheDir.exists()) cacheDir.mkdirs();
                                            File targetAudio = new File(cacheDir, "lock_active.wav");

                                            downloadFile(audioUrl, targetAudio);
                                            if (targetAudio.exists() && applyLockSound(targetAudio.getAbsolutePath())) {
                                                System.out.println("[SYNC] Lock sound deployment applied successfully: " + deployId);
                                                lastAppliedLockDeployId = deployId;
                                            } else {
                                                System.err.println("[SYNC] Downloaded lock sound failed or is too small");
                                            }
                                        }
                                    }
                                }
                            }

                            // Unlock Sound OTA deployment handling
                            if (resp.contains("\"unlock_deploy\":{")) {
                                int sIdx = resp.indexOf("\"unlock_deploy\":{");
                                int eIdx = resp.indexOf("}", sIdx);
                                if (sIdx != -1 && eIdx != -1) {
                                    String obj = resp.substring(sIdx, eIdx + 1);
                                    String deployId = extractJsonString(obj, "deploy_id");
                                    if (deployId != null && !deployId.equals(lastAppliedUnlockDeployId)) {
                                        System.out.println("[SYNC] New Unlock Sound deployment detected: " + deployId);
                                        String audioUrl = extractJsonString(obj, "audio_url");
                                        if (audioUrl != null && !audioUrl.isEmpty()) {
                                            File cacheDir = new File("/data/local/window/locksounds");
                                            if (!cacheDir.exists()) cacheDir.mkdirs();
                                            File targetAudio = new File(cacheDir, "unlock_active.wav");

                                            downloadFile(audioUrl, targetAudio);
                                            if (targetAudio.exists() && targetAudio.length() > 500) {
                                                System.out.println("[SYNC] Unlock sound deployment applied successfully: " + deployId);
                                                lastAppliedUnlockDeployId = deployId;
                                            } else {
                                                System.err.println("[SYNC] Downloaded unlock sound failed or is too small");
                                            }
                                        }
                                    }
                                }
                            }

                            // Megaphone live broadcast handling
                            if (resp.contains("\"megaphone\":{")) {
                                int sIdx = resp.indexOf("\"megaphone\":{");
                                int eIdx = resp.indexOf("}", sIdx);
                                if (sIdx != -1 && eIdx != -1) {
                                    String obj = resp.substring(sIdx, eIdx + 1);
                                    String msgId = extractJsonString(obj, "id");
                                    String audioUrl = extractJsonString(obj, "audio_url");
                                    if (msgId != null && !msgId.equals(lastPlayedMegaphoneId) && audioUrl != null && !audioUrl.isEmpty()) {
                                        System.out.println("[SYNC] New Megaphone broadcast received: " + msgId);
                                        File megaDir = new File("/data/local/window/megafon");
                                        if (!megaDir.exists()) megaDir.mkdirs();
                                        File targetAudio = new File(megaDir, "current.wav");
                                        downloadFile(audioUrl, targetAudio);
                                        if (targetAudio.exists() && targetAudio.length() > 500) {
                                            playMegaphoneAudio(targetAudio.getAbsolutePath());
                                            lastPlayedMegaphoneId = msgId;
                                            System.out.println("[SYNC] Megaphone broadcast played successfully: " + msgId);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private void handleClient(final Socket sock) {
        try {
        httpWorkers.execute(new Runnable() {
            @Override
            public void run() {
                try (Socket client = sock;
                     InputStream in = sock.getInputStream();
                     OutputStream out = sock.getOutputStream()) {
                LocalHttpRequest request = LocalHttpRequest.read(in);
                String method = request.method;
                String path = request.target;
                String reqBody = request.body;
                boolean readOnlyPath = path.equals("/") || path.equals("/status") || path.equals("/api/status")
                        || path.equals("/energy") || path.equals("/api/energy") || path.equals("/api/drive_mode");
                if (method.equals("GET") && !readOnlyPath) {
                    out.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                    return;
                }

                String responseBody = "{}";
                int statusCode = 200;

                String combined = (path + " " + reqBody).toLowerCase();

                if (path.startsWith("/api/status") || path.equals("/status") || path.equals("/")) {
                    int wFL = readIntProp(PROP_READ_FL);
                    int wFR = readIntProp(PROP_READ_FR);
                    int wRL = readIntProp(PROP_READ_RL);
                    int wRR = readIntProp(PROP_READ_RR);
                    int dFL = readIntProp(PROP_DOOR_FL);
                    int dFR = readIntProp(PROP_DOOR_FR);
                    int dRL = readIntProp(PROP_DOOR_RL);
                    int dRR = readIntProp(PROP_DOOR_RR);

                    long remSec = 0;
                    if (ventilationMode && ventilationStartTime > 0) {
                        long elapsed = (System.currentTimeMillis() - ventilationStartTime) / 1000;
                        long total = (long) ventilationDurationMin * 60;
                        remSec = Math.max(0, total - elapsed);
                    }

                    responseBody = "{\n" +
                            "  \"ok\": true,\n" +
                            "  \"enabled\": " + enabled + ",\n" +
                            "  \"crack_percent\": " + crackPercent + ",\n" +
                            "  \"ventilation_mode\": " + ventilationMode + ",\n" +
                            "  \"ventilation_remaining_sec\": " + remSec + ",\n" +
                            "  \"windows\": {\"fl\":" + wFL + ",\"fr\":" + wFR + ",\"rl\":" + wRL + ",\"rr\":" + wRR + "},\n" +
                            "  \"doors\": {\"fl\":" + dFL + ",\"fr\":" + dFR + ",\"rl\":" + dRL + ",\"rr\":" + dRR + "}\n" +
                            "}";

                } else if (path.startsWith("/api/launch")) {
                    String pkg = "com.iternio.abrpapp";
                    String act = "com.iternio.abrpapp.MainActivity";
                    if (path.contains("app=abrp") || combined.contains("abrp")) {
                        pkg = "com.iternio.abrpapp";
                        act = "com.iternio.abrpapp.MainActivity";
                    }
                    try {
                        Runtime.getRuntime().exec(new String[]{"am", "start", "-n", pkg + "/" + act});
                        responseBody = "{\"ok\":true,\"launched\":\"" + pkg + "\"}";
                    } catch (Throwable t) {
                        responseBody = "{\"ok\":false,\"error\":\"" + t.getMessage() + "\"}";
                    }

                } else if (path.startsWith("/api/energy") || path.equals("/energy")) {
                    responseBody = getEnergyJson();

                } else if (path.startsWith("/api/config") || (path.startsWith("/api/set") && (combined.contains("enabled") || combined.contains("crack")))) {
                    if (combined.contains("enabled\":false") || combined.contains("enabled=false")) {
                        enabled = false;
                        System.out.println("[API] Unpressurized relief DISABLED");
                    } else if (combined.contains("enabled\":true") || combined.contains("enabled=true")) {
                        enabled = true;
                        System.out.println("[API] Unpressurized relief ENABLED");
                    }

                    int cIdx = path.indexOf("crack=");
                    if (cIdx != -1) {
                        int end = path.indexOf('&', cIdx);
                        if (end == -1) end = path.length();
                        try {
                            crackPercent = Math.max(3, Math.min(25, Integer.parseInt(path.substring(cIdx + 6, end))));
                        } catch (Exception ignored) {}
                    } else {
                        int cJson = reqBody.indexOf("\"crack_percent\":");
                        if (cJson != -1) {
                            int end = reqBody.indexOf(',', cJson);
                            if (end == -1) end = reqBody.indexOf('}', cJson);
                            if (end != -1) {
                                try {
                                    crackPercent = Math.max(3, Math.min(25, Integer.parseInt(reqBody.substring(cJson + 16, end).trim())));
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    String newAbrpToken = extractJsonString(reqBody, "abrp_token");
                    if (newAbrpToken == null || newAbrpToken.isEmpty()) {
                        int aIdx = path.indexOf("abrp_token=");
                        if (aIdx != -1) {
                            int end = path.indexOf('&', aIdx);
                            if (end == -1) end = path.length();
                            newAbrpToken = path.substring(aIdx + 11, end);
                        }
                    }
                    if (newAbrpToken != null && !newAbrpToken.isEmpty()) {
                        abrpToken = newAbrpToken;
                    }
                    saveConfig();
                    responseBody = "{\"ok\":true,\"enabled\":" + enabled + ",\"crack_percent\":" + crackPercent + ",\"abrp_token\":\"" + (abrpToken != null ? abrpToken : "") + "\"}";

                } else if (path.startsWith("/api/hvac")) {
                    if (path.contains("tempL=")) {
                        int idx = path.indexOf("tempL=");
                        int end = path.indexOf('&', idx);
                        if (end == -1) end = path.length();
                        try {
                            setHvacZone(49, Float.parseFloat(path.substring(idx + 6, end)));
                        } catch (Exception ignored) {}
                    }
                    if (path.contains("tempR=")) {
                        int idx = path.indexOf("tempR=");
                        int end = path.indexOf('&', idx);
                        if (end == -1) end = path.length();
                        try {
                            setHvacZone(68, Float.parseFloat(path.substring(idx + 6, end)));
                        } catch (Exception ignored) {}
                    }
                    if (path.contains("temp=") && !path.contains("tempL=") && !path.contains("tempR=")) {
                        int idx = path.indexOf("temp=");
                        int end = path.indexOf('&', idx);
                        if (end == -1) end = path.length();
                        try {
                            float t = Float.parseFloat(path.substring(idx + 5, end));
                            setHvacTemperature(t);
                        } catch (Exception ignored) {}
                    }
                    if (path.contains("fan=")) {
                        int idx = path.indexOf("fan=");
                        int end = path.indexOf('&', idx);
                        if (end == -1) end = path.length();
                        try {
                            int f = Integer.parseInt(path.substring(idx + 4, end));
                            setHvacFan(f);
                        } catch (Exception ignored) {}
                    }
                    if (path.contains("power=")) {
                        int idx = path.indexOf("power=");
                        int end = path.indexOf('&', idx);
                        if (end == -1) end = path.length();
                        try {
                            String pVal = path.substring(idx + 6, end);
                            boolean pOn = "1".equals(pVal) || "true".equalsIgnoreCase(pVal);
                            setHvacPower(pOn);
                        } catch (Exception ignored) {}
                    }
                    if (path.contains("ac=")) {
                        int idx = path.indexOf("ac=");
                        int end = path.indexOf('&', idx);
                        if (end == -1) end = path.length();
                        try {
                            String aVal = path.substring(idx + 3, end);
                            boolean aOn = "1".equals(aVal) || "true".equalsIgnoreCase(aVal);
                            setHvacAc(aOn);
                        } catch (Exception ignored) {}
                    }
                    if (path.contains("recirc=") || path.contains("air=")) {
                        int idx = path.indexOf("recirc=");
                        if (idx == -1) idx = path.indexOf("air=");
                        int end = path.indexOf('&', idx);
                        if (end == -1) end = path.length();
                        try {
                            int eq = path.indexOf('=', idx);
                            int r = Integer.parseInt(path.substring(eq + 1, end));
                            setHvacRecirc(r);
                        } catch (Exception ignored) {}
                    }

                    if (reqBody.contains("\"temp\":")) {
                        float t = extractJsonFloat(reqBody, "temp", -1.0f);
                        if (t > 0) setHvacTemperature(t);
                    }
                    if (reqBody.contains("\"fan\":")) {
                        int f = extractJsonInt(reqBody, "fan", -1);
                        if (f > 0) setHvacFan(f);
                    }
                    if (reqBody.contains("\"power\":")) {
                        setHvacPower(reqBody.contains("\"power\":true") || reqBody.contains("\"power\":1"));
                    }
                    if (reqBody.contains("\"ac\":")) {
                        setHvacAc(reqBody.contains("\"ac\":true") || reqBody.contains("\"ac\":1"));
                    }
                    if (reqBody.contains("\"recirc\":")) {
                        int r = extractJsonInt(reqBody, "recirc", 0);
                        setHvacRecirc(r);
                    }

                    if (path.contains("temp") || path.contains("fan=") || path.contains("power=") || path.contains("ac=")) {
                        try { Thread.sleep(450); } catch (InterruptedException ignored) {}
                    }
                    float curTemp = readMcuTemp(PROP_HVAC_TEMP_DRIVER, 22.0f);
                    float curTempPass = readMcuTemp(PROP_HVAC_TEMP_PASS, 22.0f);
                    int curFan = firstByte(PROP_HVAC_FAN_SPEED, 0);
                    if (curFan < 0) curFan = 1;
                    int acNow = firstByte(PROP_HVAC_AC_COMP, 0);
                    boolean curAc = acNow != 0;
                    int pwrNow = firstByte(PROP_HVAC_POWER_ON, 0);
                    boolean curPower = pwrNow < 0 ? (curFan > 0) : (pwrNow != 0);

                    responseBody = "{\"ok\":true,\"temp\":" + String.format(java.util.Locale.US, "%.1f", curTemp) + ",\"temp_pass\":" + String.format(java.util.Locale.US, "%.1f", curTempPass) + ",\"fan\":" + curFan + ",\"power\":" + curPower + ",\"ac\":" + curAc + "}";

                } else if (path.startsWith("/api/ventilate")) {
                    int mins = ventilationDurationMin;
                    int mIdx = path.indexOf("minutes=");
                    if (mIdx != -1) {
                        int end = path.indexOf('&', mIdx);
                        if (end == -1) end = path.length();
                        try { mins = Integer.parseInt(path.substring(mIdx + 8, end)); } catch (Exception ignored) {}
                    }
                    startVentilation(mins);
                    responseBody = "{\"ok\":true,\"ventilation_mode\":true,\"minutes\":" + mins + ",\"percent\":" + crackPercent + "}";

                } else if (path.startsWith("/api/close")) {
                    stopVentilation();
                    responseBody = "{\"ok\":true,\"ventilation_mode\":false,\"closed\":true}";

                } else if (path.startsWith("/api/window")) {
                    int percent = crackPercent;
                    if (combined.contains("\"action\":\"close\"") || combined.contains("action=close")) {
                        stopVentilation();
                        writeAllWindows(0);
                        responseBody = "{\"ok\":true,\"action\":\"close\",\"percent\":0}";
                    } else {
                        int pIdx = path.indexOf("percent=");
                        if (pIdx != -1) {
                            int end = path.indexOf('&', pIdx);
                            if (end == -1) end = path.length();
                            try { percent = Integer.parseInt(path.substring(pIdx + 8, end)); } catch (Exception ignored) {}
                        } else {
                            int pJson = reqBody.indexOf("\"percent\":");
                            if (pJson != -1) {
                                int end = reqBody.indexOf(',', pJson);
                                if (end == -1) end = reqBody.indexOf('}', pJson);
                                if (end != -1) {
                                    try { percent = Integer.parseInt(reqBody.substring(pJson + 10, end).trim()); } catch (Exception ignored) {}
                                }
                            }
                        }
                        startVentilation(30);
                        writeAllWindows(percent);
                        responseBody = "{\"ok\":true,\"action\":\"crack\",\"percent\":" + percent + "}";
                    }

                } else if (path.startsWith("/api/trunk/open")) {
                    setTrunk(1);
                    responseBody = "{\"ok\":true,\"action\":\"open\"}";

                } else if (path.startsWith("/api/trunk/close")) {
                    setTrunk(2);
                    responseBody = "{\"ok\":true,\"action\":\"close\"}";

                } else if (path.startsWith("/api/trunk/toggle") || path.startsWith("/api/trunk")) {
                    int curTrunk = readIntProp(PROP_DOOR_TRUNK);
                    int nextAct = (curTrunk == 1) ? 2 : 1;
                    setTrunk(nextAct);
                    responseBody = "{\"ok\":true,\"action\":\"" + (nextAct == 1 ? "open" : "close") + "\"}";

                } else if (path.startsWith("/api/set")) {
                    String pos = "all";
                    int percent = 0;
                    if (path.contains("pos=fl")) pos = "fl";
                    else if (path.contains("pos=fr")) pos = "fr";
                    else if (path.contains("pos=rl")) pos = "rl";
                    else if (path.contains("pos=rr")) pos = "rr";

                    int pIdx = path.indexOf("percent=");
                    if (pIdx != -1) {
                        int end = path.indexOf('&', pIdx);
                        if (end == -1) end = path.length();
                        try { percent = Integer.parseInt(path.substring(pIdx + 8, end)); } catch (Exception ignored) {}
                    }

                    if (pos.equals("all")) writeAllWindows(percent);
                    else if (pos.equals("fl")) writeWindow(PROP_WRITE_FL, percent);
                    else if (pos.equals("fr")) writeWindow(PROP_WRITE_FR, percent);
                    else if (pos.equals("rl")) writeWindow(PROP_WRITE_RL, percent);
                    else if (pos.equals("rr")) writeWindow(PROP_WRITE_RR, percent);

                    responseBody = "{\"ok\":true,\"pos\":\"" + pos + "\",\"percent\":" + percent + "}";
                } else if (path.startsWith("/api/locksound")) {
                    File activeLock = new File("/data/local/window/locksounds/lock_active.wav");
                    String soundKey = extractJsonString(reqBody, "sound");
                    if (soundKey != null && soundKey.matches("[A-Za-z0-9_-]{1,64}")) {
                        File preset = new File("/data/local/window/locksounds/lock_" + soundKey + ".wav");
                        if (preset.exists() && applyLockSound(preset.getAbsolutePath())) {
                            copyFile(preset, activeLock);
                            responseBody = "{\"ok\":true,\"applied\":\"" + soundKey + "\"}";
                        } else {
                            responseBody = "{\"ok\":false,\"error\":\"Preset missing or deployment failed\"}";
                        }
                    } else {
                        responseBody = "{\"ok\":true,\"has_custom\":" + activeLock.exists() + ",\"size\":" + (activeLock.exists() ? activeLock.length() : 0) + "}";
                    }
                } else if (path.startsWith("/api/unlocksound")) {
                    File activeUnlock = new File("/data/local/window/locksounds/unlock_active.wav");
                    if (reqBody.contains("\"enabled\":")) {
                        unlockSoundEnabled = reqBody.contains("\"enabled\":true") || reqBody.contains("\"enabled\": 1");
                        saveConfig();
                    }
                    if (reqBody.contains("\"output\":")) {
                        String outOpt = extractJsonString(reqBody, "output");
                        if (outOpt != null && !outOpt.isEmpty()) {
                            unlockSoundOutput = outOpt;
                            saveConfig();
                        }
                    }
                    String soundKey = extractJsonString(reqBody, "sound");
                    if (soundKey != null && !soundKey.isEmpty()) {
                        File preset = new File("/data/local/window/locksounds/unlock_" + soundKey + ".wav");
                        if (preset.exists() && preset.length() > 500) {
                            copyFile(preset, activeUnlock);
                            unlockSoundPreset = soundKey;
                            saveConfig();
                            System.out.println("[API] Unlock sound changed to preset: " + soundKey);
                        }
                    }
                    if (reqBody.contains("\"test\":true") || path.contains("test=1") || path.contains("test=true")) {
                        System.out.println("[API] Testing unlock sound playback...");
                        playUnlockSound(activeUnlock.getAbsolutePath(), unlockSoundOutput);
                    }
                    responseBody = "{\"ok\":true,\"enabled\":" + unlockSoundEnabled + ",\"output\":\"" + unlockSoundOutput + "\",\"preset\":\"" + unlockSoundPreset + "\",\"has_custom\":" + activeUnlock.exists() + ",\"size\":" + (activeUnlock.exists() ? activeUnlock.length() : 0) + "}";
                } else if (path.startsWith("/api/avas/config")) {
                    File cfgFile = new File("/data/local/avas/config.json");
                    if (cfgFile.exists()) {
                        responseBody = readFileToString(cfgFile);
                    } else {
                        responseBody = "{\"active_slot\":3,\"slots\":{\"1\":{\"name\":\"Slot 1 (Galaxy)\",\"custom_name\":\"Paddle Pop\",\"volume\":[0,2,5,6,7,2,0],\"pitch\":[100,100,100,100,100,100,100],\"mute_chime\":true},\"2\":{\"name\":\"Slot 2 (Space)\",\"custom_name\":\"Space Pulse\",\"volume\":[0,6,8,11,14,6,0],\"pitch\":[100,100,100,100,100,100,100],\"mute_chime\":false},\"3\":{\"name\":\"Slot 3 (Pixel)\",\"custom_name\":\"Super Mario Bros Theme\",\"volume\":[0,3,5,6,7,3,0],\"pitch\":[100,102,105,108,112,116,120],\"mute_chime\":true}}}";
                    }
                } else if (path.startsWith("/api/avas/usb_files")) {
                    List<File> audioList = new ArrayList<>();
                    scanAudioFiles(new File("/storage/usbotg-otg1"), 0, audioList);
                    scanAudioFiles(new File("/mnt/media_rw/usbotg-otg1"), 0, audioList);
                    scanAudioFiles(new File("/sdcard/Music"), 0, audioList);
                    scanAudioFiles(new File("/storage"), 0, audioList);

                    StringBuilder sb = new StringBuilder("{\"ok\":true,\"files\":[");
                    HashSet<String> seen = new HashSet<>();
                    boolean first = true;
                    for (File af : audioList) {
                        if (seen.contains(af.getAbsolutePath())) continue;
                        seen.add(af.getAbsolutePath());
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{\"name\":\"").append(af.getName()).append("\",")
                          .append("\"path\":\"").append(af.getAbsolutePath().replace("\\", "/")).append("\",")
                          .append("\"size\":").append(af.length()).append("}");
                    }
                    sb.append("]}");
                    responseBody = sb.toString();
                } else if (path.startsWith("/api/avas/apply_usb")) {
                    int slot = extractJsonInt(reqBody, "slot", 3);
                    String filePath = extractJsonString(reqBody, "file_path");
                    String customName = extractJsonString(reqBody, "custom_name");
                    if (customName == null || customName.isEmpty()) {
                        if (filePath != null) {
                            File f = new File(filePath);
                            customName = f.getName().replace(".wav", "").replace(".mp3", "");
                        } else {
                            customName = "Custom " + slot;
                        }
                    }
                    if (filePath != null && new File(filePath).exists()) {
                        File dest = new File("/data/local/avas/slot" + slot + ".wav");
                        copyFile(new File(filePath), dest);
                        try {
                            Runtime.getRuntime().exec(new String[]{"sh", "-c", "chmod 644 " + dest.getAbsolutePath()}).waitFor();
                            updateAvasSlotConfig(slot, null, null, customName);
                            String shCmd = "sh /data/local/avas/apply_ota_avas.sh " + slot + " "
                                    + dest.getAbsolutePath() + " /data/local/avas/tuning" + slot + ".txt 1";
                            Runtime.getRuntime().exec(shCmd).waitFor();
                            responseBody = "{\"ok\":true,\"slot\":" + slot + ",\"custom_name\":\"" + customName + "\"}";
                        } catch (Throwable t) {
                            responseBody = "{\"ok\":false,\"error\":\"" + t.getMessage() + "\"}";
                        }
                    } else {
                        responseBody = "{\"ok\":false,\"error\":\"File not found\"}";
                    }
                } else if (path.startsWith("/api/avas/save")) {
                    int slot = extractJsonInt(reqBody, "slot", 3);
                    int[] vols = extractJsonIntArray(reqBody, "volume", new int[]{0, 3, 5, 6, 7, 3, 0});
                    int[] pitches = extractJsonIntArray(reqBody, "pitch", new int[]{100, 102, 105, 108, 112, 116, 120});
                    boolean muteChime = !reqBody.contains("\"mute_chime\":false");
                    String customName = extractJsonString(reqBody, "custom_name");

                    updateAvasSlotConfig(slot, vols, pitches, customName);

                    String trackName = (slot == 1) ? "yinheyinfu.wav" : (slot == 2 ? "space.wav" : "pixel.wav");
                    String trackPath = "/vendor/etc/avas/sound_type_" + slot + "/" + trackName;
                    byte[] encoded = encodeTuning(slot, trackPath, vols, pitches, muteChime);

                    File tuningFile = new File("/data/local/avas/tuning" + slot + ".txt");
                    try (FileOutputStream fos = new FileOutputStream(tuningFile)) {
                        fos.write(encoded);
                    } catch (Throwable ignored) {}

                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "chmod 644 " + tuningFile.getAbsolutePath()}).waitFor();
                        String shCmd = "sh /data/local/avas/apply_ota_avas.sh " + slot + " /data/local/avas/slot" + slot + ".wav " + tuningFile.getAbsolutePath() + " " + (muteChime ? "1" : "0");
                        Runtime.getRuntime().exec(shCmd).waitFor();
                        responseBody = "{\"ok\":true,\"slot\":" + slot + ",\"applied\":true}";
                    } catch (Throwable t) {
                        responseBody = "{\"ok\":false,\"error\":\"" + t.getMessage() + "\"}";
                    }
                } else if (path.startsWith("/api/avas")) {
                    int mode = 0;
                    int mIdx = path.indexOf("mode=");
                    if (mIdx != -1) {
                        int end = path.indexOf('&', mIdx);
                        if (end == -1) end = path.length();
                        try { mode = Integer.parseInt(path.substring(mIdx + 5, end)); } catch (Exception ignored) {}
                    } else if (reqBody.contains("\"mode\":")) {
                        mode = extractJsonInt(reqBody, "mode", 0);
                    }
                    try {
                        if (mode == 0) {
                            Runtime.getRuntime().exec(new String[]{"sh", "-c", "am broadcast -a ecarx.settings.receiver.AvasSetReceiver -p ecarx.settings -f 0x01000000 --ei AVAS_SET_VALUE 0"}).waitFor();
                        } else {
                            Runtime.getRuntime().exec(new String[]{"sh", "-c", "am broadcast -a ecarx.settings.receiver.AvasSetReceiver -p ecarx.settings -f 0x01000000 --ei AVAS_SET_VALUE " + mode}).waitFor();
                            String shCmd = "sh /data/local/avas/apply_ota_avas.sh " + mode + " /data/local/avas/slot" + mode + ".wav /data/local/avas/tuning" + mode + ".txt 1";
                            Runtime.getRuntime().exec(shCmd).waitFor();
                        }
                        updateAvasActiveSlot(mode);
                        responseBody = "{\"ok\":true,\"mode\":" + mode + "}";
                    } catch (Throwable t) {
                        responseBody = "{\"ok\":false,\"error\":\"" + t.getMessage() + "\"}";
                    }
                } else if (path.startsWith("/api/avas/preview")) {
                    int pIdx = path.indexOf("path=");
                    if (pIdx != -1) {
                        try {
                            String fPath = URLDecoder.decode(path.substring(pIdx + 5), "UTF-8");
                            File f = new File(fPath);
                            if (f.exists() && f.isFile()) {
                                String mime = fPath.toLowerCase().endsWith(".mp3") ? "audio/mpeg" : "audio/wav";
                                byte[] fileBytes = new byte[(int) f.length()];
                                try (FileInputStream fis = new FileInputStream(f)) {
                                    fis.read(fileBytes);
                                }
                                String h = "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: " + mime + "\r\n" +
                                        "Content-Length: " + fileBytes.length + "\r\n" +
                                        "Connection: close\r\n\r\n";
                                out.write(h.getBytes("UTF-8"));
                                out.write(fileBytes);
                                out.flush();
                                return;
                            }
                        } catch (Throwable ignored) {}
                    }
                    statusCode = 404;
                    responseBody = "{\"error\":\"preview file not found\"}";
                } else if (path.startsWith("/api/drive_mode")) {
                    boolean requested = path.contains("?") || !reqBody.trim().isEmpty();
                    boolean ok = true;
                    if (requested) {
                        // Exactly one explicit command per request. Legacy lock and multi-command payloads rejected.
                        org.json.JSONObject body = reqBody.trim().isEmpty()
                                ? new org.json.JSONObject() : new org.json.JSONObject(reqBody);
                        if (!"POST".equals(method) || path.contains("?") || body.length() != 1) {
                            ok = false;
                        } else if (body.has("regen")) {
                            Object value = body.get("regen");
                            ok = value instanceof Integer && setRegenLevel((Integer) value);
                        } else if (body.has("mode")) {
                            Object value = body.get("mode");
                            ok = value instanceof Integer && setDriveMode((Integer) value);
                        } else if (body.has("vbf")) {
                            Object value = body.get("vbf");
                            ok = value instanceof Integer && setBrakePedal((Integer) value);
                        } else if (body.has("cst")) {
                            Object value = body.get("cst");
                            ok = value instanceof Integer && (((Integer) value) == 0 || ((Integer) value) == 1)
                                    && setComfortBrake(((Integer) value) == 1);
                        } else ok = false;
                        if (!ok) statusCode = 409;
                    }
                    responseBody = "{\"ok\":" + ok + ",\"confirmed\":false,\"dispatch_requested\":" + requested
                            + ",\"candidate_controls_enabled\":false,\"lock\":{\"enabled\":false},\"current\":{"
                            + "\"drive_mode\":" + readIntProp(PROP_DRIVE_MODE, 0, -1)
                            + ",\"regen_level\":" + readIntProp(PROP_REGEN_LEVEL, 0, -1)
                            + ",\"vbf_mode\":" + readIntProp(PROP_VBF_STATUS, 0, -1)
                            + ",\"cst_mode\":" + readIntProp(PROP_CST_STATUS, 0, -1) + "}}";
                } else {
                    statusCode = 404;
                    responseBody = "{\"error\":\"not found\"}";
                }

                byte[] bodyBytes = responseBody.getBytes("UTF-8");
                String resp = "HTTP/1.1 " + statusCode + (statusCode == 200 ? " OK" : " Request Failed") + "\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: " + bodyBytes.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(resp.getBytes("UTF-8"));
                out.write(bodyBytes);
                out.flush();

            } catch (Exception e) { System.err.println("HTTP request rejected: " + e.getClass().getSimpleName()); }
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    public void run() {
        while (!connectCarService()) {
            System.out.println("Waiting for car_service...");
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }
        System.out.println("Connected to CarPropertyService. Monitoring doors & ventilation...");

        // Auto-launch Emas Energy app on car startup / wakeup
        new Thread(() -> {
            try {
                Thread.sleep(6000);
                Runtime.getRuntime().exec(new String[]{"am", "start", "-n", "com.noor.thenoorcar/.MainActivity"});
                System.out.println("Auto-launched Emas Energy (com.noor.thenoorcar) on car start!");
            } catch (Throwable t) {
                System.err.println("Auto-launch error: " + t.getMessage());
            }
        }).start();

        // Ensure AVAS remains persistent on startup
        new Thread(() -> {
            try {
                Thread.sleep(4000);
                ensureAvasPersistence(true);
            } catch (Throwable ignored) {}
        }).start();

        // Initial door states (-1 = unknown)
        int[] lastDoorState = new int[4];
        for (int i = 0; i < 4; i++) {
            lastDoorState[i] = readIntProp(DOOR_PROPS[i]);
            System.out.println("Initial " + NAMES[i] + " door state: " + (lastDoorState[i] == 0 ? "OPEN" : "CLOSED"));
        }

        while (true) {
            try {
                // Check if external config changed
                File cfg = new File(CONFIG_PATH);
                if (cfg.exists() && cfg.lastModified() > lastConfigMtime) {
                    loadConfig();
                }

                // Check ventilation timer
                if (ventilationMode && ventilationStartTime > 0) {
                    long elapsedMs = System.currentTimeMillis() - ventilationStartTime;
                    long limitMs = (long) ventilationDurationMin * 60 * 1000L;
                    if (elapsedMs >= limitMs) {
                        System.out.println("[VENTILATION] 30m Timer expired! Auto-closing windows...");
                        stopVentilation();
                    }
                }

                // Real Lock / Unlock transition detection
                boolean currentlyLocked = isCarLocked();
                float curSpeed = readFloatProp(PROP_VEHICLE_SPEED, 0.0f);

                if (currentlyLocked) {
                    if (!carWasLocked) {
                        carWasLocked = true;
                        unlockEventPending = false;
                        hasPlayedDoaThisSession = false;
                        System.out.println("[LOCK-MONITOR] Car transitioned to LOCKED. Armed Doa for next trip!");
                    }
                } else {
                    if (carWasLocked) {
                        carWasLocked = false;
                        unlockEventPending = true;
                        unlockTimestamp = System.currentTimeMillis();
                        hasPlayedDoaThisSession = false;
                        System.out.println("[LOCK-MONITOR] Car was UNLOCKED! Armed Doa for new trip (" + unlockSoundPreset + ")...");
                        ensureAvasPersistence(true);
                        if (unlockSoundEnabled) {
                            triggerUnlockSound("button_unlock", unlockSoundPath, unlockSoundOutput);
                        }
                    }
                }

                // Doa Naik Kenderaan Trigger: Once per session when shifting into D or R
                int currentGear = readIntProp(PROP_VEHICLE_GEAR);
                if (currentGear <= 0) currentGear = readIntProp(289408000);
                int mcuGear = readIntProp(PROP_GEAR_MCU); // 0x287000EC (5=P, !=5 D/R/N)

                boolean isDriveOrReverse = (currentGear == 8 || currentGear == 2)
                        || (mcuGear > 0 && mcuGear != 5);

                if (isDriveOrReverse && !hasPlayedDoaThisSession) {
                    hasPlayedDoaThisSession = true;
                    String gDesc;
                    if (currentGear == 8) gDesc = "D (AOSP)";
                    else if (currentGear == 2) gDesc = "R (AOSP)";
                    else if (mcuGear == 2) gDesc = "R (MCU)";
                    else if (mcuGear > 0 && mcuGear != 5) gDesc = "D/R (MCU=" + mcuGear + ")";
                    else gDesc = "Driving";

                    System.out.println("[DOA] Shifted to " + gDesc + " for the first time this session! Playing Doa Naik Kenderaan (Omar & Hana)...");
                    playDoaNaikKenderaan();
                }


                // Expire unlock event after 60 seconds if no door opened
                if (unlockEventPending && (System.currentTimeMillis() - unlockTimestamp > 60000)) {
                    unlockEventPending = false;
                    System.out.println("[LOCK-MONITOR] Unlock event expired without door open.");
                }

                for (int i = 0; i < 4; i++) {
                    int current = readIntProp(DOOR_PROPS[i]);
                    if (current == -1) {
                        connectCarService();
                        continue;
                    }

                    int previous = lastDoorState[i];
                    if (previous != -1 && current != previous) {
                        boolean opened = (current == 0); // 0 = OPEN, 1 = CLOSED
                        int winRead = WIN_READ_PROPS[i];
                        int winWrite = WIN_WRITE_PROPS[i];
                        int currentWinPos = readIntProp(winRead);

                        int targetCrack = crackPercent;
                        int moreThanCrack = targetCrack + 4;

                        if (opened) {
                            System.out.println("[EVENT] " + NAMES[i] + " door OPENED (window currently " + currentWinPos + "%, daemon enabled=" + enabled + ")");
                            // Fallback welcome / unlock sound trigger (if button trigger missed or simultaneous door touch)
                            if (unlockSoundEnabled && unlockEventPending) {
                                unlockEventPending = false; // Consume immediately so subsequent doors or exits don't play
                                System.out.println("[WELCOME] " + NAMES[i] + " door opened (pending unlock)! Triggering welcome sound (" + unlockSoundPreset + ")...");
                                triggerUnlockSound(NAMES[i] + "_door_open", unlockSoundPath, unlockSoundOutput);
                            }

                            if (enabled && !ventilationMode) {
                                if (currentWinPos >= 0 && currentWinPos < targetCrack) {
                                    System.out.println(" -> Cracking " + NAMES[i] + " window to " + targetCrack + "% (Anti-pressure relief)");
                                    writeWindow(winWrite, targetCrack);
                                } else {
                                    System.out.println(" -> Window already at " + currentWinPos + "% (skipping crack)");
                                }
                            }
                        } else {
                            System.out.println("[EVENT] " + NAMES[i] + " door CLOSED (window currently " + currentWinPos + "%, daemon enabled=" + enabled + ")");
                            if (enabled && !ventilationMode) {
                                if (currentWinPos >= 0 && currentWinPos <= moreThanCrack) {
                                    System.out.println(" -> Closing " + NAMES[i] + " window back to 0%");
                                    writeWindow(winWrite, 0);
                                } else {
                                    System.out.println(" -> Window at " + currentWinPos + "% > " + moreThanCrack + "% (user opened, leaving it)");
                                }
                            }
                        }
                        lastDoorState[i] = current;
                    } else if (previous == -1) {
                        lastDoorState[i] = current;
                    }
                }

                // Poll every 120ms
                Thread.sleep(120);

            } catch (InterruptedException ie) {
                System.out.println("Daemon interrupted. Exiting.");
                break;
            } catch (Throwable t) {
                System.err.println("Daemon loop error: " + t.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                connectCarService();
            }
        }
    }
}
