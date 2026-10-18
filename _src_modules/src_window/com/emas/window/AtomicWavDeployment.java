package com.emas.window;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Strict canonical PCM WAV deployment. Never starts/stops any vehicle service. */
public final class AtomicWavDeployment {
    private AtomicWavDeployment() {}
    // Application limit, NOT an assertion about a vendor hardware buffer.
    // 576044 = 44-byte header + 3.0s @ 48kHz/16-bit/stereo — sepadan dengan
    // pipeline VPS (ffmpeg -t 3.0) dan preset sedia (2.0005s = 384134 bait).
    // Had lama 384044 (2.0s tepat) menolak preset sendiri secara senyap.
    public static final int MAX_BYTES = 576044;
    public interface Verifier { void verify(Path active) throws IOException; }
    public static void validate(Path file) throws IOException {
        regular(file);
        long size = Files.size(file);
        if (size <= 44 || size > MAX_BYTES) throw new IOException("WAV size outside policy");
        byte[] h = new byte[44];
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) { in.readFully(h); }
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157 ||
            b.getInt(12) != 0x20746d66 || b.getInt(16) != 16 ||
            b.getShort(20) != 1 || b.getShort(22) != 2 || b.getInt(24) != 48000 ||
            b.getInt(28) != 192000 || b.getShort(32) != 4 || b.getShort(34) != 16 ||
            b.getInt(36) != 0x61746164 || b.getInt(4) != size - 8 ||
            b.getInt(40) != size - 44 || (size - 44) % 4 != 0) {
            throw new IOException("Require canonical 44-byte-header PCM 48kHz/16-bit/stereo WAV");
        }
    }
    private static void regular(Path p) throws IOException {
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file: " + p);
    }
    public static String sha256(Path file) throws IOException {
        return sha256Bounded(file, MAX_BYTES);
    }
    /** Raw downloads may include metadata; canonical deployment keeps its stricter limit. */
    public static String sha256Bounded(Path file, long maxBytes) throws IOException {
        if (maxBytes <= 0 || maxBytes > MAX_BYTES + 65536L) throw new IOException("Invalid hash size policy");
        regular(file);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192]; int n; long total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) throw new IOException("Hash input exceeds WAV policy");
                    digest.update(buf, 0, n);
                }
            }
            StringBuilder s = new StringBuilder();
            for (byte v : digest.digest()) s.append(String.format("%02x", v & 255));
            return s.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private static void checked(Path p, String hash) throws IOException {
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) throw new IOException("Approved SHA-256 required");
        validate(p);
        if (!sha256(p).equalsIgnoreCase(hash)) throw new IOException("SHA-256 mismatch: " + p);
    }
    private static Path stage(Path source, Path parent, String hash) throws IOException {
        Path tmp = Files.createTempFile(parent, ".lock-sound-", ".tmp");
        try {
            try (InputStream in = Files.newInputStream(source);
                 FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                byte[] buf = new byte[8192]; int n, total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("Source grew beyond size limit");
                    out.write(buf, 0, n);
                }
                out.getFD().sync();
            }
            checked(tmp, hash);
            return tmp;
        } catch (IOException | RuntimeException e) { Files.deleteIfExists(tmp); throw e; }
    }
    private static void move(Path from, Path to) throws IOException {
        // No delete-then-rename and no non-atomic fallback, including on unsupported filesystems.
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel dir = FileChannel.open(to.getParent(), StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            System.err.println("Directory fsync unavailable; power-loss durability NOT established");
        }
    }
    public static void deploy(Path source, Path active, String sourceHash, String knownGoodHash) throws IOException {
        deploy(source, active, sourceHash, knownGoodHash, p -> checked(p, sourceHash));
    }
    // Verifier hook supports laptop fault injection; it is not a playback-health guarantee.
    public static void deploy(Path source, Path active, String sourceHash, String knownGoodHash,
                              Verifier verifier) throws IOException {
        deploy(source, active, sourceHash, knownGoodHash, verifier, p -> {});
    }
    /** Prepare the actual inode before every rename, including recovery staging. */
    public static void deploy(Path source, Path active, String sourceHash, String knownGoodHash,
                              Verifier verifier, Verifier prepareStaging) throws IOException {
        active = active.toAbsolutePath().normalize();
        Path parent = active.getParent();
        if (!Files.isDirectory(parent) || Files.isSymbolicLink(active)) throw new IOException("Invalid target");
        Path backup = parent.resolve(active.getFileName() + ".bak");
        Path lockPath = parent.resolve(active.getFileName() + ".deploy.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Symlink lock rejected");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another audio deployment owns the lock");
            checked(source, sourceHash);
            if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                // Only enroll a currently active file explicitly approved by its known-good hash.
                checked(active, knownGoodHash);
                Path b = stage(active, parent, knownGoodHash);
                try { prepareStaging.verify(b); checked(b, knownGoodHash); move(b, backup); }
                finally { Files.deleteIfExists(b); }
            }
            checked(backup, knownGoodHash); // Never overwrite an existing recovery baseline.
            Path tmp = stage(source, parent, sourceHash);
            boolean installed = false;
            try {
                prepareStaging.verify(tmp);
                checked(tmp, sourceHash);
                move(tmp, active);
                installed = true;
                checked(active, sourceHash);
                verifier.verify(active);
            } catch (IOException | RuntimeException failure) {
                if (installed) {
                    try {
                        Path rollback = stage(backup, parent, knownGoodHash);
                        try { prepareStaging.verify(rollback); checked(rollback, knownGoodHash);
                              move(rollback, active); checked(active, knownGoodHash); }
                        finally { Files.deleteIfExists(rollback); }
                    } catch (IOException | RuntimeException recovery) { failure.addSuppressed(recovery); }
                }
                throw failure;
            } finally { Files.deleteIfExists(tmp); }
        }
    }
}
