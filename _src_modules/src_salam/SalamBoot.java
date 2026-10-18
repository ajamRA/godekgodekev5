import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.File;
import java.io.FileInputStream;

/**
 * SalamBoot - Plays cabin welcome sequence (Salam + Doa Naik Kenderaan)
 * upon IHU boot completion.
 * 
 * - Standalone, zero VHAL/car_service dependencies.
 * - Sequential multi-track playlist support with natural breathing pauses.
 * - Exits cleanly upon completion.
 */
public final class SalamBoot {

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            args = new String[] {
                "/data/local/window/salam/salam.wav",
                "/data/local/window/salam/doa.wav"
            };
        }

        System.out.println("[SalamBoot] Starting boot audio sequence (" + args.length + " tracks)...");

        for (int i = 0; i < args.length; i++) {
            String wavPath = args[i];
            if (wavPath == null || wavPath.trim().isEmpty()) continue;

            File f = new File(wavPath.trim());
            if (!f.exists() || f.length() < 44) {
                System.err.println("[SalamBoot] Skip missing/invalid file: " + wavPath);
                continue;
            }

            System.out.println("[SalamBoot] (" + (i + 1) + "/" + args.length + ") Playing: " + f.getName());
            try {
                playWav(f);
                if (i < args.length - 1) {
                    Thread.sleep(600); // 0.6s breathing room between Salam and Doa
                }
            } catch (Throwable t) {
                System.err.println("[SalamBoot] Playback error on " + wavPath + ": " + t.getMessage());
            }
        }

        System.out.println("[SalamBoot] Sequence completed successfully. Exiting.");
    }

    private static void playWav(File f) throws Exception {
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
        short channels = 2;
        short bitsPerSample = 16;
        int dataPos = -1, dataLen = 0;
        int pos = 12;

        while (pos < fileBytes.length - 8) {
            String chunkId = new String(fileBytes, pos, 4, "US-ASCII");
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

        if (dataPos == -1 || dataLen <= 0) {
            throw new IllegalArgumentException("No data chunk found in WAV");
        }

        if (sampleRate <= 0 || sampleRate > 96000) sampleRate = 48000;
        if (channels <= 0 || channels > 2) channels = 2;

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
                .setBufferSizeInBytes(Math.max(minBuf, 65536))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        try {
            track.setVolume(1.0f);
            track.play();
            track.write(fileBytes, dataPos, dataLen);

            int bytesPerFrame = channels * (bitsPerSample / 8);
            long durationMs = (dataLen * 1000L) / (sampleRate * bytesPerFrame);
            Thread.sleep(Math.max(100, durationMs + 100));
        } finally {
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }
    }
}
