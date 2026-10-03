import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.*;
import java.lang.reflect.Method;

/** Read-only VHAL gear-transition PoC for IHU801P. */
public final class GearAudioPoc {
    private static final int PROP_GEAR_MCU = 678428908;
    private static final int PROP_GEAR_AOSP = 289408001;
    private static final int PROP_SPEED_MCU = 678428840;
    private static final int PROP_SPEED_AOSP = 291504647;
    private static final int PROP_DOOR_FL = 678428815;
    private static final int AREA = 0;
    private static final long POLL_MS = 50L;
    private static final long COOLDOWN_MS = 5000L;
    private static final int STABLE_READS = 2;
    private static final double MAX_TRIGGER_SPEED_KMH = 1.0;

    private static Object propService;
    private static Method getProperty;
    private static volatile boolean running = true;
    // Preloaded WAV for AudioTrack cabin playback
    private static byte[] wavBytes;
    private static int wavSampleRate, wavChannels, wavBitsPerSample, wavDataPos, wavDataLen;
    private static volatile Process externalProcess;

    public static void main(String[] args) throws Exception {
        String wav = null, output = "cabin", pcm = null;
        boolean dryRun = false;
        long cooldown = COOLDOWN_MS;
        boolean onceMode = true; // default: play once upon boot, then exit cleanly
        for (int i = 0; i < args.length; i++) {
            if ("--wav".equals(args[i]) && i + 1 < args.length) wav = args[++i];
            else if ("--output".equals(args[i]) && i + 1 < args.length) output = args[++i];
            else if ("--pcm".equals(args[i]) && i + 1 < args.length) pcm = args[++i];
            else if ("--cooldown-ms".equals(args[i]) && i + 1 < args.length) cooldown = Long.parseLong(args[++i]);
            else if ("--dry-run".equals(args[i])) dryRun = true;
            else if ("--loop".equals(args[i])) onceMode = false; // stay running indefinitely
        }
        if (!"cabin".equals(output) && !"external".equals(output)) throw new IllegalArgumentException("output must be cabin|external");
        if ("external".equals(output) && (pcm == null || !pcm.matches("[0-9]+"))) throw new IllegalArgumentException("external output requires --pcm <numeric id>");
        if (!dryRun && (wav == null || !new File(wav).isFile())) throw new IllegalArgumentException("--wav must point to a readable WAV");
        final String selectedWav = wav, selectedOutput = output, selectedPcm = pcm;
        connectCarService();
        if (!dryRun) prepareAudio(selectedWav, selectedOutput);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> cleanupAudio()));
        System.out.println("[GearAudioPoc] read-only; gear=" + PROP_GEAR_MCU + " speed_guard<=" + MAX_TRIGGER_SPEED_KMH + "km/h output=" + output + " onceMode=" + onceMode + " dryRun=" + dryRun);

        String lastStable = "UNKNOWN", candidate = "UNKNOWN";
        int candidateReads = 0;
        long lastPlay = Long.MIN_VALUE / 2;

        while (running) {
            String current = readGear();
            if (!current.equals(candidate)) { candidate = current; candidateReads = 1; }
            else if (candidateReads < STABLE_READS) candidateReads++;
            if (candidateReads >= STABLE_READS && !current.equals(lastStable)) {
                long now = System.currentTimeMillis();
                System.out.println(now + " gear " + lastStable + " -> " + current);

                if ("D".equals(current) && !"D".equals(lastStable)) {
                    Double speed = readSpeedKmh();
                    if (speed == null || speed > MAX_TRIGGER_SPEED_KMH) {
                        System.out.println(now + " PLAY_SUPPRESSED speed=" + (speed == null ? "UNKNOWN" : speed) + "km/h");
                    } else {
                        long age = now - lastPlay;
                        if (age >= cooldown) {
                            if (dryRun) System.out.println(now + " PLAY_SUPPRESSED dry-run speed=" + speed + " output=" + selectedOutput + " wav=" + selectedWav);
                            else play(selectedWav, selectedOutput, selectedPcm);
                            lastPlay = now;

                            if (onceMode) {
                                System.out.println(now + " PLAY_COMPLETE: Session trigger fulfilled. Exiting cleanly (no more playback until next IHU reboot).");
                                try { Thread.sleep(1200); } catch (Throwable ignored) {}
                                break;
                            }
                        } else System.out.println(now + " PLAY_SUPPRESSED cooldown_remaining_ms=" + (cooldown - age));
                    }
                }
                lastStable = current;
            }
            Thread.sleep(POLL_MS);
        }
        cleanupAudio();
    }

    private static void connectCarService() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Object binder = sm.getMethod("getService", String.class).invoke(null, "car_service");
        if (binder == null) throw new IllegalStateException("car_service unavailable");
        Class<?> carStub = Class.forName("android.car.ICar$Stub");
        Object car = carStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
        Object propBinder = car.getClass().getMethod("getCarService", String.class).invoke(car, "property");
        Class<?> propStub = Class.forName("android.car.hardware.property.ICarProperty$Stub");
        propService = propStub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, propBinder);
        for (Method m : propService.getClass().getMethods()) if ("getProperty".equals(m.getName()) && m.getParameterTypes().length == 2) { getProperty = m; break; }
        if (getProperty == null) throw new IllegalStateException("getProperty unavailable");
    }

    private static String readGear() {
        Integer mcu = readInt(PROP_GEAR_MCU);
        if (mcu != null) {
            switch (mcu) { case 2: return "D"; case 4: return "R"; case 3: case 1: return "N"; case 5: return "P"; default: return "UNKNOWN(" + mcu + ")"; }
        }
        Integer aosp = readInt(PROP_GEAR_AOSP);
        if (aosp != null) { switch (aosp) { case 8: return "D"; case 2: return "R"; case 1: return "N"; case 4: return "P"; default: break; } }
        return "UNKNOWN";
    }

    private static Double readSpeedKmh() {
        Integer raw = readInt(PROP_SPEED_MCU);
        if (raw != null && raw >= 0) return raw / 100.0;
        try {
            Object cpv = getProperty.invoke(propService, PROP_SPEED_AOSP, AREA);
            if (cpv == null) return null;
            Object value = cpv.getClass().getMethod("getValue").invoke(cpv);
            if (value instanceof Float) return ((Float) value).doubleValue();
            if (value instanceof Double) return (Double) value;
            if (value instanceof Integer) return ((Integer) value).doubleValue();
        } catch (Throwable t) { System.err.println("[GearAudioPoc] speed read failed: " + t); }
        return null;
    }

    private static Integer readInt(int property) {
        try {
            Object cpv = getProperty.invoke(propService, property, AREA);
            if (cpv == null) return null;
            Object value = cpv.getClass().getMethod("getValue").invoke(cpv);
            if (value instanceof Integer) return (Integer) value;
            if (value instanceof byte[]) { byte[] b = (byte[]) value; return b.length == 0 ? null : (b[0] & 0xff); }
            if (value instanceof Short) return ((Short) value).intValue();
            if (value instanceof Byte) return ((Byte) value).intValue();
        } catch (Throwable t) { System.err.println("[GearAudioPoc] read failed prop=" + property + " " + t); }
        return null;
    }

    private static Boolean readDoorFL() {
        try {
            Object cpv = getProperty.invoke(propService, PROP_DOOR_FL, AREA);
            if (cpv == null) return null;
            Object value = cpv.getClass().getMethod("getValue").invoke(cpv);
            if (value instanceof byte[]) { byte[] b = (byte[]) value; return b.length > 0 ? (b[0] & 0xFF) == 0 : null; }
            if (value instanceof Integer) return ((Integer) value) == 0;
        } catch (Throwable t) { System.err.println("[GearAudioPoc] door read failed: " + t); }
        return null;
    }

    private static void prepareAudio(String wav, String output) throws Exception {
        if ("external".equals(output)) return;
        File f = new File(wav);
        wavBytes = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int total = 0;
            while (total < wavBytes.length) { int r = fis.read(wavBytes, total, wavBytes.length - total); if (r == -1) break; total += r; }
        }
        if (wavBytes.length < 44) throw new IllegalStateException("WAV too small");
        wavSampleRate = 48000; wavChannels = 1; wavBitsPerSample = 16; wavDataPos = -1; wavDataLen = 0;
        int pos = 12;
        while (pos < wavBytes.length - 8) {
            String chunkId = new String(wavBytes, pos, 4, "US-ASCII");
            int chunkLen = (wavBytes[pos+4]&0xFF)|((wavBytes[pos+5]&0xFF)<<8)|((wavBytes[pos+6]&0xFF)<<16)|((wavBytes[pos+7]&0xFF)<<24);
            if ("fmt ".equals(chunkId)) {
                wavChannels = (wavBytes[pos+10]&0xFF)|((wavBytes[pos+11]&0xFF)<<8);
                wavSampleRate = (wavBytes[pos+12]&0xFF)|((wavBytes[pos+13]&0xFF)<<8)|((wavBytes[pos+14]&0xFF)<<16)|((wavBytes[pos+15]&0xFF)<<24);
                wavBitsPerSample = (wavBytes[pos+22]&0xFF)|((wavBytes[pos+23]&0xFF)<<8);
            } else if ("data".equals(chunkId)) {
                wavDataPos = pos + 8;
                wavDataLen = Math.min(chunkLen, wavBytes.length - wavDataPos);
                break;
            }
            pos += 8 + chunkLen; if ((chunkLen & 1) != 0) pos++;
        }
        if (wavDataPos == -1 || wavDataLen <= 0) throw new IllegalStateException("WAV data chunk not found");
        if (wavSampleRate <= 0 || wavSampleRate > 96000) wavSampleRate = 48000;
        if (wavChannels <= 0 || wavChannels > 2) wavChannels = 1;
        System.out.println(System.currentTimeMillis() + " AUDIOTRACK_PRELOAD bytes=" + wavBytes.length + " rate=" + wavSampleRate + " ch=" + wavChannels + " bits=" + wavBitsPerSample + " dataLen=" + wavDataLen);
    }

    private static void play(String wav, String output, String pcm) {
        try {
            long started = System.currentTimeMillis();
            if ("external".equals(output)) {
                stopExternal();
                Process p = new ProcessBuilder("/system/bin/tinyplay", wav, "-D", "0", "-d", pcm).redirectErrorStream(true).start();
                externalProcess = p;
                System.out.println(started + " PLAY_EXTERNAL_START bytes=" + new File(wav).length());
                Thread reap = new Thread(() -> { try { int code = p.waitFor(); System.out.println(System.currentTimeMillis() + " PLAY_EXTERNAL_EXIT code=" + code); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } if (externalProcess == p) externalProcess = null; }, "tinyplay-reaper");
                reap.setDaemon(true); reap.start();
            } else {
                new Thread(() -> {
                    try {
                        int channelConfig = (wavChannels == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                        int audioFormat = (wavBitsPerSample == 8) ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;
                        int minBuf = AudioTrack.getMinBufferSize(wavSampleRate, channelConfig, audioFormat);
                        AudioTrack track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(audioFormat)
                                .setSampleRate(wavSampleRate)
                                .setChannelMask(channelConfig)
                                .build())
                            .setBufferSizeInBytes(Math.max(minBuf, 32768))
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                        track.setVolume(1.0f);
                        track.play();
                        track.write(wavBytes, wavDataPos, wavDataLen);
                        int bytesPerFrame = wavChannels * (wavBitsPerSample / 8);
                        Thread.sleep(Math.max(50, (wavDataLen * 1000L) / (wavSampleRate * bytesPerFrame) + 50));
                        track.stop();
                        track.release();
                        System.out.println(System.currentTimeMillis() + " PLAY_CABIN_DONE dataLen=" + wavDataLen);
                    } catch (Throwable t) { System.err.println("[GearAudioPoc] cabin playback error: " + t); }
                }, "cabin-play").start();
                System.out.println(started + " PLAY_CABIN_START rate=" + wavSampleRate + " ch=" + wavChannels);
            }
        } catch (Throwable t) { System.err.println("[GearAudioPoc] playback failed: " + t); }
    }

    private static void stopExternal() { Process p = externalProcess; if (p != null && p.isAlive()) { p.destroy(); System.out.println(System.currentTimeMillis() + " PLAY_EXTERNAL_STOP previous=true"); } externalProcess = null; }
    private static void cleanupAudio() { stopExternal(); wavBytes = null; }
}
