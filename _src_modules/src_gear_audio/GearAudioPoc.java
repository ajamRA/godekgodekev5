import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.*;
import java.lang.reflect.Method;

/**
 * GearAudioPoc - Plays Doa Naik Kenderaan upon transitioning to 'D' (Drive).
 * 
 * - Monitors VHAL gear properties:
 *     PROP_GEAR_MCU = 678428908
 *     PROP_GEAR_AOSP = 289408001
 * - Plays cabin AudioTrack with clean asynchronous or synchronous completion.
 * - Defaults to one-shot mode (plays once per driving session / IHU boot).
 */
public final class GearAudioPoc {
    private static final int PROP_GEAR_MCU = 678428908;
    private static final int PROP_GEAR_AOSP = 289408001;
    private static final int PROP_SPEED_MCU = 678428840;
    private static final int PROP_SPEED_AOSP = 291504647;
    private static final int AREA = 0;
    private static final long POLL_MS = 60L;
    private static final long COOLDOWN_MS = 5000L;
    private static final int STABLE_READS = 2;
    private static final double MAX_TRIGGER_SPEED_KMH = 3.0;

    private static Object propService;
    private static Method getProperty;
    private static volatile boolean running = true;

    private static byte[] wavBytes;
    private static int wavSampleRate = 48000;
    private static int wavChannels = 2;
    private static int wavBitsPerSample = 16;
    private static int wavDataPos = -1;
    private static int wavDataLen = 0;

    public static void main(String[] args) throws Exception {
        String wav = "/data/local/window/gear_audio/gear_doa.wav";
        long cooldown = COOLDOWN_MS;
        boolean onceMode = true; // default: play once upon shifting to D, then exit cleanly

        for (int i = 0; i < args.length; i++) {
            if ("--wav".equals(args[i]) && i + 1 < args.length) wav = args[++i];
            else if ("--cooldown-ms".equals(args[i]) && i + 1 < args.length) cooldown = Long.parseLong(args[++i]);
            else if ("--loop".equals(args[i])) onceMode = false;
        }

        File f = new File(wav);
        if (!f.isFile() || f.length() < 44) {
            System.err.println("[GearAudio] ERROR: WAV file not found: " + wav);
            return;
        }

        prepareAudio(f);
        connectCarService();
        System.out.println("[GearAudio] Ready. Monitoring gear for 'D' transition. onceMode=" + onceMode);

        String lastStable = "UNKNOWN", candidate = "UNKNOWN";
        int candidateReads = 0;
        long lastPlay = Long.MIN_VALUE / 2;

        while (running) {
            String current = readGear();
            if (!current.equals(candidate)) {
                candidate = current;
                candidateReads = 1;
            } else if (candidateReads < STABLE_READS) {
                candidateReads++;
            }

            if (candidateReads >= STABLE_READS && !current.equals(lastStable)) {
                long now = System.currentTimeMillis();
                System.out.println(now + " [GearAudio] Gear transition: " + lastStable + " -> " + current);

                if ("D".equals(current) && !"D".equals(lastStable)) {
                    Double speed = readSpeedKmh();
                    if (speed != null && speed > MAX_TRIGGER_SPEED_KMH) {
                        System.out.println(now + " [GearAudio] Suppressed due to speed=" + speed + "km/h");
                    } else {
                        long age = now - lastPlay;
                        if (age >= cooldown) {
                            lastPlay = now;
                            System.out.println(now + " [GearAudio] Entering 'D' -> Playing Doa Naik Kenderaan in cabin...");
                            playCabinAudioSync();

                            if (onceMode) {
                                System.out.println(now + " [GearAudio] Playback complete. Session fulfilled. Exiting.");
                                break;
                            }
                        } else {
                            System.out.println(now + " [GearAudio] Suppressed by cooldown (" + (cooldown - age) + "ms remaining)");
                        }
                    }
                }
                lastStable = current;
            }
            Thread.sleep(POLL_MS);
        }
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
        for (Method m : propService.getClass().getMethods()) {
            if ("getProperty".equals(m.getName()) && m.getParameterTypes().length == 2) {
                getProperty = m;
                break;
            }
        }
        if (getProperty == null) throw new IllegalStateException("getProperty unavailable");
    }

    private static String readGear() {
        Integer mcu = readInt(PROP_GEAR_MCU);
        if (mcu != null) {
            switch (mcu) {
                case 2: return "D";
                case 4: return "R";
                case 3: case 1: return "N";
                case 5: return "P";
                default: return "UNKNOWN(" + mcu + ")";
            }
        }
        Integer aosp = readInt(PROP_GEAR_AOSP);
        if (aosp != null) {
            switch (aosp) {
                case 8: return "D";
                case 2: return "R";
                case 1: return "N";
                case 4: return "P";
                default: break;
            }
        }
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
        } catch (Throwable ignored) {}
        return null;
    }

    private static Integer readInt(int property) {
        try {
            Object cpv = getProperty.invoke(propService, property, AREA);
            if (cpv == null) return null;
            Object value = cpv.getClass().getMethod("getValue").invoke(cpv);
            if (value instanceof Integer) return (Integer) value;
            if (value instanceof byte[]) {
                byte[] b = (byte[]) value;
                return b.length == 0 ? null : (b[0] & 0xff);
            }
            if (value instanceof Short) return ((Short) value).intValue();
            if (value instanceof Byte) return ((Byte) value).intValue();
        } catch (Throwable ignored) {}
        return null;
    }

    private static void prepareAudio(File f) throws Exception {
        wavBytes = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int total = 0;
            while (total < wavBytes.length) {
                int r = fis.read(wavBytes, total, wavBytes.length - total);
                if (r == -1) break;
                total += r;
            }
        }
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
            pos += 8 + chunkLen;
            if ((chunkLen & 1) != 0) pos++;
        }
        if (wavDataPos == -1 || wavDataLen <= 0) throw new IllegalStateException("WAV data chunk not found");
        if (wavSampleRate <= 0 || wavSampleRate > 96000) wavSampleRate = 48000;
        if (wavChannels <= 0 || wavChannels > 2) wavChannels = 2;
        System.out.println("[GearAudio] Preloaded audio: " + wavSampleRate + "Hz, ch=" + wavChannels + ", bits=" + wavBitsPerSample + ", dataLen=" + wavDataLen);
    }

    private static void playCabinAudioSync() {
        if (wavDataPos == -1 || wavDataLen <= 0 || wavBytes == null) return;
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
                .setBufferSizeInBytes(Math.max(minBuf, 65536))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

            track.setVolume(1.0f);
            track.play();
            track.write(wavBytes, wavDataPos, wavDataLen);

            int bytesPerFrame = wavChannels * (wavBitsPerSample / 8);
            long durMs = (wavDataLen * 1000L) / (wavSampleRate * bytesPerFrame);
            Thread.sleep(Math.max(200, durMs + 100));
            track.stop();
            track.release();
            System.out.println("[GearAudio] Playback completed successfully (" + durMs + "ms).");
        } catch (Throwable t) {
            System.err.println("[GearAudio] Playback error: " + t.getMessage());
        }
    }
}
