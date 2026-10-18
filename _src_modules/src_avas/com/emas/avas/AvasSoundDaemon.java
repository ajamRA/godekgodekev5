package com.emas.avas;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Hardened AVAS Sound Daemon for Geely Xingyuan / Proton e.MAS 5 (IHU801P).
 *
 * Security & Reliability Hardening:
 * 1. Authentication: Mandatory X-AVAS-Token header on all endpoints.
 * 2. Shell Injection Defense: Zero string concatenation; direct argument arrays via ProcessBuilder.
 * 3. Mount Verification: Checks exit codes and verifies /proc/mounts.
 * 4. Path Traversal & Leak Defense: Canonical path validation on preview endpoint.
 * 5. Audio Staging: USB files copied to internal storage first.
 * 6. WAV Validation: Mandatory PCM WAV 48,000 Hz, 16-bit, Mono (1 Channel).
 * 7. DoS & Resource Protection: Bounded ThreadPoolExecutor (2 threads, queue 8), max 64KB JSON payload.
 * 8. Reject disabled modes, silent PCM and incomplete WAVs; acoustic compliance requires physical testing.
 * 9. Factory Restore: Validates existence of factory backup file before restoring.
 */
public final class AvasSoundDaemon {

    public static final int DEFAULT_PORT = 8889;
    public static final String AVAS_BASE_DIR = "/data/local/avas";
    public static final String TOKEN_SYSTEM_PATH = "/data/local/window/avas.token";
    public static final int PROTOCOL_VERSION = 2;
    public static final String AUDIO_REVISION = "20261014";
    public static final String SELECTION_REVISION = "20261015";
    public static final int MAX_BODY_BYTES = 65536; // 64 KB

    private static volatile boolean running = true;
    private static int serverPort = DEFAULT_PORT;
    private static String baseDir = AVAS_BASE_DIR;
    private static boolean isAndroid = false;
    private static volatile String authToken = "";

    // Bounded thread pool for HTTP requests
    private static final ThreadPoolExecutor HTTP_EXECUTOR = new ThreadPoolExecutor(
        2, 2, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8),
        new ThreadPoolExecutor.AbortPolicy()
    );

    // In-memory configuration lock
    private static final Object CONFIG_LOCK = new Object();
    private static final Object AUDIO_STAGING_LOCK = new Object();
    private static JSONObject configJson = null;
    private static ServerSocket serverSocket = null;

    public static void main(String[] args) {
        System.out.println("[AvasSoundDaemon] Initializing hardened sound daemon...");
        if (args.length > 0 && "--check-server".equals(args[0])) {
            System.exit(checkServer() ? 0 : 1);
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        isAndroid = System.getProperty("java.vendor", "").toLowerCase().contains("android")
            || new File("/system/bin/sh").exists();

        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                try { serverPort = Integer.parseInt(args[++i]); } catch (Exception ignored) {}
            } else if ("--base-dir".equals(args[i]) && i + 1 < args.length) {
                baseDir = args[++i];
            }
        }

        if (isAndroid && !AVAS_BASE_DIR.equals(baseDir)) throw new IllegalArgumentException("ANDROID_BASE_DIR_FIXED");
        System.out.println("[AvasSoundDaemon] OS=" + os + ", isAndroid=" + isAndroid
            + ", port=" + serverPort + ", baseDir=" + baseDir);

        initDirectoriesAndConfig();
        initAuthToken();
        if (args.length == 4 && "--operation".equals(args[0]) && "--request-file".equals(args[2])) {
            JSONObject result;
            try {
                if (!isAndroid) throw new IllegalStateException("ANDROID_REQUIRED");
                File request = new File(args[3]);
                if (!isPathUnderDirectory(request, new File(baseDir, "requests")) || request.length() > MAX_BODY_BYTES)
                    throw new IllegalArgumentException("INVALID_REQUEST_FILE");
                result = performMutation("/api/avas/" + args[1], "", readFileString(request));
            } catch (Throwable t) { result = error(t.getMessage(), 500); }
            System.out.println(result.toString());
            System.exit(result.optBoolean("ok", false) ? 0 : 1);
            return;
        }
        if (args.length > 0 && args[0].startsWith("--operation")) throw new IllegalArgumentException("INVALID_CLI_ARGUMENTS");
        startHttpServer();
    }

    public static void startTestServer(int port, String customBaseDir) {
        serverPort = port;
        baseDir = customBaseDir;
        isAndroid = false;
        initDirectoriesAndConfig();
        initAuthToken();
        startHttpServer();
    }

    public static String getAuthToken() {
        return authToken;
    }

    public static void stopDaemon() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Throwable ignored) {}
        }
        HTTP_EXECUTOR.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Token & Configuration Setup
    // -------------------------------------------------------------------------
    private static boolean secureTokenFile(File file) {
        if (file == null || !file.exists()) return false;
        try {
            boolean cleared = file.setReadable(false, false);
            cleared &= file.setWritable(false, false);
            cleared &= file.setExecutable(false, false);
            boolean rOk = file.setReadable(true, true);
            boolean wOk = file.setWritable(true, true);
            return cleared && rOk && wOk;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void initAuthToken() {
        try {
            File tokenFile = new File(isAndroid ? TOKEN_SYSTEM_PATH : baseDir + "/avas.token");

            if (tokenFile.exists() && tokenFile.length() > 0) {
                if (tokenFile.length() > 256) throw new SecurityException("INVALID_TOKEN_FILE");
                authToken = readFileString(tokenFile).trim();
                if (!validToken(authToken)) throw new SecurityException("INVALID_TOKEN_FILE: explicit token recovery required");
            } else {
                SecureRandom rng = new SecureRandom();
                byte[] bytes = new byte[24];
                rng.nextBytes(bytes);
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) sb.append(String.format("%02x", b));
                authToken = sb.toString();

                File parent = tokenFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                writeFileString(tokenFile, authToken);
            }
            // Always enforce owner-only permissions on token file, whether created or existing
            boolean permOk = secureTokenFile(tokenFile);
            if (!permOk) {
                if (isAndroid) {
                    System.err.println("[AvasSoundDaemon:FATAL] Cannot enforce owner-only permissions on token file: " + tokenFile.getAbsolutePath());
                    throw new SecurityException("FAIL_CLOSED: Insecure token file permissions: " + tokenFile.getAbsolutePath());
                } else {
                    System.err.println("[AvasSoundDaemon] Notice: Non-POSIX laptop environment; token permissions noted: " + tokenFile.getAbsolutePath());
                }
            }
            System.out.println("[AvasSoundDaemon] Authentication token loaded.");
        } catch (SecurityException se) {
            throw se;
        } catch (Throwable t) {
            if (isAndroid) {
                System.err.println("[AvasSoundDaemon:FATAL] Token initialization failed on Android: " + t);
                throw new RuntimeException("FAIL_CLOSED: Token initialization failed", t);
            }
            throw new RuntimeException("FAIL_CLOSED: Token initialization failed", t);
        }
    }

    private static void initDirectoriesAndConfig() {
        synchronized (CONFIG_LOCK) {
            try {
                File dir = new File(baseDir);
                if (!dir.exists()) dir.mkdirs();
                File pDir = new File(baseDir + "/presets");
                if (!pDir.exists()) pDir.mkdirs();
                File sDir = new File(baseDir + "/sounds");
                if (!sDir.exists()) sDir.mkdirs();
                File bDir = new File(baseDir + "/factory_backup");
                if (!bDir.exists()) bDir.mkdirs();

                File cfgFile = new File(baseDir + "/avas_config.json");
                if (cfgFile.exists()) {
                    String text = readFileString(cfgFile);
                    configJson = new JSONObject(text);
                } else {
                    configJson = createDefaultConfig();
                }
            } catch (Throwable t) {
                System.err.println("[AvasSoundDaemon] Error initializing config: " + t);
                throw new IllegalStateException("CONFIG_INITIALIZATION_FAILED", t);
            }
        }
    }

    private static JSONObject createDefaultConfig() {
        JSONObject root = new JSONObject();
        try {
            root.put("ok", true).put("active_slot", 3).put("switch", 1);
            JSONObject slots = new JSONObject();
            for (int slot = 1; slot <= 3; slot++) {
                JSONObject state = new JSONObject();
                state.put("name", "Slot " + slot).put("custom_name", "Factory audio")
                    .put("audio_file", vendorTrack(slot)).put("source", "unverified")
                    .put("mute_chime", false).put("volume", JSONObject.NULL).put("pitch", JSONObject.NULL);
                slots.put(String.valueOf(slot), state);
            }
            root.put("slots", slots);
        } catch (Exception e) { throw new IllegalStateException(e); }
        return root;
    }

    private static void saveConfigLocked() throws Exception {
        File target = new File(baseDir, "avas_config.json");
        File temp = new File(baseDir, "avas_config.json.tmp." + UUID.randomUUID());
        try {
            writeFileString(temp, configJson.toString(2));
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { temp.delete(); }
    }

    // -------------------------------------------------------------------------
    // HTTP Server Loop
    // -------------------------------------------------------------------------
    private static void startHttpServer() {
        ServerSocket server = null;
        try {
            server = new ServerSocket(serverPort, 50, InetAddress.getByName("127.0.0.1"));
            serverSocket = server;
            if (isAndroid) AvasRemoteBridge.startIfConfigured(new File(baseDir), serverPort);
            System.out.println("[AvasSoundDaemon] HTTP server listening on 127.0.0.1:" + serverPort);
            while (running) {
                try {
                    Socket sock = server.accept();
                    try {
                        HTTP_EXECUTOR.execute(() -> handleClient(sock));
                    } catch (Throwable rejected) {
                        sendResponse(sock, 503, "application/json", "{\"ok\":false,\"error\":\"SERVER_BUSY\"}".getBytes(StandardCharsets.UTF_8));
                        try { sock.close(); } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    if (!running) break;
                }
            }
        } catch (Throwable t) {
            System.err.println("[AvasSoundDaemon] Fatal server error: " + t);
        } finally {
            if (server != null) {
                try { server.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void handleClient(Socket sock) {
        try {
            sock.setSoTimeout(3000);
            InputStream is = sock.getInputStream();

            // Read request line and headers
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int b;
            int consecutiveLf = 0;
            while ((b = is.read()) != -1) {
                headerBuf.write(b);
                if (b == '\n') {
                    consecutiveLf++;
                    if (consecutiveLf == 2) break; // End of HTTP headers (\r\n\r\n or \n\n)
                } else if (b != '\r') {
                    consecutiveLf = 0;
                }
                if (headerBuf.size() > 8192) {
                    sendResponse(sock, 431, "application/json", "{\"ok\":false,\"error\":\"HEADERS_TOO_LARGE\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }

            String headerStr = new String(headerBuf.toByteArray(), StandardCharsets.US_ASCII);
            String[] lines = headerStr.split("\r?\n");
            if (lines.length == 0 || lines[0].isEmpty()) return;

            String[] requestParts = lines[0].split(" ");
            if (requestParts.length < 2) return;
            String method = requestParts[0].toUpperCase();
            String uri = requestParts[1];

            // Parse headers
            int contentLength = 0;
            String reqToken = null;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon != -1) {
                    String name = line.substring(0, colon).trim().toLowerCase();
                    String val = line.substring(colon + 1).trim();
                    if ("content-length".equals(name)) {
                        try { contentLength = Integer.parseInt(val); } catch (Exception ignored) {}
                    } else if ("x-avas-token".equals(name)) {
                        reqToken = val;
                    }
                }
            }

            // Check Content-Length limit
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                sendResponse(sock, 413, "application/json", "{\"ok\":false,\"error\":\"PAYLOAD_TOO_LARGE\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }

            // Read exact body bytes
            byte[] bodyBytes = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int r = is.read(bodyBytes, read, contentLength - read);
                if (r == -1) break;
                read += r;
            }
            if (read != contentLength) return;
            String body = new String(bodyBytes, 0, read, StandardCharsets.UTF_8);

            handleRequest(sock, method, uri, body, reqToken);
        } catch (Throwable ignored) {
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Request Dispatcher
    // -------------------------------------------------------------------------
    private static void handleRequest(Socket sock, String method, String uri, String body, String reqToken) throws Exception {
        String path = uri;
        String query = "";
        int qIdx = uri.indexOf('?');
        if (qIdx != -1) {
            path = uri.substring(0, qIdx);
            query = uri.substring(qIdx + 1);
        }

        // CORS Preflight
        if ("OPTIONS".equals(method)) {
            sendResponse(sock, 204, "application/json", new byte[0]);
            return;
        }

        // Never allow requests when token initialization is invalid or incomplete.
        if (!validToken(authToken) || reqToken == null || !MessageDigest.isEqual(
                authToken.getBytes(StandardCharsets.US_ASCII), reqToken.getBytes(StandardCharsets.US_ASCII))) {
            sendResponse(sock, 401, "application/json", "{\"ok\":false,\"error\":\"UNAUTHORIZED\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        // Audio Preview endpoint
        if ("GET".equals(method) && "/api/avas/preview".equals(path)) {
            handleAudioPreview(sock, query);
            return;
        }

        JSONObject resp = new JSONObject();
        int statusCode = 200;

        try {
            if ("GET".equals(method) && ("/api/avas/config".equals(path) || "/api/avas/status".equals(path))) {
                synchronized (CONFIG_LOCK) {
                    resp = new JSONObject(configJson.toString());
                    resp.put("ok", true);
                    resp.put("daemon", "avas_sound_daemon");
                    resp.put("port", serverPort);
                    resp.put("is_android", isAndroid);
                    resp.put("protocol_version", PROTOCOL_VERSION);
                    resp.put("audio_revision", AUDIO_REVISION);
                    resp.put("selection_revision", SELECTION_REVISION);
                    resp.put("playback_verified", false);
                    resp.put("persistent_mounts", false);
                    resp.put("runtime_verified", false);
                    resp.put("reapply_on_boot", configJson.optInt("persistence_version", 0) == 1);
                    resp.put("boot_hook_verified", false);
                    if (isAndroid) {
                        String[] observed = AvasProperties.observed(PROPERTY_STORE);
                        resp.put("active_slot", Integer.parseInt(observed[0]));
                        resp.put("switch", Integer.parseInt(observed[3]));
                    }
                }
            } else if ("GET".equals(method) && ("/api/avas/usb_files".equals(path) || "/api/avas/presets".equals(path))) {
                resp = handleListFiles();
            } else if ("POST".equals(method) && ("/api/avas".equals(path) || path.startsWith("/api/avas/"))) {
                resp = performMutation(path, query, body);
            } else {
                statusCode = 404;
                resp.put("ok", false);
                resp.put("error", "NOT_FOUND");
                resp.put("path", path);
            }
        } catch (IllegalArgumentException iae) {
            statusCode = 400;
            resp.put("ok", false);
            resp.put("error", iae.getMessage());
        } catch (Throwable t) {
            statusCode = 500;
            resp.put("ok", false);
            resp.put("error", t.getMessage() != null ? t.getMessage() : t.toString());
        }

        if (resp.has("http_status")) {
            statusCode = resp.getInt("http_status");
            resp.remove("http_status");
        }

        byte[] payload = resp.toString(2).getBytes(StandardCharsets.UTF_8);
        sendResponse(sock, statusCode, "application/json; charset=utf-8", payload);
    }

    // -------------------------------------------------------------------------
    // Route Handlers
    // -------------------------------------------------------------------------

    private static JSONObject handleListFiles() throws Exception {
        JSONObject res = new JSONObject();
        JSONArray arr = new JSONArray();

        // Presets in <baseDir>/presets
        File presetsDir = new File(baseDir + "/presets");
        if (presetsDir.exists() && presetsDir.isDirectory()) {
            File[] files = presetsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (isWavFile(f.getName())) {
                        JSONObject item = new JSONObject();
                        item.put("name", f.getName());
                        item.put("path", f.getAbsolutePath());
                        item.put("source", "preset");
                        item.put("size_kb", f.length() / 1024);
                        arr.put(item);
                    }
                }
            }
        }

        // USB storage mounts
        String[] usbMounts = new String[]{"/storage", "/mnt/media_rw"};
        for (String mnt : usbMounts) {
            File mDir = new File(mnt);
            if (mDir.exists() && mDir.isDirectory()) {
                scanDirForWav(mDir, arr, 2);
            }
        }

        res.put("ok", true);
        res.put("files", arr);
        return res;
    }

    private static void scanDirForWav(File dir, JSONArray arr, int depth) throws Exception {
        if (depth < 0 || dir == null || !dir.isDirectory()) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                scanDirForWav(f, arr, depth - 1);
            } else if (f.isFile() && isWavFile(f.getName())) {
                JSONObject item = new JSONObject();
                item.put("name", f.getName());
                item.put("path", f.getAbsolutePath());
                item.put("source", "usb");
                item.put("size_kb", f.length() / 1024);
                arr.put(item);
            }
        }
    }

    private static boolean isWavFile(String name) {
        return name.toLowerCase().endsWith(".wav");
    }

    private static boolean validToken(String token) { return token != null && token.matches("[0-9a-f]{48}"); }

    private static JSONObject error(String message, int status) {
        try { return new JSONObject().put("ok", false).put("error", message == null ? "OPERATION_FAILED" : message).put("http_status", status).put("selection_revision", SELECTION_REVISION).put("playback_verified", false); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static JSONObject performMutation(String path, String query, String body) throws Exception {
        if (!("/api/avas".equals(path) || "/api/avas/mode".equals(path) || "/api/avas/apply".equals(path)
            || "/api/avas/apply_usb".equals(path) || "/api/avas/save".equals(path) || "/api/avas/restore".equals(path)
            || "/api/avas/resume".equals(path) || "/api/avas/observe".equals(path)))
            return error("NOT_FOUND", 404);
        synchronized (AUDIO_STAGING_LOCK) {
            String boot = isAndroid ? readFileString(new File("/proc/sys/kernel/random/boot_id")).trim() : "laptop";
            if (isAndroid && !boot.matches("[0-9a-f-]{36}")) throw new IOException("BOOT_ID_INVALID");
            // Same atomic directory lock as the independent megaphone output guard.
            // A new boot has its own lock; no live lock is guessed stale or removed.
            File lock = new File(baseDir, "output." + boot + ".lock");
            if (!lock.mkdir()) return error("DAEMON_BUSY: audio output owned by another operation", 503);
            JSONObject previous = null;
            try {
                synchronized (CONFIG_LOCK) {
                    File configFile = new File(baseDir, "avas_config.json");
                    configJson = configFile.isFile() ? new JSONObject(readFileString(configFile)) : createDefaultConfig();
                    previous = new JSONObject(configJson.toString());
                }
                if (isAndroid && (!"0".equals(commandOutput("/system/bin/id", "-u").trim())
                        || !"IHU801P".equals(commandOutput("/system/bin/getprop", "ro.product.model").trim())
                        || Integer.parseInt(commandOutput("/system/bin/getprop", "ro.build.version.sdk").trim()) < 29))
                    return error("TARGET_PREFLIGHT_FAILED: root IHU801P API29+ required", 409);
                if ("/api/avas".equals(path) || "/api/avas/mode".equals(path)) return handleSetMode(query, body);
                if ("/api/avas/apply".equals(path) || "/api/avas/apply_usb".equals(path)) return handleApplySound(body);
                if ("/api/avas/save".equals(path)) return handleSaveTuning(body);
                if ("/api/avas/resume".equals(path)) return handleResume();
                if ("/api/avas/observe".equals(path)) return handleObserve(body);
                return handleRestore(body);
            } catch (Exception failure) {
                if (previous != null) synchronized (CONFIG_LOCK) { configJson = previous; }
                throw failure;
            } finally {
                if (!lock.delete()) System.err.println("[AvasSoundDaemon] Mutation lock could not be released; further writes blocked");
            }
        }
    }

    private static int slot(JSONObject request) throws Exception {
        int slot = request.getInt("slot");
        if (slot < 1 || slot > 3) throw new IllegalArgumentException("Slot must be 1, 2, or 3");
        return slot;
    }
    private static String trackName(int slot) { return slot == 1 ? "yinheyinfu.wav" : slot == 2 ? "space.wav" : "pixel.wav"; }
    private static String vendorTrack(int slot) { return "/vendor/etc/avas/sound_type_" + slot + "/" + trackName(slot); }
    private static JSONObject slotState(int slot) throws Exception { return configJson.getJSONObject("slots").getJSONObject(String.valueOf(slot)); }

    private static JSONObject result(int slot) throws Exception {
        return new JSONObject().put("ok", true).put("slot", slot).put("protocol_version", PROTOCOL_VERSION)
            .put("audio_revision", AUDIO_REVISION).put("selection_revision", SELECTION_REVISION)
            .put("file_committed", true).put("mount_verified", isAndroid).put("service_running", isAndroid)
            .put("playback_verified", false).put("persistent_mounts", false).put("simulated", !isAndroid)
            .put("reapply_on_boot", configJson.optInt("persistence_version", 0) == 1).put("boot_hook_verified", false);
    }

    private static File stageSound(File source, int slot) throws Exception {
        if (!validatePcmWav48k16BitMono(source)) throw new IllegalArgumentException("INVALID_WAV: complete, non-silent PCM16 mono 48kHz required");
        File dir = new File(baseDir, "sounds");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("STAGING_DIRECTORY_FAILED");
        File dest = new File(dir, "slot_" + slot + "_" + UUID.randomUUID() + ".wav");
        File temp = new File(dest.getPath() + ".tmp");
        try {
            String expected = sha256(source);
            copyFile(source, temp);
            if (!validatePcmWav48k16BitMono(temp) || !expected.equals(sha256(temp))) throw new IOException("STAGING_VERIFICATION_FAILED");
            Files.move(temp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return dest;
        } finally { temp.delete(); }
    }

    // Capture vendor tuning/chime only if they are not already overlaid. Unknown old overlays are never blessed as factory.
    private static File factoryPart(int slot, String name) throws Exception {
        File dir = new File(baseDir, "factory_state/slot_" + slot);
        File file = new File(dir, name);
        File digest = new File(dir, name + ".sha256");
        if (file.isFile() && file.length() > 0 && digest.isFile() && readFileString(digest).trim().equals(sha256(file))
            && (!name.endsWith(".wav") || validatePcmWav48k16BitMono(file))) return file;
        String target = "/vendor/etc/avas/sound_type_" + slot + "/" + name;
        if (mountDepth(target) != 0) throw new IllegalStateException("FACTORY_STATE_UNKNOWN: verified backup needed for " + name);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("FACTORY_BACKUP_FAILED");
        if (file.exists() || digest.exists()) throw new IllegalStateException("FACTORY_BACKUP_CONFLICT: preserved for manual recovery");
        File temp = new File(dir, name + ".tmp." + UUID.randomUUID());
        try {
            copyFile(new File(target), temp);
            if (temp.length() == 0 || (name.endsWith(".wav") && !validatePcmWav48k16BitMono(temp))) throw new IOException("INVALID_FACTORY_BACKUP");
            String hash = sha256(temp);
            if (!hash.equals(sha256(new File(target)))) throw new IOException("FACTORY_CHANGED_DURING_BACKUP");
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE);
            writeFileString(digest, hash);
            return file;
        } finally { temp.delete(); }
    }

    private static void transaction(List<AvasTransaction.Change> changes, AvasTransaction.Commit commit) throws Exception {
        transaction(changes, selection(), commit);
    }

    private static final AvasProperties.Store PROPERTY_STORE = new AvasProperties.Store() {
        public String read(String key) throws Exception { return commandOutput("/system/bin/getprop", key); }
        public boolean write(String key, String value) { return runSystemCommand("/system/bin/setprop", key, value); }
    };
    private static String[] selection() throws Exception {
        return isAndroid ? AvasProperties.observed(PROPERTY_STORE)
            : AvasProperties.selected(configJson.getInt("active_slot"), configJson.getInt("switch"));
    }
    private static void rememberSelection(String[] selected) throws Exception {
        configJson.put("active_slot", Integer.parseInt(selected[0])).put("switch", Integer.parseInt(selected[3]))
            .put("persistence_version", 1);
    }
    private static void transaction(List<AvasTransaction.Change> changes, String[] selected, AvasTransaction.Commit commit) throws Exception {
        if (!isAndroid) { commit.run(); return; }
        final String[] before = AvasProperties.read(PROPERTY_STORE);
        AvasTransaction.Backend coordinated = new AvasTransaction.Backend() {
            private int reloads;
            public int depth(String path) throws Exception { return ANDROID_BACKEND.depth(path); }
            public String hash(String path) throws Exception { return ANDROID_BACKEND.hash(path); }
            public boolean sameFile(String a,String b) throws Exception { return ANDROID_BACKEND.sameFile(a,b); }
            public boolean bind(String a,String b) throws Exception { return ANDROID_BACKEND.bind(a,b); }
            public boolean unbind(String path) throws Exception { return ANDROID_BACKEND.unbind(path); }
            public boolean context(String path) throws Exception { return ANDROID_BACKEND.context(path); }
            public boolean serviceRunning() throws Exception { return ANDROID_BACKEND.serviceRunning(); }
            public boolean baselineRecoverable(String path) throws Exception { return ANDROID_BACKEND.baselineRecoverable(path); }
            public boolean reload() throws Exception {
                AvasProperties.reloadPreserving(PROPERTY_STORE, reloads++ == 0 ? selected : before, ANDROID_BACKEND::reload);
                return true;
            }
        };
        // Repeated resume in the same boot must not stack mounts or restart the HAL.
        List<AvasTransaction.Change> pending = new ArrayList<>();
        for (AvasTransaction.Change change : changes) if (!ANDROID_BACKEND.sameFile(change.source, change.target)
            || !ANDROID_BACKEND.hash(change.source).equals(ANDROID_BACKEND.hash(change.target))) pending.add(change);
        if (pending.isEmpty() && AvasProperties.matches(PROPERTY_STORE, selected)) { commit.run(); return; }
        AvasTransaction.execute(coordinated, pending, commit);
    }

    private static JSONObject handleSetMode(String query, String body) throws Exception {
        int mode = -1;
        for (String param : query.split("&")) if (param.startsWith("mode=")) mode = Integer.parseInt(param.substring(5));
        if (mode == -1 && !body.isEmpty()) mode = new JSONObject(body).optInt("mode", -1);
        if (mode < 1 || mode > 3) throw new IllegalArgumentException("AVAS_DISABLED_MODE_PROHIBITED: slots 1..3 only");
        final int selected = mode;
        final int enabled = body.isEmpty() ? 1 : new JSONObject(body).optInt("switch", 1);
        final String[] desired = AvasProperties.selected(mode, enabled);
        String[] keys = AvasProperties.KEYS;
        String[] previous = new String[4];
        boolean propertiesChanged = false;
        try {
            if (isAndroid) {
                if (!ANDROID_BACKEND.serviceRunning()) throw new IllegalStateException("HAL_NOT_RUNNING");
                for (int i = 0; i < 4; i++) previous[i] = commandOutput("/system/bin/getprop", keys[i]).trim();
                if (!AvasProperties.matches(PROPERTY_STORE, desired)) {
                    propertiesChanged = true;
                    AvasProperties.reloadPreserving(PROPERTY_STORE, desired, AvasSoundDaemon::restartAvasHalService);
                }
            }
            synchronized (CONFIG_LOCK) { rememberSelection(desired); saveConfigLocked(); }
        } catch (Exception failure) {
            if (isAndroid && propertiesChanged) {
                boolean restored = true;
                try { AvasProperties.reloadPreserving(PROPERTY_STORE, previous, AvasSoundDaemon::restartAvasHalService); }
                catch (Exception rollback) { restored = false; }
                throw new IOException(failure.getMessage() + "; PROPERTY_ROLLBACK_" + (restored ? "VERIFIED" : "UNVERIFIED"), failure);
            }
            throw failure;
        }
        return result(mode).put("active_slot", mode).put("message", "AVAS mode selected; physical playback not verified");
    }

    private static JSONObject handleObserve(String body) throws Exception {
        String[] observed = selection();
        JSONObject request = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        if (request.has("expected_oem_mode") || request.has("expected_oem_switch")) {
            Object mode=request.opt("expected_oem_mode"), enabled=request.opt("expected_oem_switch");
            if (!(mode instanceof Integer) || !(enabled instanceof Integer)
                    || (Integer)mode<1 || (Integer)mode>3 || (Integer)enabled<0 || (Integer)enabled>1)
                return error("INVALID_OBSERVATION", 400);
            if (!observed[0].equals(mode.toString()) || !observed[1].equals(enabled.toString()))
                return error("AVAS_SELECTION_CHANGED", 409);
        }
        synchronized (CONFIG_LOCK) {
            if (!java.util.Arrays.equals(observed, selection())) return error("AVAS_SELECTION_CHANGED", 409);
            rememberSelection(observed); saveConfigLocked();
        }
        return new JSONObject().put("ok", true).put("selection_saved", true)
            .put("active_slot", Integer.parseInt(observed[0])).put("switch", Integer.parseInt(observed[3]))
            .put("selection_revision", SELECTION_REVISION).put("service_restarted", false);
    }

    private static JSONObject handleResume() throws Exception {
        AvasBootPlan plan = AvasBootPlan.load(configJson, new File(baseDir));
        if (plan.selection == null) return new JSONObject().put("ok", true).put("resumed", false)
            .put("selection_revision", SELECTION_REVISION).put("reason", "NO_SAVED_SELECTION");
        transaction(plan.changes, plan.selection, () -> {});
        return result(Integer.parseInt(plan.selection[0])).put("resumed", true).put("switch", Integer.parseInt(plan.selection[3]));
    }

    private static JSONObject handleApplySound(String body) throws Exception {
        JSONObject request = new JSONObject(body);
        final int slot = slot(request);
        File source = new File(request.getString("file_path"));
        if (!source.isFile()) return error("SOURCE_NOT_FOUND", 404);
        if (!(isPathUnderDirectory(source, new File(baseDir)) || isPathUnderDirectory(source, new File("/storage"))
                || isPathUnderDirectory(source, new File("/mnt/media_rw")))) return error("SOURCE_PATH_FORBIDDEN", 403);
        if (!validatePcmWav48k16BitMono(source)) return error("INVALID_WAV: complete, non-silent PCM16 mono 48kHz required", 422);
        final String name = request.optString("custom_name", source.getName());
        if (request.has("activate") && !(request.opt("activate") instanceof Boolean))
            throw new IllegalArgumentException("ACTIVATE_MUST_BE_BOOLEAN");
        final String[] selected = request.optBoolean("activate", false)
            ? AvasProperties.selected(slot, 1) : selection();
        if (isAndroid) { factoryPart(slot, "tuning.txt"); factoryPart(slot, "bDM_01_01.wav"); }
        final File staged = stageSound(source, slot);
        List<AvasTransaction.Change> changes = new ArrayList<>();
        changes.add(new AvasTransaction.Change(staged.getAbsolutePath(), vendorTrack(slot)));
        transaction(changes, selected, () -> {
            synchronized (CONFIG_LOCK) {
                slotState(slot).put("custom_name", name).put("audio_file", staged.getAbsolutePath()).put("source", "custom")
                    .put("audio_sha256", sha256(staged));
                rememberSelection(selected);
                saveConfigLocked();
            }
        });
        return result(slot).put("target", vendorTrack(slot)).put("staged_file", staged.getAbsolutePath())
            .put("custom_name", name).put("message", "Audio file installed; physical playback not verified");
    }

    private static JSONObject handleSaveTuning(String body) throws Exception {
        JSONObject request = new JSONObject(body);
        final int slot = slot(request);
        JSONArray volume = request.getJSONArray("volume"), pitch = request.optJSONArray("pitch");
        final String[] selected = selection();
        if (volume.length() != 7 || (pitch != null && pitch.length() != 7)) throw new IllegalArgumentException("Curve must have exactly 7 points");
        // Chime muting is deliberately unavailable until the reverse warning contract is verified on the IHU.
        if (request.optBoolean("mute_chime", false)) throw new IllegalArgumentException("CHIME_MUTE_UNVERIFIED: preserve warning audio");
        final int[] volumes = new int[7], pitches = new int[7];
        for (int i = 0; i < 7; i++) {
            volumes[i] = volume.getInt(i); pitches[i] = pitch == null ? 100 : pitch.getInt(i);
            if (volumes[i] < 0 || volumes[i] > 20 || pitches[i] < 80 || pitches[i] > 130) throw new IllegalArgumentException("CURVE_OUT_OF_RANGE");
        }
        byte[] encoded = AvasTuningEncoder.generateEncodedTuning(slot, volumes, pitches, false);
        if (isAndroid) { factoryPart(slot, "tuning.txt"); factoryPart(slot, "bDM_01_01.wav"); }
        final File tuning = new File(baseDir, "tuning_" + slot + "_" + UUID.randomUUID() + ".txt");
        File temp = new File(tuning.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(encoded); out.getFD().sync(); }
            Files.move(temp.toPath(), tuning.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } finally { temp.delete(); }
        List<AvasTransaction.Change> changes = new ArrayList<>();
        String vendor = "/vendor/etc/avas/sound_type_" + slot + "/";
        changes.add(new AvasTransaction.Change(tuning.getAbsolutePath(), vendor + "tuning.txt"));
        if (isAndroid) changes.add(new AvasTransaction.Change(factoryPart(slot, "bDM_01_01.wav").getAbsolutePath(), vendor + "bDM_01_01.wav"));
        transaction(changes, selected, () -> {
            synchronized (CONFIG_LOCK) {
                slotState(slot).put("volume", new JSONArray(volumes)).put("pitch", new JSONArray(pitches))
                    .put("mute_chime", false).put("tuning_file", tuning.getAbsolutePath()).put("tuning_sha256", sha256(tuning));
                rememberSelection(selected);
                saveConfigLocked();
            }
        });
        return result(slot).put("tuning_file", tuning.getAbsolutePath()).put("message", "Tuning installed; physical playback not verified");
    }

    private static JSONObject handleRestore(String body) throws Exception {
        JSONObject request = new JSONObject(body);
        final int slot = slot(request);
        final String[] selected = selection();
        File backup = request.has("backup_file") ? new File(request.getString("backup_file")) : new File(baseDir, "factory_backup/" + trackName(slot));
        if (isAndroid && !isPathUnderDirectory(backup, new File(baseDir))) throw new IllegalArgumentException("BACKUP_PATH_FORBIDDEN");
        if (!backup.isFile()) return error("BACKUP_NOT_FOUND", 404);
        if (!validatePcmWav48k16BitMono(backup)) return error("BACKUP_CORRUPTED", 422);
        final File staged = stageSound(backup, slot);
        List<AvasTransaction.Change> changes = new ArrayList<>();
        changes.add(new AvasTransaction.Change(staged.getAbsolutePath(), vendorTrack(slot)));
        if (isAndroid) {
            String vendor = "/vendor/etc/avas/sound_type_" + slot + "/";
            changes.add(new AvasTransaction.Change(factoryPart(slot, "tuning.txt").getAbsolutePath(), vendor + "tuning.txt"));
            changes.add(new AvasTransaction.Change(factoryPart(slot, "bDM_01_01.wav").getAbsolutePath(), vendor + "bDM_01_01.wav"));
        }
        transaction(changes, selected, () -> {
            synchronized (CONFIG_LOCK) {
                JSONObject factory = createDefaultConfig().getJSONObject("slots").getJSONObject(String.valueOf(slot));
                factory.put("audio_file", staged.getAbsolutePath()).put("source", "factory_backup").put("custom_name", "Factory audio")
                    .put("tuning_state", "captured_vendor").put("mute_chime", false);
                configJson.getJSONObject("slots").put(String.valueOf(slot), factory);
                rememberSelection(selected);
                saveConfigLocked();
            }
        });
        return result(slot).put("message", "Factory backup and captured vendor tuning/chime installed; physical playback not verified");
    }

    private static void handleAudioPreview(Socket sock, String query) throws Exception {
        String path = "";
        if (query != null && !query.isEmpty()) {
            for (String p : query.split("&")) {
                if (p.startsWith("path=")) {
                    path = URLDecoder.decode(p.substring(5), "UTF-8");
                }
            }
        }

        File audioFile = new File(path);

        // Security check: Path traversal protection with strict directory boundary enforcement
        boolean allowed = isPathUnderDirectory(audioFile, new File(baseDir))
            || isPathUnderDirectory(audioFile, new File("/storage"))
            || isPathUnderDirectory(audioFile, new File("/mnt/media_rw"));

        if (!allowed) {
            byte[] err = "{\"ok\":false,\"error\":\"ACCESS_DENIED: Path traversal forbidden\"}".getBytes(StandardCharsets.UTF_8);
            sendResponse(sock, 403, "application/json", err);
            return;
        }

        if (!audioFile.exists() || !audioFile.isFile()) {
            byte[] err = "{\"ok\":false,\"error\":\"Audio file not found\"}".getBytes(StandardCharsets.UTF_8);
            sendResponse(sock, 404, "application/json", err);
            return;
        }

        long length = audioFile.length();
        OutputStream os = sock.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: audio/wav\r\n"
            + "Content-Length: " + length + "\r\n"
            + "Accept-Ranges: bytes\r\n"
            + "Access-Control-Allow-Origin: *\r\n"
            + "Connection: close\r\n\r\n";
        os.write(header.getBytes(StandardCharsets.US_ASCII));

        try (FileInputStream fis = new FileInputStream(audioFile)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                os.write(buf, 0, r);
            }
            os.flush();
        }
    }

    // -------------------------------------------------------------------------
    // WAV Validation (PCM 48kHz, 16-bit, Mono)
    // -------------------------------------------------------------------------
    public static boolean validatePcmWav48k16BitMono(File file) {
        if (file == null || !file.isFile() || file.length() < 46 || file.length() > 32L * 1024 * 1024) return false;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.readInt() != 0x52494646 || le32(in) + 8 != in.length() || in.readInt() != 0x57415645) return false;
            boolean fmt = false, data = false, signal = false;
            long pos = 12;
            while (pos < in.length()) {
                if (pos + 8 > in.length()) return false;
                in.seek(pos);
                int id = in.readInt(); long size = le32(in);
                long end = pos + 8 + size, next = end + (size & 1);
                if (end > in.length() || next > in.length()) return false;
                if (id == 0x666d7420) {
                    if (fmt || size < 16 || le16(in) != 1 || le16(in) != 1 || le32(in) != 48000
                        || le32(in) != 96000 || le16(in) != 2 || le16(in) != 16) return false;
                    fmt = true;
                } else if (id == 0x64617461) {
                    if (!fmt || data || size < 2 || (size & 1) != 0) return false;
                    data = true;
                    byte[] buf = new byte[8192]; long remaining = size;
                    while (remaining > 0) {
                        int count = (int)Math.min(remaining, buf.length); in.readFully(buf, 0, count);
                        for (int i = 0; i < count; i++) if (buf[i] != 0) signal = true;
                        remaining -= count;
                    }
                }
                pos = next;
            }
            return fmt && data && signal && pos == in.length();
        } catch (Exception invalid) { return false; }
    }
    private static int le16(RandomAccessFile in) throws IOException { return in.readUnsignedByte() | in.readUnsignedByte() << 8; }
    private static long le32(RandomAccessFile in) throws IOException { return (long)le16(in) | (long)le16(in) << 16; }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
        return hash.toString();
    }

    private static int mountDepth(String target) throws Exception {
        String info = readFileString(new File("/proc/self/mountinfo"));
        int depth = 0;
        for (String line : info.split("\n")) {
            String[] fields = line.split(" ");
            if (fields.length < 6) throw new IOException("INVALID_MOUNTINFO");
            if (fields[4].equals(target)) depth++;
        }
        return depth;
    }

    private static final AvasTransaction.Backend ANDROID_BACKEND = new AvasTransaction.Backend() {
        public int depth(String target) throws Exception { return mountDepth(target); }
        public String hash(String path) throws Exception { return sha256(new File(path)); }
        public boolean sameFile(String source, String target) throws Exception { return Files.isSameFile(new File(source).toPath(), new File(target).toPath()); }
        public boolean bind(String source, String target) { return runSystemCommand("/system/bin/mount", "-o", "bind", source, target); }
        public boolean unbind(String target) { return runSystemCommand("/system/bin/umount", target); }
        public boolean context(String target) { return runSystemCommand("/system/bin/restorecon", "-F", target); }
        public boolean serviceRunning() throws Exception { return !servicePids().isEmpty(); }
        public boolean baselineRecoverable(String target) { return target.endsWith(".wav") ? validatePcmWav48k16BitMono(new File(target)) : new File(target).length() > 0; }
        public boolean reload() { return restartAvasHalService(); }
    };
    private static String servicePids() throws Exception {
        Command result = command("/system/bin/pidof", "vendor.molead.hardware.avas_service@1.0-service");
        String pids = result.output.trim();
        return result.ok && pids.matches("[0-9]+( [0-9]+)*") ? pids : "";
    }
    private static boolean restartAvasHalService() {
        if (!isAndroid) return false;
        try {
            String old = servicePids();
            if (!old.isEmpty()) {
                List<String> args = new ArrayList<>(); args.add("/system/bin/kill");
                for (String pid : old.split(" ")) { if (Long.parseLong(pid) <= 1) return false; args.add(pid); }
                if (!runSystemCommand(args.toArray(new String[0]))) return false;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                String pids = servicePids();
                if (!pids.isEmpty() && !pids.equals(old)) {
                    Thread.sleep(200);
                    if (pids.equals(servicePids())) return true;
                }
                Thread.sleep(100);
            }
        } catch (Exception failure) { System.err.println("[AvasSoundDaemon] HAL reload failed"); }
        return false;
    }
    private static final class Command {
        final boolean ok; final String output;
        Command(boolean ok, String output) { this.ok = ok; this.output = output; }
    }
    private static Command command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        final boolean[] overflow = {false};
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (output.size() + count <= 65536) output.write(buffer, 0, count);
                    else overflow[0] = true;
                }
            } catch (IOException ignored) { overflow[0] = true; }
        });
        reader.setDaemon(true); reader.start();
        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        reader.join(1000);
        if (reader.isAlive()) { process.destroyForcibly(); throw new IOException("COMMAND_OUTPUT_TIMEOUT"); }
        return new Command(finished && process.exitValue() == 0 && !overflow[0], new String(output.toByteArray(), StandardCharsets.UTF_8));
    }
    private static String commandOutput(String... args) throws Exception {
        Command result = command(args);
        if (!result.ok) throw new IOException("COMMAND_FAILED: " + args[0]);
        return result.output;
    }
    private static boolean runSystemCommand(String... args) {
        if (!isAndroid) throw new IllegalStateException("ANDROID_COMMAND_FORBIDDEN_ON_LAPTOP");
        try { return command(args).ok; } catch (Exception failure) { return false; }
    }
    private static boolean checkServer() {
        try {
            String token = readFileString(new File(TOKEN_SYSTEM_PATH)).trim();
            if (!validToken(token)) return false;
            HttpURLConnection conn = (HttpURLConnection)new URL("http://127.0.0.1:8889/api/avas/status").openConnection();
            try {
                conn.setConnectTimeout(1000); conn.setReadTimeout(1000); conn.setRequestProperty("X-AVAS-Token", token);
                if (conn.getResponseCode() != 200) return false;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buffer = new byte[2048]; int count;
                    while ((count = in.read(buffer)) != -1) { if (bytes.size() + count > MAX_BODY_BYTES) return false; bytes.write(buffer, 0, count); }
                }
                JSONObject status = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                return status.optBoolean("ok") && status.optInt("protocol_version") == PROTOCOL_VERSION
                    && AUDIO_REVISION.equals(status.optString("audio_revision"))
                    && SELECTION_REVISION.equals(status.optString("selection_revision"));
            } finally { conn.disconnect(); }
        } catch (Exception failure) { return false; }
    }

    private static boolean isPathUnderDirectory(File file, File baseDir) {
        if (file == null || baseDir == null) return false;
        try {
            String cFile = file.getCanonicalPath();
            String cBase = baseDir.getCanonicalPath();
            return cFile.equals(cBase) || cFile.startsWith(cBase + File.separator);
        } catch (Throwable t) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------
    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) fos.write(buf, 0, r);
            fos.getFD().sync();
        }
    }

    private static void sendResponse(Socket sock, int code, String contentType, byte[] body) {
        try {
            OutputStream os = sock.getOutputStream();
            String statusText = (code == 200) ? "OK" :
                                (code == 204) ? "No Content" :
                                (code == 400) ? "Bad Request" :
                                (code == 401) ? "Unauthorized" :
                                (code == 403) ? "Forbidden" :
                                (code == 404) ? "Not Found" :
                                (code == 413) ? "Payload Too Large" :
                                (code == 422) ? "Unprocessable Entity" :
                                (code == 503) ? "Service Unavailable" : "Error";
            String header = "HTTP/1.1 " + code + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type, X-AVAS-Token\r\n"
                + "Connection: close\r\n\r\n";
            os.write(header.getBytes(StandardCharsets.US_ASCII));
            if (body.length > 0) {
                os.write(body);
            }
            os.flush();
        } catch (Throwable ignored) {}
    }

    private static String readFileString(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = fis.read(buf)) != -1) bos.write(buf, 0, r);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeFileString(File f, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        }
    }
}
