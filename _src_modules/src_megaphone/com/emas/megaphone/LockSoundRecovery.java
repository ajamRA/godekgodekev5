package com.emas.megaphone;

import com.emas.window.AtomicWavDeployment;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Boot-only reconciliation. The daemon remains the sole WAV installer. */
final class LockSoundRecovery {
    interface Installer { boolean install(File preset) throws IOException; }
    interface Canonicalizer { byte[] read(File preset) throws IOException; }
    private final Path dir, target, stock;
    private final Canonicalizer canonicalizer;
    private String previous = "";
    private int stable, attempts;

    LockSoundRecovery(Path dir, Path target, Path stock, Canonicalizer canonicalizer) {
        this.dir = dir; this.target = target; this.stock = stock;
        this.canonicalizer = canonicalizer;
    }

    static String hash(byte[] bytes) throws IOException {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    static void remember(Path dir, String key, Path canonical) throws IOException {
        if (!key.matches("[a-z0-9_]{1,32}") || "active".equals(key)) throw new IOException("Invalid selection");
        AtomicWavDeployment.validate(canonical);
        String digest = AtomicWavDeployment.sha256(canonical);
        Properties selection = saved(dir);
        if (!key.equals(selection.getProperty("sound")) || !digest.equals(selection.getProperty("sha256")))
            selection.clear();
        selection.setProperty("sound", key);
        selection.setProperty("sha256", digest);
        writeSelection(dir, selection);
    }

    static Properties saved(Path dir) throws IOException {
        Properties selection = new Properties();
        Path file = dir.resolve("lock_selection.properties");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 2048)
                throw new IOException("Invalid saved selection");
            try (InputStream in = Files.newInputStream(file)) { selection.load(in); }
        }
        return selection;
    }

    static void rememberRemote(Path dir, String key, String canonicalHash, String deployId,
                               long revision, String sourceHash, boolean radarRestarted) throws IOException {
        Properties selection = saved(dir);
        if (!key.equals(selection.getProperty("sound")) || !canonicalHash.equals(selection.getProperty("sha256"))
                || !canonicalHash.equals(AtomicWavDeployment.sha256(dir.resolve("lock_active.wav")))
                || !sourceHash.matches("[a-f0-9]{64}") || revision <= 0
                || !deployId.matches("[A-Za-z0-9_.:\\-]{1,80}")) throw new IOException("Remote selection not committed");
        selection.setProperty("remote_deploy_id", deployId);
        selection.setProperty("remote_revision", Long.toString(revision));
        selection.setProperty("remote_source_sha256", sourceHash);
        selection.setProperty("remote_radar_restarted", Boolean.toString(radarRestarted));
        writeSelection(dir, selection);
    }

    private static void writeSelection(Path dir, Properties selection) throws IOException {
        Path staging = Files.createTempFile(dir, ".lock_selection_", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(staging.toFile())) {
                selection.store(out, "Last committed lock sound; no playback claim");
                out.getFD().sync();
            }
            Files.move(staging, dir.resolve("lock_selection.properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staging); }
    }

    private String selection() throws IOException {
        Path mirror = dir.resolve("lock_active.wav");
        AtomicWavDeployment.validate(mirror);
        String desiredHash = AtomicWavDeployment.sha256(mirror);
        Path saved = dir.resolve("lock_selection.properties");
        if (Files.exists(saved, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(saved, LinkOption.NOFOLLOW_LINKS) || Files.size(saved) > 2048)
                throw new IOException("Invalid saved selection");
            Properties selection = new Properties();
            try (InputStream in = Files.newInputStream(saved)) { selection.load(in); }
            String key = selection.getProperty("sound", "");
            if (!key.matches("[a-z0-9_]{1,32}") || "active".equals(key)
                    || !desiredHash.equals(selection.getProperty("sha256"))
                    || !desiredHash.equals(hash(canonicalizer.read(dir.resolve("lock_" + key + ".wav").toFile()))))
                throw new IOException("Saved selection does not match preset and mirror");
            return key;
        }
        // Upgrade existing cars only from the actual committed mirror, never stale lock_state.json.
        String match = null;
        int count = 0;
        try (DirectoryStream<Path> presets = Files.newDirectoryStream(dir, "lock_*.wav")) {
            int scanned = 0;
            for (Path preset : presets) {
                if (++scanned > 64) throw new IOException("Too many lock presets");
                String name = preset.getFileName().toString();
                if (!name.matches("lock_[a-z0-9_]{1,32}\\.wav") || name.equals("lock_active.wav")) continue;
                try {
                    if (desiredHash.equals(hash(canonicalizer.read(preset.toFile())))) {
                        match = name.substring(5, name.length() - 4); count++;
                    }
                } catch (IOException invalidPreset) { /* Not a candidate. */ }
            }
        }
        if (count != 1) throw new IOException("Committed selection missing or ambiguous");
        remember(dir, match, mirror);
        return match;
    }

    String tick(boolean bootReady, boolean serviceMode, boolean parked, Installer installer) throws IOException {
        if (!bootReady || serviceMode || !parked) { stable = 0; previous = ""; return "WAITING_OR_SERVICE_MODE"; }
        String key = selection();
        String desiredHash = AtomicWavDeployment.sha256(dir.resolve("lock_active.wav"));
        String actualHash = AtomicWavDeployment.sha256Bounded(target, AtomicWavDeployment.MAX_BYTES + 65536L);
        if (desiredHash.equals(actualHash)) { stable = 0; previous = ""; return "SYNCHRONIZED:" + key; }
        // Only repair a recognized factory overwrite. Preserve any unknown external change.
        String stockHash = AtomicWavDeployment.sha256Bounded(stock, AtomicWavDeployment.MAX_BYTES + 65536L);
        if (!stockHash.equals(actualHash)) { stable = 0; previous = ""; return "UNKNOWN_TARGET_PRESERVED"; }
        String observation = key + ":" + desiredHash + ":" + actualHash;
        stable = observation.equals(previous) ? stable + 1 : 1;
        previous = observation;
        if (stable < 3) return "WAITING_FOR_STABLE_FACTORY_OVERWRITE";
        if (attempts >= 3) return "ATTEMPT_LIMIT";
        attempts++;
        stable = 0;
        // Installer owns validation, approved rollback baseline, atomic commit and reload.
        boolean committed = installer.install(dir.resolve("lock_" + key + ".wav").toFile());
        if (!committed || !desiredHash.equals(AtomicWavDeployment.sha256(target)))
            return "RESTORE_FAILED_OR_CHANGED";
        return "RESTORED:" + key;
    }
}
