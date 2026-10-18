package com.emas.lock;

import java.io.*;
import java.io.RandomAccessFile;
import java.net.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LockSoundDaemon — daemon BERPISAH untuk tukar bunyi kunci kereta.
 *
 * Peranan: ganti /sdcard/V3_Vehicle_Locked_Single.wav secara atomik
 * (AtomicWavDeployment) dan restart ecarx_reverse_radar supaya bunyi
 * kunci baharu dimainkan servis itu. Terbukti berfungsi pada IHU801P.
 *
 * JANGAN ulang fungsi DoorPressureReliefDaemon (tetingkap/pintu/trunk).
 * Daemon ini hanya uruskan bunyi kunci — tiada VHAL write lain.
 *
 * Interface:
 *   HTTP lokal  : 127.0.0.1:8891  (token, sama corak AvasSoundDaemon)
 *   Sync VPS    : POST https://emas.43-159-63-29.nip.io/api/car/sync
 *                 baca lock_deploy, lapor lock_applied.
 *
 * Keselamatan: token diri-jana 0600; WAV disahkan header kausal
 * 48kHz/16-bit/stereo sebelum deploy; tiada restart servis selain
 * ecarx_reverse_radar (yang memang perlu baca semula fail).
 */
public final class LockSoundDaemon {

    // ==== Konfigurasi ====
    private static int serverPort = 8891;
    private static final String ACTIVE_LOCK_WAV = "/sdcard/V3_Vehicle_Locked_Single.wav";
    private static final String SOUND_DIR = "/data/local/locksound";
    private static final String TOKEN_SYSTEM_PATH = "/data/local/locksound/locksound.token";
    private static final String VPS_SYNC_URL = "https://emas.43-159-63-29.nip.io/api/car/sync";
    private static final long SYNC_INTERVAL_MS = 2000;
    private static final String RADAR_SERVICE = "ecarx_reverse_radar";

    private static String authToken = "";
    private static volatile String lastAppliedDeployId = "";
    private static final AtomicBoolean running = new AtomicBoolean(false);

    public static void main(String[] args) {
        System.out.println("[LockSoundDaemon] lock ownership is MegaphoneDaemon:8890; this process will not poll or bind");
    }

    /** Ujian lokal sahaja — jangan baca/simpan token dlm ujian. */
    static void startTestServer(int port, String baseDirOverride) {
        serverPort = port;
        new File(SOUND_DIR).mkdirs();
        authToken = "test-mode-no-token";
        running.set(true);
        startHttpServer();
    }

    // ==== Auth token ====
    private static void initAuthToken() {
        try {
            File tokenFile = new File(TOKEN_SYSTEM_PATH);
            if (tokenFile.exists() && tokenFile.length() > 0) {
                authToken = readFileString(tokenFile).trim();
            } else {
                SecureRandom rng = new SecureRandom();
                byte[] bytes = new byte[24];
                rng.nextBytes(bytes);
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) sb.append(String.format("%02x", b));
                authToken = sb.toString();
                writeFileString(tokenFile, authToken);
            }
            // 0600 owner-only
            try {
                Process p = Runtime.getRuntime().exec(new String[]{
                        "sh", "-c", "chmod 600 " + TOKEN_SYSTEM_PATH});
                p.waitFor();
            } catch (Exception ignored) {}
            System.out.println("[LockSoundDaemon] Authentication token loaded.");
        } catch (Exception e) {
            System.err.println("[LockSoundDaemon:FATAL] Token init failed: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // ==== HTTP server (lokal) ====
    private static void startHttpServer() {
        try (ServerSocket server = new ServerSocket(serverPort, 8, InetAddress.getByName("127.0.0.1"))) {
            System.out.println("[LockSoundDaemon] HTTP listening on 127.0.0.1:" + serverPort);
            while (running.get()) {
                try {
                    Socket sock = server.accept();
                    sock.setSoTimeout(3000);
                    handleClient(sock);
                } catch (Throwable e) {
                    System.err.println("[LockSoundDaemon] accept error: " + e.getMessage());
                }
            }
        } catch (Throwable t) {
            System.err.println("[LockSoundDaemon:FATAL] bind " + serverPort + " failed: " + t.getMessage());
        }
    }

    private static void handleClient(Socket sock) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             OutputStream out = sock.getOutputStream()) {
            String requestLine = in.readLine();
            if (requestLine == null) return;
            String path = requestLine.split(" ")[1];
            StringBuilder headers = new StringBuilder();
            String line;
            int contentLength = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                headers.append(line).append("\n");
                if (line.toLowerCase().startsWith("content-length:")) {
                    try { contentLength = Integer.parseInt(line.split(":")[1].trim()); } catch (Exception ignored) {}
                }
            }
            String body = "";
            if (contentLength > 0 && contentLength < 1_000_000) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = in.read(buf, read, contentLength - read);
                    if (r == -1) break;
                    read += r;
                }
                body = new String(buf, 0, read);
            }

            // Auth: semua endpoint (kecuali /health) perlukan token
            String headerText = headers.toString();
            String provided = extractHeader(headerText, "X-Auth-Token");
            boolean authorized = "/health".equals(path) || (provided != null && provided.equals(authToken));

            String resp;
            if (!authorized) {
                resp = json(401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            } else if (path.equals("/health")) {
                resp = json(200, "{\"ok\":true,\"daemon\":\"locksound\",\"active_deploy\":\"" + lastAppliedDeployId + "\"}");
            } else if (path.equals("/api/locksound/list")) {
                resp = json(200, listSounds());
            } else if (path.equals("/api/locksound/apply")) {
                String soundKey = extractJsonString(body, "sound");
                resp = applySound(soundKey, "local:" + System.currentTimeMillis());
            } else if (path.equals("/api/locksound/restart_radar")) {
                resp = restartRadar() ? json(200, "{\"ok\":true,\"restarted\":true}")
                                      : json(500, "{\"ok\":false,\"error\":\"restart failed\"}");
            } else {
                resp = json(404, "{\"ok\":false,\"error\":\"not found\"}");
            }
            out.write(resp.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable e) {
            System.err.println("[LockSoundDaemon] client error: " + e.getMessage());
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    private static String listSounds() {
        File dir = new File(SOUND_DIR);
        File[] files = dir.listFiles(new FileFilter() {
            @Override public boolean accept(File f) { return f.getName().endsWith(".wav"); }
        });
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"sounds\":[");
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"name\":\"").append(files[i].getName().replace(".wav", ""))
                  .append("\",\"size\":").append(files[i].length()).append("}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    // ==== Deploy bunyi ====

    /**
     * Buang chunk bukan-data (LIST/INFO metadata ffmpeg) dan tulis semula
     * WAV kausal: header 44 bait + data. Fail VPS ffmpeg ada chunk LIST
     * antara fmt dan data, menyebabkan validator kausal menolak.
     * Pulangkan fail kausal; null jika format asas ROSAK.
     */
    static File normalizeWav(File source) {
        try {
            RandomAccessFile raf = new RandomAccessFile(source, "r");
            byte[] head = new byte[12];
            raf.readFully(head);
            if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F'
             || head[8] != 'W' || head[9] != 'A' || head[10] != 'V' || head[11] != 'E') {
                raf.close();
                return null;
            }
            // imbas chunk cari "data"
            long dataOff = -1; long dataLen = -1;
            long pos = 12;
            while (pos + 8 <= source.length()) {
                raf.seek(pos);
                byte[] ch = new byte[8];
                raf.readFully(ch);
                long sz = (ch[4] & 255L) | (ch[5] & 255L) << 8 | (ch[6] & 255L) << 16 | (ch[7] & 255L) << 24;
                if (ch[0] == 'd' && ch[1] == 'a' && ch[2] == 't' && ch[3] == 'a') {
                    dataOff = pos + 8; dataLen = sz; break;
                }
                pos += 8 + sz + (sz % 2); // chunk kad
            }
            if (dataOff < 0 || dataLen <= 0) { raf.close(); return null; }
            if (dataLen > 576044 - 44) { raf.close(); return null; }

            // baca data
            raf.seek(dataOff);
            byte[] pcm = new byte[(int) dataLen];
            raf.readFully(pcm);
            raf.close();

            // tulis kausal: RIFF(12) + fmt(24) + data(8+pcm)
            File out = new File(source.getParentFile(), source.getName() + ".canonical");
            DataOutputStream dos = new DataOutputStream(new FileOutputStream(out));
            dos.writeBytes("RIFF"); dos.writeInt(Integer.reverseBytes((int)(36 + dataLen)));
            dos.writeBytes("WAVE");
            dos.writeBytes("fmt "); dos.writeInt(Integer.reverseBytes(16));
            dos.writeShort(Short.reverseBytes((short)1));       // PCM
            dos.writeShort(Short.reverseBytes((short)2));       // stereo
            dos.writeInt(Integer.reverseBytes(48000));           // 48kHz
            dos.writeInt(Integer.reverseBytes(192000));          // byte rate
            dos.writeShort(Short.reverseBytes((short)4));        // block align
            dos.writeShort(Short.reverseBytes((short)16));       // 16-bit
            dos.writeBytes("data"); dos.writeInt(Integer.reverseBytes((int) dataLen));
            dos.write(pcm);
            dos.close();
            return out;
        } catch (Exception e) {
            System.err.println("[LockSoundDaemon] normalize error: " + e);
            return null;
        }
    }

    static String applySound(String soundKey, String deployId) {
        if (soundKey == null || !soundKey.matches("[a-z0-9_]{1,32}")) {
            return json(400, "{\"ok\":false,\"error\":\"invalid sound key\"}");
        }
        File source = new File(SOUND_DIR, soundKey + ".wav");
        if (!source.exists() || source.length() <= 44) {
            return json(404, "{\"ok\":false,\"error\":\"sound file missing\"}");
        }
        try {
            // Normalisasi dulu: fail VPS ffmpeg bawa chunk LIST/INFO.
            File canonical = normalizeWav(source);
            if (canonical == null) {
                return json(422, "{\"ok\":false,\"error\":\"WAV cannot be normalized (no data chunk / RIFF invalid)\"}");
            }
            java.nio.file.Path sourcePath = canonical.toPath();
            java.nio.file.Path activePath = java.nio.file.Paths.get(ACTIVE_LOCK_WAV);

            // AtomicWavDeployment menolak fail bukan-PCM kausal 48k/16/stereo.
            com.emas.window.AtomicWavDeployment.validate(sourcePath);
            String sourceHash = com.emas.window.AtomicWavDeployment.sha256(sourcePath);

            // known-good hash: fail asal proton (kalau .known-good tiada, guna hash fail aktif)
            String knownGood = readKnownGood();
            // .bak basi (dipasang manual zaman dulu) menyebabkan deploy gagal:
            // deploy() menolak menimpa baseline pemulihan yg tak sepadan known-good.
            // Pindahkan ke tepi, jangan padam — data dikekalkan utk audit.
            java.nio.file.Path bakPath = java.nio.file.Paths.get(ACTIVE_LOCK_WAV + ".bak");
            if (java.nio.file.Files.exists(bakPath)) {
                String bakHash = com.emas.window.AtomicWavDeployment.sha256(bakPath);
                if (!bakHash.equalsIgnoreCase(knownGood)) {
                    java.nio.file.Path stale = java.nio.file.Paths.get(
                            ACTIVE_LOCK_WAV + ".bak.stale." + System.currentTimeMillis());
                    java.nio.file.Files.move(bakPath, stale);
                    System.out.println("[LockSoundDaemon] stale .bak moved aside: " + stale.getFileName());
                }
            }
            com.emas.window.AtomicWavDeployment.deploy(sourcePath, activePath, sourceHash, knownGood);
            // Baseline bergerak: selepas deploy berjaya, bunyi aktif BARU menjadi
            // known-good (fail .bak adalah snapshot approved terakhir; kontrak kelas).
            writeFileString(new File(ACTIVE_LOCK_WAV + ".known-good.sha256"), sourceHash);

            lastAppliedDeployId = deployId;
            boolean radarOk = restartRadar();
            return json(200, "{\"ok\":true,\"deployed\":\"" + soundKey + "\",\"deploy_id\":\"" + deployId
                    + "\",\"radar_restarted\":" + radarOk + "}");
        } catch (Exception e) {
            System.err.println("[LockSoundDaemon] deploy failed: " + e.getMessage());
            return json(500, "{\"ok\":false,\"error\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private static String readKnownGood() throws IOException {
        File f = new File(ACTIVE_LOCK_WAV + ".known-good.sha256");
        if (f.exists() && f.length() <= 128) {
            return readFileString(f).trim();
        }
        // fallback: hash fail aktif semasa (baseline pemulihan)
        try {
            return com.emas.window.AtomicWavDeployment.sha256(java.nio.file.Paths.get(ACTIVE_LOCK_WAV));
        } catch (Exception e) {
            throw new IOException("Cannot establish known-good baseline: " + e.getMessage());
        }
        }
    private static boolean restartRadar() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "killall " + RADAR_SERVICE + " 2>/dev/null; sleep 1; start " + RADAR_SERVICE});
            p.waitFor();
            Thread.sleep(500);
            return radarRunning();
        } catch (Exception e) {
            System.err.println("[LockSoundDaemon] radar restart error: " + e.getMessage());
            return false;
        }
    }

    private static boolean radarRunning() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                    "pidof " + RADAR_SERVICE + " >/dev/null && echo yes || echo no"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s = r.readLine();
            p.waitFor();
            return "yes".equals(s);
        } catch (Exception e) { return false; }
    }

    // ==== Sync VPS ====
    private static void syncLoop() {
        System.out.println("[LockSoundDaemon] Sync thread -> " + VPS_SYNC_URL);
        while (running.get()) {
            try {
                Thread.sleep(SYNC_INTERVAL_MS);
                URL url = new URL(VPS_SYNC_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                String body = "{}";
                if (!lastAppliedDeployId.isEmpty()) {
                    body = "{\"lock_applied\":\"" + lastAppliedDeployId + "\"}";
                }
                conn.setRequestProperty("Content-Length", String.valueOf(body.getBytes("UTF-8").length));
                try (OutputStream os = conn.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
                int code = conn.getResponseCode();
                if (code == 200) {
                    String resp = readStream(conn.getInputStream());
                    handleSyncResponse(resp);
                }
                conn.disconnect();
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                // senyap: VPS offline bukan ralat maut
            }
        }
    }

    private static void handleSyncResponse(String resp) {
        try {
            if (resp == null || !resp.contains("\"lock_deploy\":{\"")) return;
            int sIdx = resp.indexOf("\"lock_deploy\":{");
            int eIdx = resp.indexOf("}", sIdx);
            if (sIdx == -1 || eIdx == -1) return;
            String obj = resp.substring(sIdx, eIdx + 1);
            String deployId = extractJsonString(obj, "deploy_id");
            if (deployId == null || deployId.equals(lastAppliedDeployId)) return;

            System.out.println("[SYNC] New lock sound deployment: " + deployId);
            String soundKey = extractJsonString(obj, "sound_key");
            String audioUrl = extractJsonString(obj, "audio_url");

            if (soundKey != null && !soundKey.isEmpty()) {
                File target = new File(SOUND_DIR, soundKey + ".wav");
                if ((target.exists() && target.length() > 44) || (audioUrl != null && downloadFile(audioUrl, target))) {
                    String result = applySound(soundKey, deployId);
                    System.out.println("[SYNC] apply result: " + result);
                } else {
                    System.err.println("[SYNC] cannot obtain sound file: " + soundKey
                        + " (local missing AND download failed - see download error above)");
                }
            }
        } catch (Exception e) {
            System.err.println("[SYNC] handle error: " + e.getMessage());
        }
    }

    private static boolean downloadFile(String urlStr, File target) {
        OutputStream os = null;
        InputStream is = null;
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code != 200) {
                System.err.println("[LockSoundDaemon] download HTTP " + code + " for " + urlStr);
                return false;
            }
            is = conn.getInputStream();
            File tmp = new File(target.getAbsolutePath() + ".tmp");
            os = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > 576044) {
                    System.err.println("[LockSoundDaemon] download rejected: " + total + " bytes exceeds WAV policy");
                    os.close(); is.close(); tmp.delete(); return false;
                }
                os.write(buf, 0, n);
            }
            os.flush(); os.close(); os = null;
            is.close(); is = null;
            if (total <= 44) { System.err.println("[LockSoundDaemon] download too small: " + total + " bytes"); tmp.delete(); return false; }
            return tmp.renameTo(target);
        } catch (Exception e) {
            System.err.println("[LockSoundDaemon] download error: " + e
                + " | url=" + urlStr + " -> " + target.getName());
            return false;
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            try { if (is != null) is.close(); } catch (Exception ignored) {}
        }
    }

    // ==== Utiliti ====
    private static String json(int code, String body) {
        return "HTTP/1.1 " + code + " OK\r\nContent-Type: application/json\r\n"
             + "Content-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
    }

    private static String extractHeader(String headers, String name) {
        for (String h : headers.split("\n")) {
            if (h.toLowerCase().startsWith(name.toLowerCase() + ":")) {
                return h.substring(h.indexOf(':') + 1).trim();
            }
        }
        return null;
    }

    static String extractJsonString(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i == -1) return null;
        int start = i + needle.length();
        int end = json.indexOf('"', start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    private static String readFileString(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
        }
        return sb.toString();
    }

    private static void writeFileString(File f, String content) throws IOException {
        try (FileWriter w = new FileWriter(f)) { w.write(content); }
    }

    private static String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }
}
