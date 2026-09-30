package com.emas.megaphone;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Dedicated, Standalone Megaphone & Prayer (Doa) Daemon for Proton e.MAS 5 / IHU801P.
 * 
 * Port: 8890
 * Single Responsibility: 
 * 1. Exterior Bumper Speaker (tinyplay ALSA pcmC0D29p)
 * 2. In-Cabin Prayer (AudioTrack)
 * 3. Cloud VPS Sync Worker (polls https://emas.43-159-63-29.nip.io/api/car/sync for phone app broadcasts)
 * 
 * Fully decoupled from Telemetry and AVAS tuning.
 */
public class MegaphoneDaemon {

    static final int HTTP_PORT = 8890;
    static final String VPS_SYNC_URL = "https://emas.43-159-63-29.nip.io/api/car/sync";
    static final String MEGAFON_DIR = "/data/local/window/megafon";
    static final String TINYPLAY_BIN = "/data/local/window/tinyplay";
    static final String DOA_PATH = "/data/local/window/locksounds/unlock_omar_hana.wav";

    private static final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private static volatile String lastPlayedMegaphoneId = "";
    private final ThreadPoolExecutor httpWorkers = new ThreadPoolExecutor(
            3, 3, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8));

    public static void main(String[] args) {
        System.out.println("=== EMAS MEGAPHONE & DOA DAEMON v1.1 (CLOUD SYNC ENABLED) ===");
        System.out.println("Port: " + HTTP_PORT);
        System.out.println("VPS Sync: " + VPS_SYNC_URL);
        System.out.println("Megafon Directory: " + MEGAFON_DIR);
        System.out.println("Tinyplay Binary: " + TINYPLAY_BIN);

        File dir = new File(MEGAFON_DIR);
        if (!dir.exists()) dir.mkdirs();

        MegaphoneDaemon daemon = new MegaphoneDaemon();
        daemon.startCloudSync();
        daemon.startHttpServer();
    }

    private void startCloudSync() {
        Thread syncThread = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("[MegaphoneDaemon] Cloud Sync loop active -> " + VPS_SYNC_URL);
                long sleepMs = 45000; // Default to 45s power/data save mode
                while (true) {
                    try {
                        Thread.sleep(sleepMs);
                        URL url = new URL(VPS_SYNC_URL);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/json");

                        String payload = "{\"client\":\"MegaphoneDaemon\",\"playing\":" + isPlaying.get() + "}";
                        conn.getOutputStream().write(payload.getBytes("UTF-8"));

                        if (conn.getResponseCode() == 200) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line);
                            String resp = sb.toString();

                            try {
                                JSONObject root = new JSONObject(resp);

                                // Adaptive polling interval from server (1.5s when active, 45s when idle/switch off)
                                if (root.has("poll_interval_sec")) {
                                    double sec = root.optDouble("poll_interval_sec", 45.0);
                                    sleepMs = Math.max(1000, (long)(sec * 1000));
                                } else if (root.optBoolean("megaphone_switch", false)) {
                                    sleepMs = 1500;
                                } else {
                                    sleepMs = 45000;
                                }

                                if (root.has("megaphone") && !root.isNull("megaphone")) {
                                    JSONObject m = root.getJSONObject("megaphone");
                                    String msgId = m.optString("id");
                                    String audioUrl = m.optString("audio_url");
                                    if (!msgId.isEmpty() && !msgId.equals(lastPlayedMegaphoneId) && !audioUrl.isEmpty()) {
                                        System.out.println("[MegaphoneDaemon-SYNC] New Megaphone broadcast from phone/cloud: " + msgId);
                                        File targetAudio = new File(MEGAFON_DIR, "current.wav");
                                        downloadFile(audioUrl, targetAudio);
                                        if (targetAudio.exists() && targetAudio.length() > 500) {
                                            lastPlayedMegaphoneId = msgId;
                                            sleepMs = 1500; // Stay fast while active
                                            if (isPlaying.compareAndSet(false, true)) {
                                                new Thread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        try {
                                                            playExternalAudio(targetAudio.getAbsolutePath());
                                                        } finally {
                                                            isPlaying.set(false);
                                                        }
                                                    }
                                                }).start();
                                                System.out.println("[MegaphoneDaemon-SYNC] Playing broadcast: " + msgId);
                                            }
                                        }
                                    }
                                }
                            } catch (Throwable jsonErr) {
                                System.err.println("[MegaphoneDaemon-SYNC] JSON parse error: " + jsonErr.getMessage());
                            }
                        }
                    } catch (Throwable ignored) {
                        sleepMs = 30000; // Backoff on connection drop
                    }
                }
            }
        });
        syncThread.setDaemon(true);
        syncThread.start();
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
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                }
                System.out.println("[MegaphoneDaemon] Downloaded audio -> " + dest.getAbsolutePath() + " (" + dest.length() + " bytes)");
            }
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon] downloadFile error: " + t.getMessage());
        }
    }

    private void startHttpServer() {
        try {
            ServerSocket server = new ServerSocket(HTTP_PORT, 10, InetAddress.getByName("0.0.0.0"));
            System.out.println("[MegaphoneDaemon] HTTP listening on 0.0.0.0:" + HTTP_PORT);
            while (true) {
                final Socket s = server.accept();
                httpWorkers.execute(() -> handleClient(s));
            }
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon] Server fatal error: " + t);
            t.printStackTrace();
        }
    }

    private void handleClient(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
            String reqLine = null;
            int contentLength = 0;
            String contentType = "";

            int prev = -1;
            int b;
            while ((b = in.read()) != -1) {
                if (prev == '\r' && b == '\n') {
                    String line = lineBuf.toString("UTF-8").trim();
                    lineBuf.reset();
                    if (reqLine == null) {
                        reqLine = line;
                    } else if (line.isEmpty()) {
                        break; // End of HTTP headers
                    } else {
                        String lower = line.toLowerCase();
                        if (lower.startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        } else if (lower.startsWith("content-type:")) {
                            contentType = line.substring(13).trim();
                        }
                    }
                } else if (b != '\r') {
                    lineBuf.write(b);
                }
                prev = b;
            }

            if (reqLine == null) {
                s.close();
                return;
            }

            String[] parts = reqLine.split(" ");
            if (parts.length < 2) {
                s.close();
                return;
            }

            String method = parts[0].toUpperCase();
            String fullPath = parts[1];
            String path = fullPath;
            String query = "";
            int qIdx = fullPath.indexOf('?');
            if (qIdx != -1) {
                path = fullPath.substring(0, qIdx);
                query = fullPath.substring(qIdx + 1);
            }

            // CORS Preflight
            if ("OPTIONS".equals(method)) {
                sendCors(out, 204, "No Content", "text/plain", "");
                s.close();
                return;
            }

            // Read Body if present
            byte[] bodyBytes = null;
            if (contentLength > 0 && contentLength <= 10 * 1024 * 1024) { // max 10MB
                bodyBytes = new byte[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int r = in.read(bodyBytes, totalRead, contentLength - totalRead);
                    if (r == -1) break;
                    totalRead += r;
                }
            }

            // Routing
            if ("/api/status".equals(path)) {
                File tp = new File(TINYPLAY_BIN);
                String json = "{\"ok\":true,\"daemon\":\"MegaphoneDaemon\",\"port\":" + HTTP_PORT
                        + ",\"playing\":" + isPlaying.get()
                        + ",\"tinyplay\":" + (tp.exists() && tp.canExecute())
                        + ",\"cloud_sync\":\"" + VPS_SYNC_URL + "\""
                        + ",\"presets\":[\"alihkan\",\"salam\",\"tepi\",\"datang\"]}";
                sendCors(out, 200, "OK", "application/json", json);
            } else if ("/api/megaphone/play".equals(path) || "/api/megafon/play".equals(path) || "/api/megafon/speak".equals(path)) {
                handleMegaphonePlay(out, query, bodyBytes);
            } else if ("/api/megaphone/upload".equals(path) || "/api/megafon/upload".equals(path)) {
                handleMegaphoneUpload(out, bodyBytes);
            } else if ("/api/doa/play".equals(path) || "/api/doa".equals(path)) {
                handleDoaPlay(out);
            } else {
                sendCors(out, 404, "Not Found", "application/json", "{\"ok\":false,\"error\":\"NOT_FOUND\"}");
            }

            out.flush();
            s.close();
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private void handleMegaphonePlay(OutputStream out, String query, byte[] bodyBytes) throws IOException {
        String preset = extractParam(query, "preset");
        String customPath = extractParam(query, "path");

        if (preset == null && bodyBytes != null && bodyBytes.length > 0) {
            String bodyStr = new String(bodyBytes, "UTF-8");
            preset = extractJsonString(bodyStr, "preset");
            if (customPath == null) customPath = extractJsonString(bodyStr, "path");
        }

        String targetWav = null;
        if (customPath != null && !customPath.isEmpty()) {
            targetWav = customPath;
        } else if (preset != null && !preset.isEmpty()) {
            if (!preset.endsWith(".wav")) preset = preset + ".wav";
            targetWav = MEGAFON_DIR + "/" + preset;
        } else {
            targetWav = MEGAFON_DIR + "/current.wav";
        }

        File f = new File(targetWav);
        if (!f.exists() || f.length() < 44) {
            sendCors(out, 404, "Not Found", "application/json",
                    "{\"ok\":false,\"error\":\"FILE_NOT_FOUND\",\"path\":\"" + targetWav + "\"}");
            return;
        }

        if (!isPlaying.compareAndSet(false, true)) {
            sendCors(out, 429, "Too Many Requests", "application/json",
                    "{\"ok\":false,\"error\":\"BUSY\",\"message\":\"Audio currently playing\"}");
            return;
        }

        final String playPath = targetWav;
        new Thread(() -> {
            try {
                playExternalAudio(playPath);
            } finally {
                isPlaying.set(false);
            }
        }).start();

        sendCors(out, 200, "OK", "application/json",
                "{\"ok\":true,\"message\":\"Megaphone broadcast started\",\"file\":\"" + f.getName() + "\"}");
    }

    private void handleMegaphoneUpload(OutputStream out, byte[] bodyBytes) throws IOException {
        if (bodyBytes == null || bodyBytes.length < 44) {
            sendCors(out, 400, "Bad Request", "application/json",
                    "{\"ok\":false,\"error\":\"INVALID_AUDIO_DATA\"}");
            return;
        }

        File currentWav = new File(MEGAFON_DIR, "current.wav");
        try (FileOutputStream fos = new FileOutputStream(currentWav)) {
            fos.write(bodyBytes);
        }

        if (!isPlaying.compareAndSet(false, true)) {
            sendCors(out, 429, "Too Many Requests", "application/json",
                    "{\"ok\":false,\"error\":\"BUSY\",\"message\":\"Audio currently playing\"}");
            return;
        }

        new Thread(() -> {
            try {
                playExternalAudio(currentWav.getAbsolutePath());
            } finally {
                isPlaying.set(false);
            }
        }).start();

        sendCors(out, 200, "OK", "application/json",
                "{\"ok\":true,\"message\":\"Uploaded audio broadcasted successfully\",\"bytes\":" + bodyBytes.length + "}");
    }

    private void handleDoaPlay(OutputStream out) throws IOException {
        File doaFile = new File(DOA_PATH);
        if (!doaFile.exists() || doaFile.length() < 44) {
            sendCors(out, 404, "Not Found", "application/json",
                    "{\"ok\":false,\"error\":\"DOA_FILE_NOT_FOUND\",\"path\":\"" + DOA_PATH + "\"}");
            return;
        }

        new Thread(() -> {
            try {
                System.out.println("[MegaphoneDaemon] Playing Doa Naik Kenderaan in cabin...");
                playCabinAudio(doaFile);
            } catch (Throwable t) {
                System.err.println("[MegaphoneDaemon] Error playing Doa: " + t.getMessage());
            }
        }).start();

        sendCors(out, 200, "OK", "application/json",
                "{\"ok\":true,\"message\":\"Doa Naik Kenderaan playing in cabin\"}");
    }

    /**
     * Plays audio via external AVAS bumper speaker (/dev/snd/pcmC0D29p) using tinyplay.
     * Guaranteed fail-safe: always restarts avas_service in finally block!
     */
    private static void playExternalAudio(String wavPath) {
        File tp = new File(TINYPLAY_BIN);
        if (!tp.exists() || !tp.canExecute()) {
            System.out.println("[MegaphoneDaemon] tinyplay not available, falling back to cabin playback...");
            playCabinAudio(new File(wavPath));
            return;
        }

        boolean avasStopped = false;
        try {
            System.out.println("[MegaphoneDaemon] Stopping avas_service for megaphone playback...");
            Process stopProc = new ProcessBuilder("stop", "avas_service").start();
            stopProc.waitFor(2, TimeUnit.SECONDS);
            avasStopped = true;

            Thread.sleep(150);

            System.out.println("[MegaphoneDaemon] Playing: " + wavPath + " -> /dev/snd/pcmC0D29p");
            Process p = new ProcessBuilder(TINYPLAY_BIN, wavPath, "-D", "0", "-d", "29").start();
            boolean finished = p.waitFor(12, TimeUnit.SECONDS);
            if (!finished) {
                System.err.println("[MegaphoneDaemon] tinyplay timeout (12s), killing process...");
                p.destroyForcibly();
            } else {
                System.out.println("[MegaphoneDaemon] tinyplay finished (exit=" + p.exitValue() + ")");
            }
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon] External playback error: " + t.getMessage());
        } finally {
            if (avasStopped) {
                try {
                    System.out.println("[MegaphoneDaemon] Restarting avas_service...");
                    Process startProc = new ProcessBuilder("start", "avas_service").start();
                    startProc.waitFor(2, TimeUnit.SECONDS);
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Plays audio inside the cabin using Android AudioTrack with smooth micro-fade ramps.
     */
    private static void playCabinAudio(File f) {
        try {
            if (!f.exists()) return;
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
            int dataPos = -1;
            int dataLen = 0;

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

            if (dataPos == -1 || dataLen <= 0) return;
            if (sampleRate <= 0 || sampleRate > 96000) sampleRate = 48000;
            if (channels <= 0 || channels > 2) channels = 1;

            int channelConfig = (channels == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int audioFormat = (bitsPerSample == 8) ? AudioFormat.ENCODING_PCM_8BIT : AudioFormat.ENCODING_PCM_16BIT;

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
            int bytesPerFrame = channels * (bitsPerSample / 8);
            Thread.sleep(Math.max(100, (dataLen * 1000L) / (sampleRate * bytesPerFrame) + 50));
            track.stop();
            track.release();
            System.out.println("[MegaphoneDaemon] In-cabin AudioTrack playback finished (" + dataLen + " bytes)");
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon] Cabin playback error: " + t.getMessage());
        }
    }

    private static void sendCors(OutputStream out, int code, String msg, String mime, String body) throws IOException {
        byte[] b = body.getBytes("UTF-8");
        String header = "HTTP/1.1 " + code + " " + msg + "\r\n"
                + "Content-Type: " + mime + "; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type, X-API-Key, Range\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(b);
    }

    private static String extractParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        String search = key + "=";
        int idx = query.indexOf(search);
        if (idx == -1) return null;
        int start = idx + search.length();
        int end = query.indexOf('&', start);
        if (end == -1) end = query.length();
        return query.substring(start, end);
    }

    private static String extractJsonString(String json, String key) {
        if (json == null) return null;
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
}
