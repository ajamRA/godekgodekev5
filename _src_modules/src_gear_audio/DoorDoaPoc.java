import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.*;
import java.lang.reflect.Method;

/**
 * Read-only VHAL door-open trigger PoC for IHU801P.
 * Plays doa naik kenderaan (cabin speaker) when driver door opens.
 * One-shot per session: triggers once, then exits or idles.
 *
 * Door FL: VHAL 678428815 (byte[] — 0x00 = open, 0x01 = closed)
 *
 * Usage:
 *   CLASSPATH=/data/local/tmp/DoorDoaPoc.jar app_process /data/local/tmp DoorDoaPoc \
 *     --wav /data/local/window/locksounds/unlock_omar_hana.wav --dry-run
 *
 * Remove --dry-run after verifying door detection works.
 */
public final class DoorDoaPoc {
    private static final int PROP_DOOR_FL = 678428815; // Front-Left (Passenger in RHD Malaysia)
    private static final int PROP_DOOR_FR = 678428816; // Front-Right (Driver in RHD Malaysia)
    private static final int AREA = 0;
    private static final long POLL_MS = 100L;
    private static final int STABLE_READS = 2;

    private static Object propService;
    private static Method getProperty;

    public static void main(String[] args) throws Exception {
        String wav = "/data/local/window/locksounds/unlock_omar_hana.wav";
        boolean dryRun = false;
        boolean keepAlive = false;
        for (int i = 0; i < args.length; i++) {
            if ("--wav".equals(args[i]) && i + 1 < args.length) wav = args[++i];
            else if ("--dry-run".equals(args[i])) dryRun = true;
            else if ("--keep-alive".equals(args[i])) keepAlive = true;
        }
        if (!dryRun && !new File(wav).isFile()) throw new IllegalArgumentException("--wav must point to a readable WAV: " + wav);
        final String selectedWav = wav;
        connectCarService();
        System.out.println("[DoorDoaPoc] read-only; monitoring FRONT doors (FL=" + PROP_DOOR_FL + ", FR=" + PROP_DOOR_FR + ") wav=" + wav + " dryRun=" + dryRun);

        // Wait for initial state: front doors should be CLOSED first
        Boolean lastAnyOpen = null;
        String candidate = null;
        int candidateReads = 0;
        boolean triggered = false;

        while (true) {
            Boolean openFL = isDoorOpen(PROP_DOOR_FL);
            Boolean openFR = isDoorOpen(PROP_DOOR_FR);
            Boolean anyOpen = (openFL != null && openFL.booleanValue()) || (openFR != null && openFR.booleanValue());
            String state = (openFL == null && openFR == null) ? "UNKNOWN" : (anyOpen ? "OPEN" : "CLOSED");

            if (!state.equals(candidate)) { candidate = state; candidateReads = 1; }
            else if (candidateReads < STABLE_READS) candidateReads++;

            if (candidateReads >= STABLE_READS) {
                String lastState = lastAnyOpen == null ? "UNKNOWN" : (lastAnyOpen ? "OPEN" : "CLOSED");
                if (!state.equals(lastState)) {
                    long now = System.currentTimeMillis();
                    String detail = (openFR != null && openFR.booleanValue() ? "FR_DRIVER " : "") + (openFL != null && openFL.booleanValue() ? "FL_PASSENGER" : "");
                    System.out.println(now + " front_doors " + lastState + " -> " + state + " [" + detail.trim() + "]");

                    // Trigger: CLOSED -> OPEN, one-shot only
                    if ("OPEN".equals(state) && "CLOSED".equals(lastState) && !triggered) {
                        if (dryRun) {
                            System.out.println(now + " DOA_SUPPRESSED dry-run wav=" + selectedWav);
                        } else {
                            System.out.println(now + " DOA_PLAY_START (" + detail.trim() + ") wav=" + selectedWav);
                            playCabinAudio(new File(selectedWav));
                            System.out.println(System.currentTimeMillis() + " DOA_PLAY_DONE");
                        }
                        triggered = true;
                        if (!keepAlive) {
                            System.out.println(System.currentTimeMillis() + " SESSION_DONE one-shot exit (no double play)");
                            return;
                        }
                    }
                }
                lastAnyOpen = "OPEN".equals(state) ? Boolean.TRUE : "CLOSED".equals(state) ? Boolean.FALSE : lastAnyOpen;
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
        for (Method m : propService.getClass().getMethods()) if ("getProperty".equals(m.getName()) && m.getParameterTypes().length == 2) { getProperty = m; break; }
        if (getProperty == null) throw new IllegalStateException("getProperty unavailable");
    }

    private static Boolean isDoorOpen(int prop) {
        try {
            Object cpv = getProperty.invoke(propService, prop, AREA);
            if (cpv == null) return null;
            Object value = cpv.getClass().getMethod("getValue").invoke(cpv);
            if (value instanceof byte[]) { byte[] b = (byte[]) value; return b.length > 0 ? (b[0] & 0xFF) == 0 : null; }
            if (value instanceof Integer) return ((Integer) value) == 0;
        } catch (Throwable t) { System.err.println("[DoorDoaPoc] read failed prop=" + prop + " " + t); }
        return null;
    }

    private static void playCabinAudio(File f) {
        try {
            if (!f.exists()) { System.err.println("[DoorDoaPoc] WAV not found: " + f); return; }
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
            int sampleRate = 48000;
            short channels = 1;
            short bitsPerSample = 16;
            int dataPos = -1, dataLen = 0;
            int pos = 12;
            while (pos < fileBytes.length - 8) {
                String chunkId = new String(fileBytes, pos, 4, "US-ASCII");
                int chunkLen = (fileBytes[pos+4]&0xFF)|((fileBytes[pos+5]&0xFF)<<8)|((fileBytes[pos+6]&0xFF)<<16)|((fileBytes[pos+7]&0xFF)<<24);
                if ("fmt ".equals(chunkId)) {
                    channels = (short)((fileBytes[pos+10]&0xFF)|((fileBytes[pos+11]&0xFF)<<8));
                    sampleRate = (fileBytes[pos+12]&0xFF)|((fileBytes[pos+13]&0xFF)<<8)|((fileBytes[pos+14]&0xFF)<<16)|((fileBytes[pos+15]&0xFF)<<24);
                    bitsPerSample = (short)((fileBytes[pos+22]&0xFF)|((fileBytes[pos+23]&0xFF)<<8));
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
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
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
                .setBufferSizeInBytes(Math.max(minBuf, 32768))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
            track.setVolume(1.0f);
            track.play();
            track.write(fileBytes, dataPos, dataLen);
            int bytesPerFrame = channels * (bitsPerSample / 8);
            Thread.sleep(Math.max(100, (dataLen * 1000L) / (sampleRate * bytesPerFrame) + 50));
            track.stop();
            track.release();
            System.out.println("[DoorDoaPoc] Cabin playback finished (" + dataLen + " bytes, " + sampleRate + "Hz)");
        } catch (Throwable t) {
            System.err.println("[DoorDoaPoc] Cabin playback error: " + t.getMessage());
        }
    }
}
