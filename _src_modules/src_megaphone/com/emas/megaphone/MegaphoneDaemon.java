package com.emas.megaphone;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.json.JSONArray;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import com.emas.window.AtomicWavDeployment;

/**
 * Dedicated, Standalone Megaphone & Prayer (Doa) Daemon for Proton e.MAS 5 / IHU801P.
 * 
 * Port: 8890
 * Single Responsibility: 
 * 1. Exterior Bumper Speaker (tinyplay ALSA pcmC0D29p)
 * 2. In-Cabin Prayer (AudioTrack)
 * 3. Cloud VPS Sync Worker (polls https://emas.43-159-63-29.nip.io/api/car/sync for phone app broadcasts)
 * 4. Sole writer of the factory lock chime WAV (LockSoundDaemon 8891 must not run)
 *
 * Fully decoupled from Telemetry and AVAS tuning.
 */
public class MegaphoneDaemon {

    static final int HTTP_PORT = 8890;
    static final String VPS_SYNC_URL = "https://emas.43-159-63-29.nip.io/api/car/sync";
    static final String MEGAFON_DIR = "/data/local/window/megafon";
    static final String TINYPLAY_BIN = "/data/local/window/tinyplay";
    static final String DOA_PATH = "/data/local/window/locksounds/unlock_omar_hana.wav";
    static final String LOCKSOUNDS_DIR = "/data/local/window/locksounds";
    static final String LOCK_TARGET = "/sdcard/V3_Vehicle_Locked_Single.wav";
    static final String LOCK_ACTIVE = "/data/local/window/locksounds/lock_active.wav";

    private static final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private static final int AUDIO_QUEUE_CAPACITY = 5;
    private final LinkedBlockingQueue<AudioJob> audioQueue = new LinkedBlockingQueue<AudioJob>(AUDIO_QUEUE_CAPACITY);
    private static long audioSequence = 0;
    private final LinkedBlockingQueue<String> diagnosticEvents = new LinkedBlockingQueue<String>(80);
    private static volatile String lastPlayedMegaphoneId = "";
    private static volatile String lastAppliedLockDeployId = "";
    private static volatile String pendingLockAck = "";
    private static volatile String lastAppliedUnlockDeployId = "";
    private static volatile String pendingUnlockAck = "";
    private static final Object LOCK_INSTALL = new Object();
    private static final String LOCK_AUDIO_HOST = "https://emas.43-159-63-29.nip.io";
    private static final String RADAR_SERVICE = "ecarx_reverse_radar";
    private static volatile String lastLockFailId = "";
    private static volatile long lastLockFailAt = 0L;
    private final ThreadPoolExecutor httpWorkers = new ThreadPoolExecutor(
            3, 3, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8));

    private static final class AudioJob {
        final String path;
        final String source;
        AudioJob(String path, String source) { this.path = path; this.source = source; }
    }

    public static void main(String[] args) {
        System.out.println("=== EMAS MEGAPHONE & DOA DAEMON v1.1 (CLOUD SYNC ENABLED) ===");
        System.out.println("Port: " + HTTP_PORT);
        System.out.println("VPS Sync: " + VPS_SYNC_URL);
        System.out.println("Megafon Directory: " + MEGAFON_DIR);
        System.out.println("Tinyplay Binary: " + TINYPLAY_BIN);

        File dir = new File(MEGAFON_DIR);
        if (!dir.exists()) dir.mkdirs();
        prepareLockSounds();

        MegaphoneDaemon daemon = new MegaphoneDaemon();
        daemon.startAudioWorker();
        daemon.startCloudSync();
        daemon.startLockSoundRecovery();
        daemon.startHttpServer();
    }

    private static boolean lockServiceModeActive() {
        return Files.exists(Paths.get("/data/local/window/SERVICE_MODE_ACTIVE"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(Paths.get("/data/local/window/.service_mode"), LinkOption.NOFOLLOW_LINKS);
    }

    /** Require fresh stationary telemetry before boot recovery can reload the radar service. */
    private static boolean lockRecoveryParked(JSONObject telemetry, long now) {
        if (telemetry == null || !telemetry.optBoolean("ok", false)
                || !"telemetry_daemon".equals(telemetry.optString("source"))
                || !"P".equals(telemetry.optString("gear"))) return false;
        double speed = telemetry.optDouble("speed_kmh", Double.NaN);
        long age = telemetry.optLong("age_ms", -1);
        long observed = telemetry.optLong("observed_at_ms", -1);
        return !Double.isNaN(speed) && !Double.isInfinite(speed) && speed == 0.0
                && age >= 0 && age <= 1500 && observed > 0 && now >= observed && now - observed <= 2000;
    }

    private static String readLockCommand(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) return "";
            String line = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8")).readLine();
            return line == null ? "" : line.trim();
        } catch (Exception unavailable) { return ""; }
        finally { if (process != null) { if (process.isAlive()) process.destroyForcibly();
            try { process.getInputStream().close(); } catch (IOException ignored) {} } }
    }

    private static boolean lockBootReady() {
        return "1".equals(readLockCommand("/system/bin/getprop", "sys.boot_completed"));
    }

    private void startLockSoundRecovery() {
        Thread recovery = new Thread(() -> {
            LockSoundRecovery state = new LockSoundRecovery(Paths.get(LOCKSOUNDS_DIR), Paths.get(LOCK_TARGET),
                    Paths.get("/data/local/tmp/stock_lock.wav"), MegaphoneDaemon::readCanonicalLockBytes);
            String previous = "";
            // Bounded startup window; this is not a perpetual writer or a retry queue.
            for (int tick = 0; tick < 60; tick++) {
                try {
                    Thread.sleep(5000);
                    boolean ready = lockBootReady();
                    boolean parked = lockRecoveryParked(fetchLocalTelemetry(), System.currentTimeMillis());
                    String result;
                    synchronized (LOCK_INSTALL) {
                        result = state.tick(ready, lockServiceModeActive(), parked, preset -> {
                            if (lockServiceModeActive() || !lockRecoveryParked(fetchLocalTelemetry(), System.currentTimeMillis()))
                                return false;
                            return installLockWav(preset, "boot-recovery:" + tickId()).fileCommitted;
                        });
                    }
                    if (!result.equals(previous)) {
                        System.out.println("[LockSoundRecovery] " + result);
                        previous = result;
                    }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                catch (Exception error) {
                    String result = "RECOVERY_DEFERRED:" + error.getClass().getSimpleName();
                    if (!result.equals(previous)) System.err.println("[LockSoundRecovery] " + result);
                    previous = result;
                }
            }
            System.out.println("[LockSoundRecovery] startup window completed");
        }, "lock-sound-boot-recovery");
        recovery.setDaemon(true);
        recovery.start();
    }

    private static String tickId() { return java.util.UUID.randomUUID().toString(); }

    private void startAudioWorker() {
        Thread worker = new Thread(() -> {
            while (true) {
                try {
                    AudioJob job = audioQueue.take();
                    isPlaying.set(true);
                    System.out.println("[MegaphoneDaemon] QUEUE PLAY source=" + job.source + " path=" + job.path + " depth=" + audioQueue.size());
                    try {
                        boolean played = playExternalAudio(job.path);
                        addDiagnostic("EXTERNAL_PLAY " + (played ? "PLAYER_COMPLETED_NOT_ACOUSTICALLY_VERIFIED" : "REJECTED_OR_FAILED") + " source=" + job.source);
                    }
                    finally {
                        isPlaying.set(false);
                        File jf = new File(job.path);
                        if (jf.getName().startsWith(".queue_") || jf.getName().equals("current.wav") || jf.getName().startsWith("upload_")) {
                            jf.delete();
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[MegaphoneDaemon] Queue worker error: " + t.getMessage());
                    isPlaying.set(false);
                }
            }
        }, "megaphone-audio-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean enqueueAudio(String path, String source) {
        File input = new File(path);
        File snapshot;
        synchronized (MegaphoneDaemon.class) {
            snapshot = new File(MEGAFON_DIR, ".queue_" + (++audioSequence) + ".wav");
        }
        if (!input.exists() || !copyFile(input, snapshot) || !isValidWav(snapshot) || !repairWavEdges(snapshot)) {
            snapshot.delete();
            System.err.println("[MegaphoneDaemon] QUEUE REJECT_INVALID source=" + source + " path=" + path);
            return false;
        }
        boolean accepted = audioQueue.offer(new AudioJob(snapshot.getAbsolutePath(), source));
        if (!accepted) snapshot.delete();
        System.out.println("[MegaphoneDaemon] QUEUE " + (accepted ? "ACCEPT" : "REJECT_FULL")
                + " source=" + source + " depth=" + audioQueue.size());
        addDiagnostic("QUEUE " + (accepted ? "ACCEPT" : "REJECT_FULL") + " source=" + source + " depth=" + audioQueue.size());
        return accepted;
    }

    private void addDiagnostic(String event) {
        String line = System.currentTimeMillis() + " " + event;
        while (!diagnosticEvents.offer(line)) diagnosticEvents.poll();
    }

    private List<String> drainDiagnostics() {
        List<String> batch = new ArrayList<String>();
        diagnosticEvents.drainTo(batch, 40);
        return batch;
    }

    private void requeueDiagnostics(List<String> batch) {
        for (String line : batch) {
            while (!diagnosticEvents.offer(line)) diagnosticEvents.poll();
        }
    }

    private void startCloudSync() {
        Thread syncThread = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("[MegaphoneDaemon] Cloud Sync loop active (responsive polling) -> " + VPS_SYNC_URL);
                long sleepMs = 2500; // Fast 2.5s responsive default idle polling
                while (true) {
                    List<String> diagnosticBatch = new ArrayList<String>();
                    try {
                        Thread.sleep(sleepMs);
                        URL url = new URL(VPS_SYNC_URL);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(4000);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "application/json");

                        StringBuilder payload = new StringBuilder();
                        payload.append("{\"client\":\"MegaphoneDaemon\",\"playing\":").append(isPlaying.get());
                        diagnosticBatch = drainDiagnostics();
                        if (!diagnosticBatch.isEmpty()) {
                            payload.append(",\"diagnostic_events\":[");
                            for (int i = 0; i < diagnosticBatch.size(); i++) {
                                if (i > 0) payload.append(',');
                                payload.append(JSONObject.quote(diagnosticBatch.get(i)));
                            }
                            payload.append(']');
                        }
                        if (lastPlayedMegaphoneId != null && !lastPlayedMegaphoneId.isEmpty()) {
                            payload.append(",\"megaphone_ack\":\"").append(lastPlayedMegaphoneId).append("\"");
                        }
                        if (pendingLockAck != null && !pendingLockAck.isEmpty()) {
                            payload.append(",\"lock_applied\":\"").append(pendingLockAck).append("\"");
                        }
                        JSONObject lockReport = lockRemoteReport();
                        if (lockReport != null) payload.append(",\"lock_report\":").append(lockReport.toString());
                        if (pendingUnlockAck != null && !pendingUnlockAck.isEmpty()) {
                            payload.append(",\"unlock_applied\":\"").append(pendingUnlockAck).append("\"");
                        }
                        // Bridge: Include local TelemetryDaemon state (doors, trunk, battery) to VPS
                        JSONObject localTel = fetchLocalTelemetry();
                        if (localTel != null) {
                            if (localTel.has("doors")) {
                                payload.append(",\"doors\":").append(localTel.getJSONObject("doors").toString());
                            }
                            if (localTel.has("battery")) {
                                payload.append(",\"energy\":").append(localTel.getJSONObject("battery").toString());
                            }
                        }
                        payload.append("}");

                        conn.getOutputStream().write(payload.toString().getBytes("UTF-8"));

                        if (conn.getResponseCode() == 200) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) sb.append(line);
                            String resp = sb.toString();

                            try {
                                JSONObject root = new JSONObject(resp);

                                // Clear ACKs once VPS indicates pending deploy is cleared (null)
                                if (root.isNull("lock_deploy")) {
                                    pendingLockAck = "";
                                }
                                if (root.isNull("unlock_deploy")) {
                                    pendingUnlockAck = "";
                                }

                                // Adaptive polling interval: capped at max 3.0s idle, 1.2s when active
                                if (root.optBoolean("megaphone_switch", false)) {
                                    sleepMs = 1200;
                                } else if (root.has("poll_interval_sec")) {
                                    double sec = root.optDouble("poll_interval_sec", 2.5);
                                    sleepMs = Math.max(1000, Math.min(3000, (long)(sec * 1000)));
                                } else {
                                    sleepMs = 2500;
                                }

                                // Handle Megaphone audio broadcasts
                                if (root.has("megaphone") && !root.isNull("megaphone")) {
                                    JSONObject m = root.getJSONObject("megaphone");
                                    String msgId = m.optString("id");
                                    String audioUrl = m.optString("audio_url");
                                    if (!msgId.isEmpty() && !msgId.equals(lastPlayedMegaphoneId) && !audioUrl.isEmpty()) {
                                        System.out.println("[MegaphoneDaemon-SYNC] New Megaphone broadcast from phone/cloud: " + msgId);
                                        File targetAudio = new File(MEGAFON_DIR, "current.wav");
                                        downloadFileAtomic(audioUrl, targetAudio);
                                        if (isValidWav(targetAudio)) {
                                                                                    sleepMs = 1200; // Stay fast while active
                                                                                    if (enqueueAudio(targetAudio.getAbsolutePath(), "cloud:" + msgId)) {
                                                                                        lastPlayedMegaphoneId = msgId;
                                                                                        System.out.println("[MegaphoneDaemon-SYNC] Queued broadcast: " + msgId);
                                                                                    } else {
                                                                                        System.err.println("[MegaphoneDaemon-SYNC] Queue full; broadcast not acknowledged: " + msgId);
                                                                                    }
                                                                                }
                                    }
                                }

                                // Lock chime: this daemon is the only writer. No truncate-copy, no ACK before verify.
                                if (root.has("lock_selection") && !root.isNull("lock_selection")) {
                                    handleCloudLockDeploy(root.getJSONObject("lock_selection"));
                                } else if (root.has("lock_deploy") && !root.isNull("lock_deploy")) {
                                    handleCloudLockDeploy(root.getJSONObject("lock_deploy"));
                                }

                                // Handle Unlock Sound deployment from Phone/VPS
                                if (root.has("unlock_deploy") && !root.isNull("unlock_deploy")) {
                                    JSONObject ud = root.getJSONObject("unlock_deploy");
                                    String uDeployId = ud.optString("deploy_id");
                                    String uFileName = ud.optString("file");
                                    String uAudioUrl = ud.optString("audio_url");

                                    if (!uDeployId.isEmpty() && !uDeployId.equals(lastAppliedUnlockDeployId)) {
                                        File lDir = new File(LOCKSOUNDS_DIR);
                                        if (!lDir.exists()) lDir.mkdirs();

                                        File localPreset = new File(lDir, uFileName);
                                        if (!localPreset.exists() || localPreset.length() < 1000) {
                                            if (!uAudioUrl.isEmpty()) {
                                                downloadFile(uAudioUrl, localPreset);
                                            }
                                        }

                                        if (localPreset.exists() && localPreset.length() > 1000) {
                                            File activeUnlock = new File(lDir, "unlock_active.wav");
                                            copyFile(localPreset, activeUnlock);
                                            lastAppliedUnlockDeployId = uDeployId;
                                            pendingUnlockAck = uDeployId;
                                            System.out.println("[MegaphoneDaemon-SYNC] Applied unlock sound: " + uFileName + " (" + uDeployId + ")");
                                        }
                                    }
                                }

                                // Bridge: Handle Trunk / Tailgate motor command from Phone/VPS
                                if (root.has("trunk_cmd") && !root.isNull("trunk_cmd")) {
                                    String trunkCmd = root.optString("trunk_cmd").toLowerCase().trim();
                                    if ("open".equals(trunkCmd) || "close".equals(trunkCmd) || "toggle".equals(trunkCmd)) {
                                        System.out.println("[MegaphoneDaemon-SYNC] Forwarding trunk_cmd to TelemetryDaemon: " + trunkCmd);
                                        forwardTrunkCommand(trunkCmd);
                                    }
                                }

                                // Bridge: Handle Frunk / Front Hood release from Phone/VPS
                                if (root.has("frunk_cmd") && !root.isNull("frunk_cmd")) {
                                    String frunkCmd = root.optString("frunk_cmd").toLowerCase().trim();
                                    if ("unlock".equals(frunkCmd) || "open".equals(frunkCmd)) {
                                        System.out.println("[MegaphoneDaemon-SYNC] Forwarding frunk_cmd to TelemetryDaemon: " + frunkCmd);
                                        forwardFrunkCommand();
                                    }
                                }

                                // Bridge: Handle On-Demand App & Game Launching on IHU
                                if (root.has("launch_app_cmd") && !root.isNull("launch_app_cmd")) {
                                    String launchAppCmd = root.optString("launch_app_cmd").trim();
                                    if (!launchAppCmd.isEmpty()) {
                                        System.out.println("[MegaphoneDaemon-SYNC] Launching app on IHU: " + launchAppCmd);
                                        launchAppOnIhu(launchAppCmd);
                                    }
                                }
                            } catch (Throwable jsonErr) {
                                System.err.println("[MegaphoneDaemon-SYNC] JSON parse error: " + jsonErr.getMessage());
                            }
                        }
                    } catch (Throwable ignored) {
                        requeueDiagnostics(diagnosticBatch);
                        sleepMs = 5000; // Quick 5s retry on temporary network glitch
                    }
                }
            }
        });
        syncThread.setDaemon(true);
        syncThread.start();
    }

    private static boolean downloadFileAtomic(String fileUrl, File dest) {
        File tmp = new File(dest.getAbsolutePath() + ".downloading");
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
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                    fos.flush();
                    fos.getFD().sync();
                }
                if (!isValidWav(tmp) || !repairWavEdges(tmp) || !tmp.renameTo(dest)) {
                    tmp.delete();
                    System.err.println("[MegaphoneDaemon] Downloaded audio rejected or could not be committed: " + dest);
                    return false;
                }
                System.out.println("[MegaphoneDaemon] Downloaded audio -> " + dest.getAbsolutePath() + " (" + dest.length() + " bytes)");
                return true;
            }
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon] downloadFile error: " + t.getMessage());
        }
        tmp.delete();
        return false;
    }

    private static void downloadFile(String fileUrl, File dest) {
        downloadFileAtomic(fileUrl, dest);
    }

    private static boolean isValidWav(File f) {
        if (f == null || !f.exists() || f.length() < 44) return false;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[12];
            raf.readFully(h);
            return h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E';
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean repairWavEdges(File f) {
        try {
            byte[] b;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                if (raf.length() > 10 * 1024 * 1024) return false;
                b = new byte[(int) raf.length()];
                raf.readFully(b);
            }
            int fmt = findChunk(b, "fmt ", 12), data = findChunk(b, "data", 12);
            if (fmt < 0 || data < 0) return true;
            int channels = le16(b, fmt + 10), bits = le16(b, fmt + 22), rate = le32(b, fmt + 12);
            int dataPos = data + 8, dataLen = Math.min(le32(b, data + 4), b.length - dataPos);
            int frameBytes = channels * (bits / 8), frames = frameBytes > 0 ? dataLen / frameBytes : 0;
            if (channels < 1 || channels > 2 || bits != 16 || rate <= 0 || frames <= 0) return true;
            int first = -1, last = -1;
            for (int i = 0; i < frames; i++) {
                int peak = 0;
                for (int c = 0; c < channels; c++) { int p = dataPos + i * frameBytes + c * 2; int v = (short)((b[p]&255)|((b[p+1]&255)<<8)); peak = Math.max(peak, Math.abs(v)); }
                if (peak > 600) { if (first < 0) first = i; last = i; }
            }
            if (first < 0) return false;
            int start = Math.max(0, first - Math.min(first, rate * 10 / 1000));
            int end = Math.min(frames, last + 1 + Math.min(frames - first, rate * 40 / 1000));
            int newLen = (end - start) * frameBytes; byte[] out = Arrays.copyOf(b, dataPos + newLen);
            System.arraycopy(b, dataPos + start * frameBytes, out, dataPos, newLen);
            int fadeIn = Math.max(1, rate * 15 / 1000), fadeOut = Math.max(1, rate * 40 / 1000), outFrames = end - start;
            for (int i = 0; i < outFrames; i++) {
                double gain = 1.0; if (i < fadeIn) gain = i / (double)fadeIn; if (i >= outFrames - fadeOut) gain = Math.min(gain, (outFrames - 1 - i) / (double)fadeOut);
                for (int c = 0; c < channels; c++) { int p = dataPos + i * frameBytes + c * 2; int v = (short)((out[p]&255)|((out[p+1]&255)<<8)); int nv=(int)Math.round(v*Math.max(0,gain)); out[p]=(byte)nv; out[p+1]=(byte)(nv>>8); }
            }
            putLe32(out, data + 4, newLen); putLe32(out, 4, out.length - 8);
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) { raf.setLength(out.length); raf.seek(0); raf.write(out); raf.getFD().sync(); }
            System.out.println("[MegaphoneDaemon] WAV edge repair: " + b.length + " -> " + out.length + " bytes");
            return true;
        } catch (Throwable t) { System.err.println("[MegaphoneDaemon] WAV edge repair failed: " + t.getMessage()); return false; }
    }
    private static int findChunk(byte[] b, String name, int from) { for (int p=from; p+8<=b.length;) { String n=new String(b,p,4); int len=le32(b,p+4); if(name.equals(n)) return p; if(len<0 || p+8L+len>b.length) break; p+=8+len+(len&1); } return -1; }
    private static int le16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
    private static int le32(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
    private static void putLe32(byte[] b,int p,int v){b[p]=(byte)v;b[p+1]=(byte)(v>>8);b[p+2]=(byte)(v>>16);b[p+3]=(byte)(v>>24);}

    private static boolean copyFile(File src, File dst) {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                fos.write(buf, 0, r);
            }
            fos.flush();
            fos.getFD().sync();
            return true;
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon] copyFile error: " + t.getMessage());
            return false;
        }
    }

    private void startHttpServer() {
        try (ServerSocket server = new ServerSocket(HTTP_PORT, 10, InetAddress.getByName("127.0.0.1"))) {
            serveHttp(server);
        } catch (IOException t) {
            System.err.println("[MegaphoneDaemon] Server fatal error: " + t);
        }
    }

    // Allows an isolated laptop server on an ephemeral port; never starts Cloud/audio workers.
    private void serveHttp(ServerSocket server) throws IOException {
            System.out.println("[MegaphoneDaemon] HTTP listening on 127.0.0.1:" + HTTP_PORT);
            while (!server.isClosed()) {
                final Socket s = server.accept();
                try {
                    httpWorkers.execute(() -> handleClient(s));
                } catch (RejectedExecutionException busy) {
                    try {
                        sendCors(s.getOutputStream(), 503, "Service Unavailable", "application/json",
                                "{\"ok\":false,\"file_committed\":false,\"error\":\"DAEMON_BUSY\"}");
                    } catch (IOException ignored) {
                    } finally {
                        try { s.close(); } catch (IOException ignored) {}
                    }
                }
            }
    }

    private void handleClient(Socket s) {
        try {
            s.setSoTimeout(5000);
            final long requestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
            String reqLine = null;
            int contentLength = 0;
            String contentType = "";

            int prev = -1;
            int b;
            int headerBytes = 0;
            while ((b = in.read()) != -1) {
                if (++headerBytes > 8192 || System.nanoTime() > requestDeadline) {
                    sendCors(out, 431, "Request Header Fields Too Large", "application/json",
                            "{\"ok\":false,\"error\":\"INVALID_HTTP_HEADERS\"}");
                    return;
                }
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
            int bodyLimit = path.startsWith("/api/locksound/") ? 512 : 10 * 1024 * 1024;
            if (contentLength < 0 || contentLength > bodyLimit) {
                sendCors(out, 413, "Payload Too Large", "application/json",
                        "{\"ok\":false,\"file_committed\":false,\"error\":\"REQUEST_TOO_LARGE\"}");
                return;
            }
            if (contentLength > 0 && contentLength <= 10 * 1024 * 1024) { // max 10MB
                bodyBytes = new byte[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    if (System.nanoTime() > requestDeadline) throw new SocketTimeoutException("request deadline");
                    int r = in.read(bodyBytes, totalRead, contentLength - totalRead);
                    if (r == -1) throw new EOFException("Incomplete HTTP body");
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
                        + ",\"lock_apply\":\"/api/locksound/apply\""
                        + ",\"presets\":[\"alihkan\",\"salam\",\"tepi\",\"datang\"]}";
                sendCors(out, 200, "OK", "application/json", json);
            } else if ("/api/megaphone/play".equals(path) || "/api/megafon/play".equals(path) || "/api/megafon/speak".equals(path)) {
                handleMegaphonePlay(out, query, bodyBytes);
            } else if ("/api/megaphone/upload".equals(path) || "/api/megafon/upload".equals(path)) {
                handleMegaphoneUpload(out, bodyBytes);
            } else if ("/api/doa/play".equals(path) || "/api/doa".equals(path)) {
                handleDoaPlay(out);
            } else if ("/api/locksound/apply".equals(path) && "POST".equals(method)) {
                handleLockSoundApply(out, bodyBytes);
            } else if ("/api/locksound/apply".equals(path)) {
                sendCors(out, 405, "Method Not Allowed", "application/json",
                        "{\"ok\":false,\"error\":\"POST required\"}");
            } else if ("/api/locksound/list".equals(path) && "GET".equals(method)) {
                sendCors(out, 200, "OK", "application/json", listLockSounds(new File(LOCKSOUNDS_DIR)).toString());
            } else if ("/api/locksound/status".equals(path) && "GET".equals(method)) {
                sendCors(out, 200, "OK", "application/json", lockSoundStatus(new File(LOCKSOUNDS_DIR),
                        Paths.get(LOCK_TARGET), Paths.get(LOCK_ACTIVE)).toString());
            } else if ("/api/locksound/preview".equals(path) && "GET".equals(method)) {
                handleLockSoundPreview(out, extractParam(query, "sound"));
            } else {
                sendCors(out, 404, "Not Found", "application/json", "{\"ok\":false,\"error\":\"NOT_FOUND\"}");
            }

            out.flush();
            s.close();
        } catch (Throwable t) {
            try { s.close(); } catch (Throwable ignored) {}
        } finally {
            try { s.close(); } catch (IOException ignored) {}
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

        if (!enqueueAudio(targetWav, "http:" + f.getName())) {
            sendCors(out, 429, "Too Many Requests", "application/json",
                    "{\"ok\":false,\"error\":\"QUEUE_FULL\",\"message\":\"Audio queue is full\"}");
            return;
        }

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
        File uploadTmp = new File(MEGAFON_DIR, "current.wav.uploading");
        try (FileOutputStream fos = new FileOutputStream(uploadTmp)) {
        fos.write(bodyBytes);
        fos.flush();
        fos.getFD().sync();
        }
        if (!isValidWav(uploadTmp) || !repairWavEdges(uploadTmp) || !uploadTmp.renameTo(currentWav)) {
        uploadTmp.delete();
        sendCors(out, 400, "Bad Request", "application/json",
                "{\"ok\":false,\"error\":\"INVALID_OR_UNCOMMITTED_WAV\"}");
        return;
        }

        if (!enqueueAudio(currentWav.getAbsolutePath(), "upload:current.wav")) {
            sendCors(out, 429, "Too Many Requests", "application/json",
                    "{\"ok\":false,\"error\":\"QUEUE_FULL\",\"message\":\"Audio queue is full\"}");
            return;
        }

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
     * The independent init-supervised guard owns tinyplay and OEM service recovery.
     * Missing/unhealthy guard rejects external playback; cabin Doa has its own route.
     */
    private static boolean playExternalAudio(String wavPath) {
        try {
            if (AvasPlaybackClient.androidClient().play(Paths.get(wavPath))) return true;
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon] Guarded external playback error: " + t.getMessage());
            if (t instanceof InterruptedException) Thread.currentThread().interrupt();
        }
        System.out.println("[MegaphoneDaemon] Guarded playback not ready; falling back to direct tinyplay: " + wavPath);
        Process p = null;
        try {
            p = new ProcessBuilder("/data/local/window/tinyplay", wavPath, "-D", "0", "-d", "29")
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon] Direct tinyplay error: " + t.getMessage());
            return false;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
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

    private static JSONObject fetchLocalTelemetry() {
        try {
            URL u = new URL("http://127.0.0.1:8888/api/energy");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(300);
            c.setReadTimeout(500);
            if (c.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                c.disconnect();
                return new JSONObject(sb.toString());
            }
            c.disconnect();
        } catch (Throwable ignored) {}
        return null;
    }

    private static void forwardTrunkCommand(String action) {
        try {
            URL u = new URL("http://127.0.0.1:8888/api/trunk/" + action);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            System.out.println("[MegaphoneDaemon-SYNC] Forward trunk '" + action + "' to TelemetryDaemon -> HTTP " + code);
            c.disconnect();
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon-SYNC] Failed to forward trunk command: " + t.getMessage());
        }
    }

    private static void forwardFrunkCommand() {
        try {
            URL u = new URL("http://127.0.0.1:8888/api/frunk/unlock");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            int code = c.getResponseCode();
            System.out.println("[MegaphoneDaemon-SYNC] Forward frunk unlock to TelemetryDaemon -> HTTP " + code);
            c.disconnect();
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon-SYNC] Failed to forward frunk command: " + t.getMessage());
        }
    }

    private static void launchAppOnIhu(String target) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String pkg = target;
                    if ("sudachi".equalsIgnoreCase(target) || "switch".equalsIgnoreCase(target) || "suyu".equalsIgnoreCase(target)) {
                        // Check if sudachi or suyu or yuzu is installed
                        pkg = "org.sudachi.sudachi_emu";
                    } else if ("yuzu".equalsIgnoreCase(target)) {
                        pkg = "org.yuzu.yuzu_emu";
                    } else if ("ppsspp".equalsIgnoreCase(target) || "psp".equalsIgnoreCase(target)) {
                        pkg = "org.ppsspp.ppsspp";
                    } else if ("smarttube".equalsIgnoreCase(target) || "youtube".equalsIgnoreCase(target)) {
                        pkg = "com.liskovsoft.videomanager";
                    } else if ("netflix".equalsIgnoreCase(target)) {
                        pkg = "com.netflix.mediaclient";
                    } else if ("energy".equalsIgnoreCase(target) || "emasenergy".equalsIgnoreCase(target)) {
                        pkg = "com.emas.energy";
                    }

                    System.out.println("[MegaphoneDaemon] Requesting Android to bring foreground: " + pkg);
                    Process p = Runtime.getRuntime().exec(new String[]{
                        "/system/bin/sh", "-c",
                        "if [ \"" + target + "\" = \"sudachi\" ] || [ \"" + target + "\" = \"switch\" ]; then " +
                        "  monkey -p org.sudachi.sudachi_emu -c android.intent.category.LAUNCHER 1 2>/dev/null || " +
                        "  monkey -p org.suyu.suyu_emu -c android.intent.category.LAUNCHER 1 2>/dev/null || " +
                        "  monkey -p org.yuzu.yuzu_emu -c android.intent.category.LAUNCHER 1 2>/dev/null || " +
                        "  am start $(pm dump org.sudachi.sudachi_emu 2>/dev/null | grep -A 1 'android.intent.action.MAIN:' | grep -m 1 '/' | awk '{print $NF}'); " +
                        "else " +
                        "  monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1 2>/dev/null || " +
                        "  am start $(pm dump " + pkg + " 2>/dev/null | grep -A 1 'android.intent.action.MAIN:' | grep -m 1 '/' | awk '{print $NF}'); " +
                        "fi"
                    });
                    p.waitFor();
                    System.out.println("[MegaphoneDaemon] Launch execution complete for: " + pkg);
                } catch (Throwable t) {
                    System.err.println("[MegaphoneDaemon] Launch error: " + t.getMessage());
                }
            }
        }, "app-launcher-thread").start();
    }

    // ==== Lock chime: single owner. repairWavEdges stays on the megaphone path only. ====

    /** Inspect the OEM target, not a remembered selection. Never writes or reloads radar. */
    private static JSONObject lockSoundStatus(File dir, Path target, Path mirror) throws IOException {
        synchronized (LOCK_INSTALL) {
            try {
                JSONObject reply = new JSONObject().put("ok", true).put("active_sound", JSONObject.NULL)
                        .put("desired_sound", JSONObject.NULL).put("target_sha256", JSONObject.NULL)
                        .put("sync_required", JSONObject.NULL).put("playback_verified", false)
                        .put("observed_at_ms", System.currentTimeMillis());
                String activeHash = null, desiredHash = null;
                try {
                    readCanonicalLockBytes(target.toFile());
                    activeHash = AtomicWavDeployment.sha256(target);
                    reply.put("target_sha256", activeHash);
                } catch (IOException missingOrInvalid) { reply.put("warning", "ACTIVE_FILE_UNAVAILABLE"); }
                try {
                    readCanonicalLockBytes(mirror.toFile());
                    desiredHash = AtomicWavDeployment.sha256(mirror);
                } catch (IOException missingOrInvalid) { /* Unknown is different from synchronized. */ }
                JSONArray presets = listLockSounds(dir).getJSONArray("presets");
                int activeMatches = 0, desiredMatches = 0;
                String activeKey = null, desiredKey = null;
                for (int i = 0; i < presets.length(); i++) {
                    String key = presets.getJSONObject(i).getString("key");
                    // Compare canonical bytes: presets may contain WAV metadata removed on install.
                    byte[] bytes = readCanonicalLockBytes(new File(dir, "lock_" + key + ".wav"));
                    byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
                    StringBuilder hash = new StringBuilder();
                    for (byte value : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                    if (hash.toString().equals(activeHash)) { activeKey = key; activeMatches++; }
                    if (hash.toString().equals(desiredHash)) { desiredKey = key; desiredMatches++; }
                }
                // Identical named presets cannot be distinguished by their file content.
                if (activeMatches == 1) reply.put("active_sound", activeKey);
                if (desiredMatches == 1) reply.put("desired_sound", desiredKey);
                reply.put("active_match_count", activeMatches);
                if (desiredHash != null) reply.put("sync_required", !desiredHash.equals(activeHash));
                return reply;
            } catch (Exception error) { throw new IOException("Lock status unavailable", error); }
        }
    }

    private static JSONObject listLockSounds(File dir) throws IOException {
        try {
            JSONArray presets = new JSONArray();
            File[] files = dir.listFiles();
            if (files == null) throw new IOException("Preset directory unavailable");
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File file : files) {
                String name = file.getName();
                if (!name.matches("lock_[a-z0-9_]{1,32}\\.wav") || name.equals("lock_active.wav")) continue;
                String key = name.substring(5, name.length() - 4);
                try {
                    byte[] wav = readCanonicalLockBytes(file);
                    presets.put(new JSONObject().put("key", key).put("label", key.replace('_', ' '))
                            .put("duration_ms", (wav.length - 44) * 1000L / 192000L));
                    if (presets.length() == 64) break;
                } catch (IOException invalidPreset) {
                    // Only offer presets that this installer can actually use.
                }
            }
            return new JSONObject().put("ok", true).put("presets", presets);
        } catch (org.json.JSONException impossible) { throw new IOException(impossible); }
    }

    private static void handleLockSoundPreview(OutputStream out, String key) throws IOException {
        if (key == null || !key.matches("[a-z0-9_]{1,32}") || key.equals("active")) {
            sendCors(out, 400, "Bad Request", "application/json", "{\"ok\":false,\"error\":\"INVALID_SOUND_KEY\"}");
            return;
        }
        byte[] wav;
        try { wav = readCanonicalLockBytes(new File(LOCKSOUNDS_DIR, "lock_" + key + ".wav")); }
        catch (IOException invalid) {
            sendCors(out, 404, "Not Found", "application/json", "{\"ok\":false,\"error\":\"PRESET_UNAVAILABLE\"}");
            return;
        }
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: " + wav.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes("UTF-8"));
        out.write(wav);
    }

    private static void prepareLockSounds() {
        File dir = new File(LOCKSOUNDS_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            System.err.println("[MegaphoneDaemon] preset dir missing: " + LOCKSOUNDS_DIR);
        }
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", 8891), 200);
            System.err.println("[MegaphoneDaemon] WARNING port 8891 is open; LockSoundDaemon must not run");
        } catch (Exception ignored) {
            // Closed port is the required state.
        }
    }

    private void handleCloudLockDeploy(JSONObject ld) {
        // Server retains offline choices. Stealth mode defers the entire file transaction.
        if (lockServiceModeActive() || !lockRecoveryParked(fetchLocalTelemetry(), System.currentTimeMillis())) return;
        String deployId = sanitizeDeployId(ld.optString("deploy_id", ""));
        String soundKey = normalizeSoundKey(ld.optString("sound_key", ""));
        String fileName = ld.optString("file", "").trim();
        String audioUrl = ld.optString("audio_url", "").trim();
        String sha = ld.optString("sha256", "").trim().toLowerCase();
        long revision = ld.optLong("revision", 0);
        if (deployId.isEmpty() || soundKey == null) {
            System.err.println("[MegaphoneDaemon] lock_deploy rejected: id or sound key");
            return;
        }
        if (revision > 0) {
            try {
                java.util.Properties saved = LockSoundRecovery.saved(Paths.get(LOCKSOUNDS_DIR));
                long installedRevision = Long.parseLong(saved.getProperty("remote_revision", "0"));
                if (revision < installedRevision) return;
                if (deployId.equals(saved.getProperty("remote_deploy_id"))
                        && revision == installedRevision && sha.equals(saved.getProperty("remote_source_sha256"))
                        && saved.getProperty("sha256", "").equals(AtomicWavDeployment.sha256(Paths.get(LOCK_TARGET)))) {
                    lastAppliedLockDeployId = deployId;
                    pendingLockAck = deployId;
                    return;
                }
            } catch (Exception unavailable) { /* Actual installer validates all state before acknowledging. */ }
        } else if (deployId.equals(lastAppliedLockDeployId)) return;
        if (deployId.equals(lastLockFailId) && System.currentTimeMillis() - lastLockFailAt < 60000L) return;

        String expectedFile = "lock_" + soundKey + ".wav";
        if (!fileName.isEmpty() && !expectedFile.equals(fileName)) {
            fail(deployId, "lock file name rejected");
            return;
        }
        if (!sha.isEmpty() && !sha.matches("[0-9a-f]{64}")) {
            fail(deployId, "sha256 rejected");
            return;
        }

        File presetDir = new File(LOCKSOUNDS_DIR);
        if (!presetDir.isDirectory() && !presetDir.mkdirs()) {
            fail(deployId, "preset dir missing");
            return;
        }
        File preset = new File(presetDir, expectedFile);
        boolean cacheHit = isLockCacheValid(preset, sha);
        if (!cacheHit) {
            String url = resolveLockUrl(audioUrl, soundKey);
            if (url == null) {
                fail(deployId, "audio url rejected");
                return;
            }
            if (!downloadLockWav(url, preset, sha)) {
                fail(deployId, lockWavError.get() == null ? "LOCK_DOWNLOAD_FAILED" : lockWavError.get());
                return;
            }
        }
        try {
            AtomicWavDeployment.validate(preset.toPath());
        } catch (Exception notCanonical) {
            if (!canonicalizePreset(preset)) {
                fail(deployId, lockWavError.get() == null ? "LOCK_PRESET_INVALID" : lockWavError.get());
                return;
            }
        }
        synchronized (LOCK_INSTALL) {
            if (lockServiceModeActive() || !lockRecoveryParked(fetchLocalTelemetry(), System.currentTimeMillis())) return;
            LockInstallResult result = installLockWav(preset, deployId);
            addDiagnostic("LOCK_RESULT " + result.toJson(soundKey, deployId));
            if (result.fileCommitted) {
                if (revision > 0) {
                    try {
                        LockSoundRecovery.rememberRemote(Paths.get(LOCKSOUNDS_DIR), soundKey, result.sha256,
                                deployId, revision, sha, result.radarRestarted);
                    } catch (IOException persistFailed) {
                        fail(deployId, "REMOTE_SELECTION_NOT_PERSISTED");
                        return;
                    }
                }
                // Legacy lock_applied means verified FILE commit, never verified native playback.
                lastAppliedLockDeployId = deployId;
                pendingLockAck = deployId;
                System.out.println("[MegaphoneDaemon] lock FILE acknowledged " + soundKey + " deploy=" + deployId);
            } else {
                fail(deployId, result.error);
            }
        }
    }

    /** Fresh file report lets the server distinguish its desired choice from actual IHU bytes. */
    private static JSONObject lockRemoteReport() {
        synchronized (LOCK_INSTALL) {
            try {
                java.util.Properties saved = LockSoundRecovery.saved(Paths.get(LOCKSOUNDS_DIR));
                String id = saved.getProperty("remote_deploy_id", "");
                if (id.isEmpty()) return null;
                String hash = AtomicWavDeployment.sha256(Paths.get(LOCK_TARGET));
                String canonical = saved.getProperty("sha256", "");
                boolean service = lockServiceModeActive();
                boolean reloaded = Boolean.parseBoolean(saved.getProperty("remote_radar_restarted", "false"));
                return new JSONObject().put("deploy_id", id)
                        .put("revision", Long.parseLong(saved.getProperty("remote_revision", "0")))
                        .put("source_sha256", saved.getProperty("remote_source_sha256", ""))
                        .put("canonical_sha256", canonical).put("target_sha256", hash)
                        .put("file_committed", !service && canonical.equals(hash))
                        .put("service_mode", service).put("radar_restarted", reloaded)
                        .put("reload_deferred", !reloaded).put("playback_verified", false);
            } catch (Exception unavailable) { return null; }
        }
    }

    private static void handleLockSoundApply(OutputStream out, byte[] bodyBytes) throws IOException {
        String key;
        try {
            JSONObject request = new JSONObject(bodyBytes == null ? "" : new String(bodyBytes, "UTF-8"));
            String raw = request.getString("sound");
            key = raw.matches("[a-z0-9_]{1,32}") ? raw : null;
        } catch (Exception invalidJson) {
            key = null;
        }
        if (key == null) {
            sendCors(out, 400, "Bad Request", "application/json",
                    "{\"ok\":false,\"file_committed\":false,\"error\":\"INVALID_SOUND_KEY_OR_JSON\"}");
            return;
        }
        String deployId = "local:" + key + ":" + java.util.UUID.randomUUID().toString();
        LockInstallResult result = installLockWav(new File(LOCKSOUNDS_DIR, "lock_" + key + ".wav"), deployId);
        int code = result.fileCommitted ? 200 : result.error.startsWith("INVALID_WAV")
                || result.error.equals("UNSUPPORTED_WAV_CHUNK") ? 422
                : result.error.startsWith("PRESET_MISSING") ? 404
                : result.error.equals("LOCK_INSTALL_BUSY") ? 409 : 500;
        String reason = code == 200 ? "OK" : code == 422 ? "Unprocessable Entity"
                : code == 404 ? "Not Found" : code == 409 ? "Conflict" : "Internal Server Error";
        sendCors(out, code, reason, "application/json", result.toJson(key, deployId));
    }

    /** Result describes verified file state; it never claims OEM playback succeeded. */
    private static final class LockInstallResult {
        final boolean fileCommitted;
        final boolean radarRestarted;
        final String error;
        final String sha256;
        final String warning;
        LockInstallResult(boolean committed, boolean radarRestarted, String error, String hash, String warning) {
            this.fileCommitted = committed;
            this.radarRestarted = radarRestarted;
            this.error = error;
            this.sha256 = hash;
            this.warning = warning;
        }
        LockInstallResult(boolean committed, String error, String hash, String warning) {
            this(committed, false, error, hash, warning);
        }
        String toJson(String sound, String deployId) {
            try {
                return new JSONObject().put("ok", fileCommitted).put("file_committed", fileCommitted)
                        .put("sound", sound).put("deploy_id", deployId)
                        .put("sha256", sha256).put("radar_restarted", radarRestarted)
                        .put("reload_deferred", !radarRestarted).put("playback_verified", false)
                        .put("error", error).put("warning", warning).toString();
            } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
    }
    // Test seam invokes real validation and real AtomicWavDeployment on laptop paths.
    private interface LockInstallEffects {
        boolean chmod(String mode, String path);
        void mirror(File canonical) throws IOException;
    }
    private static String appendWarning(String warnings, String code) {
        return warnings.isEmpty() ? code : warnings + ";" + code;
    }
    private static LockInstallResult installLockWav(File preset, String deployId) {
        return installLockWav(preset, deployId, Paths.get(LOCK_TARGET), null,
                new LockInstallEffects() {
                    public boolean chmod(String mode, String path) { return chmodPath(mode, path); }
                    public void mirror(File canonical) throws IOException {
                        mirrorLockActive(canonical);
                        String name = preset.getName();
                        if (!name.matches("lock_[a-z0-9_]{1,32}\\.wav")) throw new IOException("Invalid preset name");
                        LockSoundRecovery.remember(Paths.get(LOCKSOUNDS_DIR),
                                name.substring(5, name.length() - 4), canonical.toPath());
                    }
                });
    }
    private static LockInstallResult installLockWav(File preset, String deployId, Path active,
                                                   String approvedBaseline, LockInstallEffects effects) {
        synchronized (LOCK_INSTALL) {
            Path canonical = null;
            String sourceHash = "", warnings = "";
            boolean committed = false;
            try {
                active = active.toAbsolutePath().normalize();
                if (Files.isSymbolicLink(active) || !Files.isDirectory(active.getParent()))
                    throw new LockWavException("INVALID_LOCK_TARGET");
                if (preset == null || !Files.isRegularFile(preset.toPath(), LinkOption.NOFOLLOW_LINKS))
                    throw new LockWavException("PRESET_MISSING_OR_SYMLINK");
                canonical = Files.createTempFile(active.getParent(), ".lock_install_", ".tmp");
                writeCanonicalLockWavChecked(preset, canonical.toFile());
                sourceHash = AtomicWavDeployment.sha256(canonical);
                boolean same = false;
                if (Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        AtomicWavDeployment.validate(active);
                        same = sourceHash.equalsIgnoreCase(AtomicWavDeployment.sha256(active));
                    } catch (IOException ignored) { /* Not a valid identical active file. */ }
                }
                if (!same) {
                    String knownGood = approvedBaseline != null ? approvedBaseline : readKnownGoodFile();
                    if (knownGood == null || !knownGood.matches("[a-fA-F0-9]{64}"))
                        throw new LockWavException("KNOWN_GOOD_BASELINE_REQUIRED");
                    // Reuse the fixed approved backup; never rotate it or enroll active implicitly.
                    // The permission hint must apply to the actual staging inode, not the input copy.
                    final boolean[] stagingChmodFailed = {false};
                    AtomicWavDeployment.deploy(canonical, active, sourceHash, knownGood, p -> {}, p -> {
                        try {
                            if (!effects.chmod("644", p.toString())) stagingChmodFailed[0] = true;
                        } catch (RuntimeException unsupported) { stagingChmodFailed[0] = true; }
                    });
                    if (stagingChmodFailed[0])
                        warnings = appendWarning(warnings, "STAGING_CHMOD_UNSUPPORTED_OR_FAILED");
                }
                AtomicWavDeployment.validate(active);
                if (!sourceHash.equalsIgnoreCase(AtomicWavDeployment.sha256(active)))
                    throw new LockWavException("ACTIVE_HASH_VERIFICATION_FAILED");
                committed = true;
                try {
                    if (!effects.chmod("644", active.toString()))
                        warnings = appendWarning(warnings, "ACTIVE_CHMOD_UNSUPPORTED_OR_FAILED");
                } catch (RuntimeException e) {
                    warnings = appendWarning(warnings, "ACTIVE_CHMOD_UNSUPPORTED_OR_FAILED");
                }
                // Mirror is a convenience, not the native active file. Failure cannot undo commit.
                try { effects.mirror(canonical.toFile()); }
                catch (IOException | RuntimeException e) { warnings = appendWarning(warnings, "ACTIVE_MIRROR_FAILED"); }
                boolean radarOk = !same && restartRadarBounded(deployId);
                if (!radarOk) {
                    warnings = appendWarning(warnings, "RADAR_RELOAD_DEFERRED_NO_VERIFIED_SAFETY_GATE");
                }
                lastLockFailId = "";
                System.out.println("[MegaphoneDaemon] LOCK_FILE_COMMITTED id=" + sanitizeDeployId(deployId)
                        + " hash=" + sourceHash + " radarRestarted=" + radarOk + " warning=" + warnings);
                return new LockInstallResult(true, radarOk, "", sourceHash, warnings);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String code = e instanceof LockWavException ? ((LockWavException)e).code
                        : e instanceof java.nio.file.AtomicMoveNotSupportedException ? "ATOMIC_MOVE_UNSUPPORTED"
                        : e instanceof java.nio.channels.OverlappingFileLockException ? "LOCK_INSTALL_BUSY"
                        : "LOCK_ATOMIC_DEPLOY_FAILED";
                System.err.println("[MegaphoneDaemon] lock apply id=" + sanitizeDeployId(deployId)
                        + " error=" + code + " type=" + e.getClass().getSimpleName());
                return new LockInstallResult(committed, committed ? "" : code, sourceHash, warnings);
            } finally {
                if (canonical != null) {
                    try { Files.deleteIfExists(canonical); }
                    catch (IOException cleanup) { System.err.println("[MegaphoneDaemon] lock staging cleanup failed"); }
                }
            }
        }
    }

    private static String readKnownGoodFile() throws IOException {
        File f = new File(LOCK_TARGET + ".known-good.sha256");
        if (!Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Known-good sidecar must be a regular non-symlink file");
        String text = readSmallText(f, 128);
        if (text == null || !text.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("known-good file is invalid");
        }
        return text.toLowerCase();
    }

    private static void mirrorLockActive(File canonical) throws IOException {
        File dir = new File(LOCKSOUNDS_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("preset dir missing");
        File tmp = new File(dir, ".lock_active.staging");
        File dest = new File(LOCK_ACTIVE);
        try (InputStream in = new FileInputStream(canonical);
             FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > AtomicWavDeployment.MAX_BYTES) throw new IOException("mirror exceeds WAV policy");
                fos.write(buf, 0, n);
            }
            fos.flush();
            fos.getFD().sync();
        }
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveFailed) {
            tmp.delete();
            throw moveFailed;
        }
    }

    private static boolean downloadLockWav(String fileUrl, File dest, String expectedSha) {
        lockWavError.remove();
        File parent = dest.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
        File tmp = null;
        File canon = null;
        HttpURLConnection conn = null;
        try {
            tmp = Files.createTempFile(parent.toPath(), ".lock_staging_", ".tmp").toFile();
            canon = Files.createTempFile(parent.toPath(), ".lock_canonical_", ".tmp").toFile();
            conn = (HttpURLConnection) new URL(fileUrl).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(20000);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            int code = conn.getResponseCode();
            if (code != 200) {
                System.err.println("[MegaphoneDaemon] lock download HTTP " + code);
                return false;
            }
            if (conn.getContentLengthLong() > AtomicWavDeployment.MAX_BYTES + 65536L) return false;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    if (System.nanoTime() > deadline) throw new SocketTimeoutException("lock download deadline");
                    total += n;
                    if (total > AtomicWavDeployment.MAX_BYTES + 65536) return false;
                    fos.write(buf, 0, n);
                }
                fos.flush();
                fos.getFD().sync();
            }
            String rawSha = AtomicWavDeployment.sha256Bounded(tmp.toPath(), AtomicWavDeployment.MAX_BYTES + 65536L);
            if (expectedSha != null && !expectedSha.isEmpty() && !expectedSha.equals(rawSha)) {
                lockWavError.set("LOCK_SHA256_MISMATCH");
                return false;
            }
            if (!writeCanonicalLockWav(tmp, canon)) return false;
            AtomicWavDeployment.validate(canon.toPath());
            Files.move(canon.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // The marker is a cache hint. Its canonical hash prevents a torn pair being reused.
            if (!writeLockCache(dest, rawSha, AtomicWavDeployment.sha256(dest.toPath())))
                System.err.println("[MegaphoneDaemon] cache marker unavailable; next deploy downloads again");
            return true;
        } catch (Exception e) {
            System.err.println("[MegaphoneDaemon] lock download failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
            if (tmp != null) tmp.delete();
            if (canon != null) canon.delete();
        }
    }

    private static boolean isLockCacheValid(File preset, String expectedRawSha) {
        if (expectedRawSha == null || !expectedRawSha.matches("[0-9a-f]{64}")) return false;
        try {
            AtomicWavDeployment.validate(preset.toPath());
            File marker = new File(preset.getParentFile(), preset.getName() + ".cache.json");
            if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
            String text = readSmallText(marker, 512);
            if (text == null) return false;
            JSONObject metadata = new JSONObject(text);
            return expectedRawSha.equals(metadata.optString("source_sha256", ""))
                    && AtomicWavDeployment.sha256(preset.toPath()).equals(metadata.optString("canonical_sha256", ""));
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean writeLockCache(File preset, String rawSha, String canonicalSha) {
        Path tmp = null;
        try {
            Path side = new File(preset.getParentFile(), preset.getName() + ".cache.json").toPath();
            tmp = Files.createTempFile(side.getParent(), ".lock_cache_", ".tmp");
            byte[] data = new JSONObject().put("source_sha256", rawSha)
                    .put("canonical_sha256", canonicalSha).toString().getBytes("UTF-8");
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                fos.write(data); fos.getFD().sync();
            }
            Files.move(tmp, side, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private static boolean canonicalizePreset(File preset) {
        File canon = new File(preset.getParentFile(), ".lock_preset_" + Thread.currentThread().getId() + ".tmp");
        try {
            if (!writeCanonicalLockWav(preset, canon)) return false;
            AtomicWavDeployment.validate(canon.toPath());
            Files.move(canon.toPath(), preset.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            System.err.println("[MegaphoneDaemon] preset normalize failed: " + e.getMessage());
            return false;
        } finally {
            canon.delete();
        }
    }

    /** Canonical PCM validation without conversion, gain changes or edge repair. */
    private static final ThreadLocal<String> lockWavError = new ThreadLocal<String>();
    private static final class LockWavException extends IOException {
        final String code;
        LockWavException(String code) { super(code); this.code = code; }
    }
    private static boolean writeCanonicalLockWav(File source, File out) {
        lockWavError.remove();
        try {
            writeCanonicalLockWavChecked(source, out);
            return true;
        } catch (LockWavException e) {
            lockWavError.set(e.code);
            System.err.println("[MegaphoneDaemon] lock WAV rejected: " + e.code);
        } catch (IOException | RuntimeException e) {
            lockWavError.set("LOCK_WAV_IO_ERROR");
            System.err.println("[MegaphoneDaemon] lock WAV IO failure: " + e.getClass().getSimpleName());
        }
        return false;
    }
    private static void writeCanonicalLockWavChecked(File source, File out) throws IOException {
        if (source == null || out == null) throw new LockWavException("PRESET_MISSING");
        Path src = source.toPath().toAbsolutePath().normalize();
        Path dst = out.toPath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS))
            throw new LockWavException("PRESET_MISSING_OR_SYMLINK");
        if (!out.getName().startsWith(".lock_") || dst.equals(Paths.get(LOCK_TARGET).toAbsolutePath().normalize())
                || dst.equals(src) || Files.isSymbolicLink(dst)
                || (Files.exists(dst, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(src, dst)))
            throw new LockWavException("INVALID_STAGING_PATH");
        byte[] canonical = readCanonicalLockBytes(source);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(canonical);
            fos.getFD().sync();
        }
        AtomicWavDeployment.validate(dst);
    }

    /** In-memory snapshot for listing, preview and install; never rewrites the source. */
    private static byte[] readCanonicalLockBytes(File source) throws IOException {
        if (source == null || !Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new LockWavException("PRESET_MISSING_OR_SYMLINK");
        // Allow bounded metadata overhead; output PCM remains capped at three seconds.
        byte[] file;
        try (RandomAccessFile raf = new RandomAccessFile(source, "r")) {
            long len = raf.length();
            if (len < 48 || len > AtomicWavDeployment.MAX_BYTES + 65536L)
                throw new LockWavException("INVALID_WAV_SIZE");
            file = new byte[(int) len];
            raf.readFully(file);
            if (raf.length() != len) throw new LockWavException("WAV_SOURCE_CHANGED");
        }
        if (le32(file, 0) != 0x46464952 || le32(file, 8) != 0x45564157
                || Integer.toUnsignedLong(le32(file, 4)) != file.length - 8L)
            throw new LockWavException("INVALID_WAV_RIFF_LENGTH_OR_HEADER");
        boolean fmtSeen = false, dataSeen = false;
        int dataPos = -1, dataLen = 0, pos = 12;
        while (pos < file.length) {
            if (file.length - pos < 8) throw new LockWavException("INVALID_WAV_CHUNK_BOUNDARY");
            long n = Integer.toUnsignedLong(le32(file, pos + 4));
            long next = pos + 8L + n + (n & 1L);
            if (next > file.length) throw new LockWavException("INVALID_WAV_TRUNCATED_CHUNK");
            int tag = le32(file, pos);
            if (tag == 0x20746d66) {
                if (fmtSeen || dataSeen || (n != 16 && n != 18))
                    throw new LockWavException("INVALID_WAV_FMT_CHUNK");
                fmtSeen = true;
                if (le16(file, pos + 8) != 1 || le16(file, pos + 10) != 2
                        || le32(file, pos + 12) != 48000 || le16(file, pos + 22) != 16)
                    throw new LockWavException("INVALID_WAV_FORMAT_NEEDS_48KHZ_STEREO_PCM16");
                if (le32(file, pos + 16) != 192000 || le16(file, pos + 20) != 4
                        || (n == 18 && le16(file, pos + 24) != 0))
                    throw new LockWavException("INVALID_WAV_BYTE_RATE_OR_ALIGNMENT");
            } else if (tag == 0x61746164) {
                if (!fmtSeen || dataSeen) throw new LockWavException("INVALID_WAV_DATA_CHUNK");
                dataSeen = true;
                if (n < 4 || (n & 3L) != 0 || n > AtomicWavDeployment.MAX_BYTES - 44L)
                    throw new LockWavException("INVALID_WAV_DATA_SIZE");
                dataPos = pos + 8;
                dataLen = (int) n;
            } else if (tag != 0x5453494c && tag != 0x4b4e554a && tag != 0x4f464e49) {
                throw new LockWavException("UNSUPPORTED_WAV_CHUNK");
            }
            pos = (int) next;
        }
        if (!fmtSeen || !dataSeen) throw new LockWavException("INVALID_WAV_MISSING_FMT_OR_DATA");
        byte[] hdr = new byte[44];
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x46464952).putInt(36 + dataLen).putInt(0x45564157)
                .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 2)
                .putInt(48000).putInt(192000).putShort((short) 4).putShort((short) 16)
                .putInt(0x61746164).putInt(dataLen);
        byte[] canonical = new byte[44 + dataLen];
        System.arraycopy(hdr, 0, canonical, 0, 44);
        System.arraycopy(file, dataPos, canonical, 44, dataLen);
        return canonical;
    }

    private static void putLe16(byte[] b, int p, int v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >> 8);
    }

    /** Reload through init only while fresh telemetry confirms stationary Park. */
    private static boolean restartRadarBounded(String deployId) {
        Process p = null;
        try {
            String before = readLockCommand("/system/bin/pidof", RADAR_SERVICE);
            if (!before.matches("[0-9]+") || !"running".equals(readLockCommand("/system/bin/getprop", "init.svc." + RADAR_SERVICE))
                    || lockServiceModeActive() || !lockRecoveryParked(fetchLocalTelemetry(), System.currentTimeMillis()))
                return false;
            p = new ProcessBuilder("/system/bin/setprop", "ctl.restart", RADAR_SERVICE).start();
            if (!p.waitFor(1, TimeUnit.SECONDS) || p.exitValue() != 0) return false;
            for (int poll = 0; poll < 10; poll++) {
                Thread.sleep(200);
                String after = readLockCommand("/system/bin/pidof", RADAR_SERVICE);
                if (after.matches("[0-9]+") && !before.equals(after)
                        && "running".equals(readLockCommand("/system/bin/getprop", "init.svc." + RADAR_SERVICE))) {
                    System.out.println("[MegaphoneDaemon] RADAR_RELOADED via init id=" + sanitizeDeployId(deployId));
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            System.err.println("[MegaphoneDaemon] restartRadar error: " + t.getMessage());
            return false;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }
    /** Best-effort permission hint. Return value is NOT consumer readiness or anti-overwrite proof. */
    private static boolean chmodPath(String mode, String path) {
        if ((!"444".equals(mode) && !"644".equals(mode)) || path == null || path.isEmpty()
                || !new File(path).isAbsolute() || path.indexOf('\0') >= 0) return false;
        Process p = null;
        try {
            p = new ProcessBuilder("chmod", mode, path).redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
            if (!p.waitFor(1000, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                System.err.println("[MegaphoneDaemon] chmod hint timed out; NOT a transaction failure");
                return false;
            }
            boolean ok = p.exitValue() == 0;
            if (!ok) System.err.println("[MegaphoneDaemon] chmod hint unsupported/failed; NOT a transaction failure");
            return ok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            System.err.println("[MegaphoneDaemon] chmod hint unavailable; NOT a transaction failure");
            return false;
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    private static String resolveLockUrl(String audioUrl, String soundKey) {
        String path = "/static/locksounds/lock_" + soundKey + ".wav";
        String candidate = (audioUrl == null || audioUrl.isEmpty()) ? LOCK_AUDIO_HOST + path : audioUrl;
        if (candidate.startsWith("/")) candidate = LOCK_AUDIO_HOST + candidate;
        try {
            URL u = new URL(candidate);
            if (!"https".equalsIgnoreCase(u.getProtocol())) return null;
            if (!"emas.43-159-63-29.nip.io".equalsIgnoreCase(u.getHost())) return null;
            if (u.getPort() != -1 && u.getPort() != 443) return null;
            if (u.getUserInfo() != null) return null;
            String requestedPath = u.getPath();
            String versionPattern = "/static/locksounds/versions/[a-f0-9]{64}/lock_" + soundKey + "\\.wav";
            if (!path.equals(requestedPath) && !requestedPath.matches(versionPattern)) return null;
            String query = u.getQuery();
            if (query != null && !query.matches("t=[0-9]{1,16}")) return null;
            return query == null ? LOCK_AUDIO_HOST + requestedPath : LOCK_AUDIO_HOST + requestedPath + "?" + query;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeSoundKey(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.endsWith(".wav")) s = s.substring(0, s.length() - 4);
        if (!s.matches("[a-z0-9_]{1,32}")) return null;
        return s;
    }

    private static String sanitizeDeployId(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (!s.matches("[A-Za-z0-9_.:\\-]{1,80}")) return "";
        return s;
    }

    private static String fail(String deployId, String msg) {
        if (deployId != null && !deployId.isEmpty()) {
            lastLockFailId = deployId;
            lastLockFailAt = System.currentTimeMillis();
        }
        if (msg != null && !"backoff".equals(msg)) {
            System.err.println("[MegaphoneDaemon] lock install failed: " + msg);
        }
        return msg;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        int n = Math.min(s.length(), 180);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    private static String readSmallText(File f, int max) throws IOException {
        if (f == null || !f.isFile() || f.length() == 0 || f.length() > max) return null;
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return new String(buf, "UTF-8").trim();
    }
}
