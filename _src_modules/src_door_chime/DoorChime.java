import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import com.emas.door.AndroidDoorClock;
import com.emas.door.DoorChimeObserver;
import com.emas.door.DoorHubClient;
import com.emas.door.DoorStateHub;
import com.emas.door.DoorVhalReader;
import java.io.*;
import java.util.concurrent.*;

/**
 * DoorChime - Responsive Luxury EV Door Open & Close Chime Daemon for IHU801P.
 * 
 * - Reads one cached snapshot from DoorStateHub on loopback port 8885.
 * - Read-only VHAL fallback after sustained hub failure; source changes establish a silent baseline.
 * - Door Open: Plays open WAV (e.g. /data/local/window/door_chime.wav)
 * - Door Close: Plays close WAV (e.g. /data/local/window/door_close_chime.wav)
 * - 2-second Cooldown Protection on open & close transitions to prevent spam.
 */
public final class DoorChime {
    private static final ThreadPoolExecutor AUDIO = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task -> {
                Thread thread = new Thread(task, "door-chime-audio"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private static class WavAudio {
        byte[] bytes;
        int sampleRate = 48000;
        int channels = 2;
        int dataPos = -1;
        int dataLen = 0;
        final String name;

        WavAudio(String name) {
            this.name = name;
        }

        boolean load(String path) {
            File f = new File(path);
            if (!f.exists() || f.length() < 44) {
                System.err.println("[DoorChime] WAV file not found: " + path);
                return false;
            }
            try (FileInputStream fis = new FileInputStream(f)) {
                bytes = new byte[(int) f.length()];
                int read = 0;
                while (read < bytes.length) {
                    int r = fis.read(bytes, read, bytes.length - read);
                    if (r == -1) break;
                    read += r;
                }
                int pos = 12;
                while (pos < bytes.length - 8) {
                    String chunkId = new String(bytes, pos, 4, "US-ASCII");
                    int chunkLen = (bytes[pos + 4] & 0xFF) | ((bytes[pos + 5] & 0xFF) << 8) |
                                   ((bytes[pos + 6] & 0xFF) << 16) | ((bytes[pos + 7] & 0xFF) << 24);
                    if ("fmt ".equals(chunkId)) {
                        channels = (short) ((bytes[pos + 10] & 0xFF) | ((bytes[pos + 11] & 0xFF) << 8));
                        sampleRate = (bytes[pos + 12] & 0xFF) | ((bytes[pos + 13] & 0xFF) << 8) |
                                     ((bytes[pos + 14] & 0xFF) << 16) | ((bytes[pos + 15] & 0xFF) << 24);
                    } else if ("data".equals(chunkId)) {
                        dataPos = pos + 8;
                        dataLen = Math.min(chunkLen, bytes.length - dataPos);
                        break;
                    }
                    pos += 8 + chunkLen;
                    if ((chunkLen & 1) != 0) pos++;
                }
                System.out.println("[DoorChime] Loaded " + name + " (" + path + "): " + sampleRate + "Hz, ch=" + channels + ", len=" + dataLen);
                return (dataPos > 0 && dataLen > 0);
            } catch (Throwable t) {
                System.err.println("[DoorChime] Failed to load " + path + ": " + t.getMessage());
                return false;
            }
        }

        void playAsync() {
            if (dataPos == -1 || dataLen <= 0 || bytes == null) return;
            try { AUDIO.execute(new Runnable() {
                @Override
                public void run() {
                    AudioTrack track = null;
                    try {
                        int channelConfig = (channels == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
                        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
                        track = new AudioTrack.Builder()
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build())
                                .setAudioFormat(new AudioFormat.Builder()
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setSampleRate(sampleRate)
                                        .setChannelMask(channelConfig)
                                        .build())
                                .setBufferSizeInBytes(Math.max(minBuf, 32768))
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build();

                        track.setVolume(1.0f);
                        track.play();
                        track.write(bytes, dataPos, dataLen);

                        int bytesPerFrame = channels * 2;
                        long durMs = (dataLen * 1000L) / (sampleRate * bytesPerFrame);
                        Thread.sleep(Math.max(100, durMs + 50));
                    } catch (Throwable t) {
                        System.err.println("[DoorChime] Playback error (" + name + "): " + t.getMessage());
                    } finally {
                        if (track != null) {
                            try {
                                track.stop();
                            } catch (Throwable ignored) {}
                            try {
                                track.release();
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            }); } catch (RejectedExecutionException e) {
                System.err.println("[DoorChime] Audio queue full; skipped " + name);
            }
        }
    }

    private static final WavAudio openAudio = new WavAudio("OPEN");
    private static final WavAudio closeAudio = new WavAudio("CLOSE");

    public enum DoorState {
        OPEN, CLOSED, UNKNOWN
    }

    public static void main(String[] args) throws Exception {
        String openPath = "/data/local/window/door_chime.wav";
        String closePath = "/data/local/window/door_close_chime.wav";

        if (args.length > 0 && args[0] != null && !args[0].isEmpty()) {
            openPath = args[0];
        }
        if (args.length > 1 && args[1] != null && !args[1].isEmpty()) {
            closePath = args[1];
        }

        openAudio.load(openPath);
        closeAudio.load(closePath);

        DoorStateHub.Clock clock = new AndroidDoorClock();
        boolean allowFallback = args.length < 3 || !"--hub-only".equals(args[2]);
        DoorHubClient client = new DoorHubClient(clock, new DoorHubClient.HttpTransport(),
                allowFallback ? new DoorVhalReader(clock) : null);
        DoorChimeObserver observer = new DoorChimeObserver();
        String lastSource = "";
        System.out.println("[DoorChime] DoorStateHub client ready; VHAL fallback=" + allowFallback);

        while (true) {
            try {
                DoorHubClient.Reading reading = client.next();
                if (!reading.source.equals(lastSource)) {
                    System.out.println("[DoorChime] Source=" + reading.source + "; silent baseline");
                    lastSource = reading.source;
                }
                int events = observer.observe(reading, clock.elapsedMillis());
                if ((events & DoorChimeObserver.OPEN_CHIME) != 0) openAudio.playAsync();
                if ((events & DoorChimeObserver.CLOSE_CHIME) != 0) closeAudio.playAsync();
                Thread.sleep(DoorStateHub.POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); client.close(); AUDIO.shutdownNow(); break;
            } catch (RuntimeException e) {
                observer.observe(new DoorHubClient.Reading("unavailable", DoorStateHub.unknownStates()), clock.elapsedMillis());
                System.err.println("[DoorChime] Client error: " + e.getMessage());
                try { Thread.sleep(500); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); client.close(); AUDIO.shutdownNow(); break;
                }
            }
        }
    }
}
