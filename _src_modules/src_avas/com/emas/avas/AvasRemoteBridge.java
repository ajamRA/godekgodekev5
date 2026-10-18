package com.emas.avas;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.Iterator;
import org.json.JSONObject;

/** Opt-in phone bridge. Its durable ledger never retries an ambiguous local mutation. */
public final class AvasRemoteBridge {
    static final int MAX_AUDIO_BYTES = 8 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int MAX_OPERATIONS = 4096;
    private static boolean started;

    interface Transport {
        JSONObject status() throws Exception;
        JSONObject sync(JSONObject request) throws Exception;
        void download(String path, File destination, long expectedBytes) throws Exception;
        JSONObject mutate(String path, JSONObject request) throws Exception;
    }
    interface PrivateFiles { void protect(File file) throws IOException; }

    static final class Config {
        final URI origin;
        final String apiKey;
        final String deviceId;
        Config(JSONObject json) throws Exception {
            URI value = new URI(json.getString("base_url"));
            String path = value.getRawPath();
            if (!"https".equalsIgnoreCase(value.getScheme()) || value.getHost() == null
                    || value.getRawUserInfo() != null || value.getRawQuery() != null
                    || value.getRawFragment() != null || !(path == null || path.isEmpty() || "/".equals(path))
                    || value.getPort() == 0 || value.getPort() > 65535) throw new IOException("INVALID_REMOTE_ORIGIN");
            origin = new URI("https", null, value.getHost(), value.getPort(), "", null, null);
            apiKey = json.getString("api_key");
            deviceId = json.getString("device_id");
            if (apiKey.isEmpty() || apiKey.length() > 512 || !apiKey.matches("[\\x21-\\x7e]+")
                    || !deviceId.matches("[A-Za-z0-9._:-]{1,128}")) throw new IOException("INVALID_REMOTE_CONFIG");
        }
    }

    private final File root;
    private final File ledgerFile;
    private final File inbox;
    private final String deviceId;
    private final Transport transport;
    private final PrivateFiles privateFiles;
    private JSONObject ledger;

    AvasRemoteBridge(File root, String deviceId, Transport transport) throws Exception {
        this(root, deviceId, transport, new PrivateFiles() {
            @Override public void protect(File file) throws IOException { ownerOnly(file); }
        });
    }

    AvasRemoteBridge(File root, String deviceId, Transport transport, PrivateFiles privateFiles) throws Exception {
        this.root = root.getCanonicalFile();
        this.deviceId = deviceId;
        this.transport = transport;
        this.privateFiles = privateFiles;
        ledgerFile = new File(this.root, "remote-ledger.json");
        inbox = new File(this.root, "remote-inbox");
        requireChild(ledgerFile);
        requireChild(inbox);
        if (ledgerFile.exists()) {
            ledger = new JSONObject(readText(ledgerFile, MAX_JSON_BYTES));
            if (ledger.getInt("version") != 1) throw new IOException("INVALID_REMOTE_LEDGER");
            JSONObject operations = ledger.getJSONObject("operations");
            if (operations.length() > MAX_OPERATIONS) throw new IOException("INVALID_REMOTE_LEDGER");
            String pending = ledger.optString("pending", "");
            if (!pending.isEmpty() && (!validId(pending) || !operations.has(pending))) throw new IOException("INVALID_REMOTE_LEDGER");
            Iterator<String> ids = operations.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                JSONObject entry = operations.getJSONObject(id);
                String status = entry.getString("status");
                if (!validId(id) || !id.equals(entry.optString("id"))
                        || !("dispatching".equals(status) || "applied".equals(status)
                        || "failed".equals(status) || "uncertain".equals(status))) throw new IOException("INVALID_REMOTE_LEDGER");
                if ("dispatching".equals(status)) {
                    operations.put(id, result(id, "uncertain", "LOCAL_RESULT_UNKNOWN"));
                    ledger.put("pending", id);
                }
            }
            saveLedger();
        } else {
            ledger = new JSONObject().put("version", 1).put("operations", new JSONObject()).put("pending", "");
            saveLedger();
        }
    }

    /** Caller must be the Android daemon. Missing config leaves the bridge completely disabled. */
    public static synchronized void startIfConfigured(File baseDir, int daemonPort) {
        if (started || !new File(baseDir, "remote.json").isFile()) return;
        if (!(System.getProperty("java.vendor", "").toLowerCase().contains("android")
                || new File("/system/bin/sh").isFile())) return;
        try {
            if (!baseDir.getCanonicalPath().equals("/data/local/avas")) throw new IOException("ANDROID_BASE_DIR_FIXED");
            File configFile = new File(baseDir, "remote.json");
            if (!configFile.getCanonicalFile().equals(configFile.getAbsoluteFile())) throw new IOException("INVALID_CONFIG_PATH");
            ownerOnly(configFile);
            Config config = new Config(new JSONObject(readText(configFile, 8192)));
            final AvasRemoteBridge bridge = new AvasRemoteBridge(baseDir, config.deviceId, new HttpTransport(config, daemonPort));
            Thread thread = new Thread(new Runnable() {
                @Override public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        try { bridge.tick(); } catch (Exception ignored) { /* Credentials and payloads are never logged. */ }
                        try { Thread.sleep(10000L); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                    }
                }
            }, "avas-remote-bridge");
            thread.setDaemon(true);
            thread.start();
            started = true;
        } catch (Exception error) {
            System.err.println("[AvasRemoteBridge] Disabled: configuration or durable state unavailable.");
        }
    }

    synchronized void tick() throws Exception {
        JSONObject status = transport.status();
        boolean compatible = Boolean.TRUE.equals(status.opt("ok")) && Boolean.TRUE.equals(status.opt("is_android"))
                && exactInteger(status.opt("protocol_version"), 2) && "20261014".equals(status.opt("audio_revision"));
        JSONObject request = new JSONObject().put("device_id", deviceId).put("bridge_protocol", 1)
                .put("daemon_protocol", 2).put("audio_revision", "20261014").put("status", status);
        String pending = ledger.optString("pending", "");
        JSONObject operations = ledger.getJSONObject("operations");
        if (!pending.isEmpty()) {
            JSONObject entry = operations.getJSONObject(pending);
            if ("dispatching".equals(entry.optString("status"))) {
                entry = result(pending, "uncertain", "LOCAL_RESULT_UNKNOWN");
                operations.put(pending, entry);
                saveLedger();
            }
            request.put("result", new JSONObject(entry.toString()));
        }
        JSONObject reply = transport.sync(request);
        if (!Boolean.TRUE.equals(reply.opt("ok"))) return;
        if (!pending.isEmpty()) {
            ledger.put("pending", "");
            saveLedger();
        }
        JSONObject job = reply.optJSONObject("job");
        if (job == null || !compatible) return;
        String id = job.optString("id");
        if (!validId(id)) throw new IOException("INVALID_JOB_ID");
        if (operations.has(id)) {
            ledger.put("pending", id);
            saveLedger();
            return;
        }
        if (operations.length() >= MAX_OPERATIONS) throw new IOException("REMOTE_LEDGER_FULL");
        File download = null;
        boolean dispatched = false;
        boolean localResolved = false;
        JSONObject outcome;
        try {
            String action = job.getString("action");
            int slot = job.getInt("slot");
            if (!exactInteger(job.opt("slot"), slot) || slot < 1 || slot > 3 || !("apply".equals(action) || "restore".equals(action))) throw new IOException("INVALID_JOB");
            JSONObject body = new JSONObject().put("slot", slot);
            if ("apply".equals(action)) {
                String uploadId = job.getString("upload_id");
                String hash = job.getString("sha256");
                long bytes = job.getLong("bytes");
                String name = job.getString("name");
                String audioPath = job.getString("audio_path");
                if (!validId(uploadId) || !hash.matches("[a-f0-9]{64}") || !exactInteger(job.opt("bytes"), bytes) || bytes < 44 || bytes > MAX_AUDIO_BYTES
                        || name.isEmpty() || name.length() > 80 || name.matches("(?s).*[\\x00-\\x1f\\x7f].*")
                        || !audioPath.equals("/api/remote/avas/audio/" + uploadId)) throw new IOException("INVALID_AUDIO_JOB");
                requireChild(inbox);
                if (!inbox.isDirectory() && !inbox.mkdir()) throw new IOException("INBOX_UNAVAILABLE");
                download = new File(inbox, id + "-" + UUID.randomUUID().toString().replace("-", "") + ".wav");
                requireChild(download);
                if (!download.createNewFile()) throw new IOException("INBOX_COLLISION");
                privateFiles.protect(download);
                transport.download(audioPath, download, bytes);
                if (download.length() != bytes || !hash.equals(sha256(download))) throw new IOException("AUDIO_INTEGRITY_FAILED");
                if (!AvasSoundDaemon.validatePcmWav48k16BitMono(download)) throw new IOException("INVALID_WAV_48K_MONO_16BIT");
                body.put("file_path", download.getAbsolutePath()).put("custom_name", name).put("activate", true);
            }
            // A crash after this commit is always uncertain, even if no response reached this process.
            operations.put(id, new JSONObject().put("id", id).put("status", "dispatching"));
            ledger.put("pending", id);
            saveLedger();
            dispatched = true;
            JSONObject mutationReply = transport.mutate("/api/avas/" + action, body);
            if (!mutationReply.has("ok") || !(mutationReply.get("ok") instanceof Boolean)) {
                outcome = result(id, "uncertain", "LOCAL_RESULT_UNKNOWN");
            } else if (mutationReply.getBoolean("ok")) {
                localResolved = true;
                outcome = result(id, "applied", null);
            } else {
                localResolved = true;
                // Do not relay local error details: paths, credentials or shell output may be present.
                outcome = result(id, "failed", "LOCAL_OPERATION_FAILED");
            }
        } catch (Exception error) {
            outcome = result(id, dispatched ? "uncertain" : "failed", dispatched ? "LOCAL_RESULT_UNKNOWN" : "JOB_VALIDATION_OR_DOWNLOAD_FAILED");
        } finally {
            // Keep the source after an ambiguous local request: the daemon may still be staging it.
            if (download != null && (!dispatched || localResolved)) {
                requireChild(download);
                Files.deleteIfExists(download.toPath());
            }
        }
        operations.put(id, outcome);
        ledger.put("pending", id);
        saveLedger();
    }

    private static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{32}"); }
    private static boolean exactInteger(Object value, long expected) {
        return value instanceof Number && ((Number)value).longValue() == expected && ((Number)value).doubleValue() == (double)expected;
    }
    private static JSONObject result(String id, String status, String error) throws Exception {
        JSONObject json = new JSONObject().put("id", id).put("status", status);
        if (error != null) json.put("error", error);
        return json;
    }
    private void requireChild(File file) throws Exception {
        String canonical = file.getCanonicalPath();
        if (!canonical.equals(file.getAbsolutePath()) || !canonical.startsWith(root.getPath() + File.separator)) throw new IOException("INVALID_BRIDGE_PATH");
    }
    private void saveLedger() throws Exception {
        requireChild(ledgerFile);
        File temp = new File(root, "remote-ledger-" + UUID.randomUUID().toString().replace("-", "") + ".tmp");
        requireChild(temp);
        try {
            if (!temp.createNewFile()) throw new IOException("LEDGER_COLLISION");
            privateFiles.protect(temp);
            byte[] data = ledger.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_JSON_BYTES) throw new IOException("REMOTE_LEDGER_FULL");
            try (FileOutputStream output = new FileOutputStream(temp)) { output.write(data); output.getFD().sync(); }
            Files.move(temp.toPath(), ledgerFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
    private static void ownerOnly(File file) throws IOException {
        if (!file.setReadable(false, false) || !file.setWritable(false, false)
                || !file.setExecutable(false, false) || !file.setReadable(true, true)
                || !file.setWritable(true, true)) throw new IOException("PRIVATE_FILE_PERMISSION_FAILED");
    }
    private static String readText(File file, int limit) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) { return new String(readBounded(input, limit), StandardCharsets.UTF_8); }
    }
    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() > limit - count) throw new IOException("RESPONSE_TOO_LARGE");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 255));
        return hex.toString();
    }

    static final class HttpTransport implements Transport {
        private final Config config;
        private final int port;
        HttpTransport(Config config, int port) throws IOException {
            if (port < 1 || port > 65535) throw new IOException("INVALID_DAEMON_PORT");
            this.config = config; this.port = port;
        }
        static URL remoteUrl(Config config, String path) throws Exception {
            if (!("/api/remote/avas/sync".equals(path) || path.matches("/api/remote/avas/audio/[a-f0-9]{32}"))) throw new IOException("INVALID_REMOTE_PATH");
            URI resolved = config.origin.resolve(path);
            if (!config.origin.getScheme().equals(resolved.getScheme()) || !config.origin.getAuthority().equals(resolved.getAuthority())) throw new IOException("CROSS_ORIGIN_REJECTED");
            return resolved.toURL();
        }
        private HttpURLConnection open(String path, boolean remote) throws Exception {
            HttpURLConnection connection = (HttpURLConnection)(remote ? remoteUrl(config, path) : new URL("http://127.0.0.1:" + port + path)).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(remote ? 15000 : 120000);
            connection.setUseCaches(false);
            if (remote) connection.setRequestProperty("X-API-Key", config.apiKey);
            else {
                String token = readText(new File(AvasSoundDaemon.TOKEN_SYSTEM_PATH), 256).trim();
                if (!token.matches("[a-f0-9]{48}")) throw new IOException("INVALID_LOCAL_TOKEN");
                connection.setRequestProperty("X-AVAS-Token", token);
            }
            return connection;
        }
        static void requireSuccess(HttpURLConnection connection) throws IOException {
            // Redirects are rejected before opening a body, including same-origin redirects.
            if (connection.getResponseCode() != 200) throw new IOException("HTTP_REQUEST_REJECTED");
        }
        static boolean definiteRejection(int code) {
            return code == 400 || code == 401 || code == 403 || code == 404
                || code == 409 || code == 422 || code == 503;
        }
        private JSONObject json(String path, JSONObject payload, boolean remote) throws Exception {
            HttpURLConnection connection = open(path, remote);
            try {
                if (payload != null) {
                    byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
                    connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                }
                int code = connection.getResponseCode();
                boolean rejected = !remote && payload != null && definiteRejection(code);
                if (!rejected) requireSuccess(connection);
                try (InputStream input = rejected ? connection.getErrorStream() : connection.getInputStream()) {
                    if (input == null) throw new IOException("LOCAL_RESULT_UNKNOWN");
                    JSONObject reply = new JSONObject(new String(readBounded(input, MAX_JSON_BYTES), StandardCharsets.UTF_8));
                    if (rejected && !Boolean.FALSE.equals(reply.opt("ok"))) throw new IOException("LOCAL_RESULT_UNKNOWN");
                    return reply;
                }
            } finally { connection.disconnect(); }
        }
        public JSONObject status() throws Exception { return json("/api/avas/status", null, false); }
        public JSONObject sync(JSONObject request) throws Exception { return json("/api/remote/avas/sync", request, true); }
        public JSONObject mutate(String path, JSONObject request) throws Exception {
            if (!("/api/avas/apply".equals(path) || "/api/avas/restore".equals(path))) throw new IOException("INVALID_MUTATION_PATH");
            return json(path, request, false);
        }
        public void download(String path, File destination, long expectedBytes) throws Exception {
            HttpURLConnection connection = open(path, true);
            try {
                requireSuccess(connection);
                long declared = connection.getContentLengthLong();
                if (declared != -1 && declared != expectedBytes) throw new IOException("AUDIO_LENGTH_MISMATCH");
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[8192]; long total = 0; int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > expectedBytes || total > MAX_AUDIO_BYTES) throw new IOException("AUDIO_TOO_LARGE");
                        output.write(buffer, 0, count);
                    }
                    if (total != expectedBytes) throw new IOException("AUDIO_LENGTH_MISMATCH");
                    output.getFD().sync();
                }
            } finally { connection.disconnect(); }
        }
    }
}
