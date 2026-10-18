package com.emas.megaphone;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** File IPC only: this client never stops/starts OEM services or launches tinyplay. */
public final class AvasPlaybackClient {
    public interface Clock { long millis(); void sleep(long millis) throws InterruptedException; }
    public static final long MAX_LEASE_MS = 15000;
    public static final long MAX_AUDIO_MS = 12500;
    public static final String IPC_VERSION = "2";
    private final Path state, media, proc;
    private final int owner;
    private final Clock clock;

    public AvasPlaybackClient(Path state, Path media, Path proc, int owner, Clock clock) {
        this.state = state; this.media = media; this.proc = proc; this.owner = owner; this.clock = clock;
    }

    public static AvasPlaybackClient androidClient() {
        return new AvasPlaybackClient(Paths.get("/data/local/window/avas_recovery"),
                Paths.get("/data/local/window/megafon"), Paths.get("/proc"), android.os.Process.myPid(),
                new Clock() {
                    public long millis() {
                        try (BufferedReader br = new BufferedReader(new FileReader("/proc/uptime"))) {
                            String line = br.readLine();
                            if (line != null) {
                                String[] parts = line.trim().split("\\s+");
                                return (long)(Double.parseDouble(parts[0]) * 1000);
                            }
                        } catch (Exception ignored) {}
                        return android.os.SystemClock.elapsedRealtime();
                    }
                    public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
                });
    }

    public boolean play(Path wav) throws IOException, InterruptedException {
        if (!Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) return false;
        Path canonical = wav.toRealPath();
        if (!Files.isRegularFile(wav, LinkOption.NOFOLLOW_LINKS)
                || !canonical.getParent().equals(media.toRealPath())
                || !canonical.getFileName().toString().matches("\\.queue_[0-9]+\\.wav")) return false;
        long duration = durationMillis(canonical);
        if (duration <= 0 || duration > MAX_AUDIO_MS) return false;
        String ticks = identity(proc.resolve(Integer.toString(owner)));
        String boot = readSmall(proc.resolve("sys/kernel/random/boot_id")).trim();
        if (ticks == null || !boot.matches("[0-9a-fA-F-]{36}")) return false;
        Path lockPath = state.resolve("client.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Symlink IPC lock rejected");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { return false; }
            if (lock == null) return false;
            try {
                long now = clock.millis();
                if (!guardReady(now, boot) || Files.exists(state.resolve("active"))
                        || Files.exists(state.resolve("request"))) return false;
                String nonce = UUID.randomUUID().toString().replace("-", "");
                long deadline = now + Math.min(MAX_LEASE_MS, duration + 2500);
                Path result = state.resolve("result." + nonce);
                String request = IPC_VERSION + "\n" + nonce + "\n" + owner + "\n" + ticks + "\n" + boot + "\n"
                        + now + "\n" + deadline + "\n" + canonical + "\n";
                atomicWrite(state.resolve("request"), request);
                // Keep the immutable lease on timeout/interruption: the independent guard owns cleanup.
                while (clock.millis() <= deadline + 5000) {
                    if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)) {
                        List<String> lines = Arrays.asList(readSmall(result).split("\\r?\\n"));
                        boolean ok = lines.size() == 4 && IPC_VERSION.equals(lines.get(0)) && nonce.equals(lines.get(1))
                                && "OK".equals(lines.get(2)) && "running".equals(lines.get(3));
                        Files.delete(result);
                        return ok;
                    }
                    clock.sleep(50);
                }
                return false;
            } finally { lock.release(); }
        }
    }

    private boolean guardReady(long now, String boot) throws IOException {
        Path heartbeat = state.resolve("heartbeat");
        if (!Files.isRegularFile(heartbeat, LinkOption.NOFOLLOW_LINKS)) return false;
        String[] h = readSmall(heartbeat).split("\\r?\\n");
        if (h.length != 6 || !IPC_VERSION.equals(h[0]) || !h[1].matches("[1-9][0-9]*")
                || !h[2].matches("[0-9]+") || !h[3].matches("[0-9]+")
                || !boot.equals(h[4]) || !"ready".equals(h[5])) return false;
        long age = now - Long.parseLong(h[3]);
        return age >= 0 && age <= 3000 && h[2].equals(identity(proc.resolve(h[1])));
    }

    static String identity(Path process) throws IOException {
        Path stat = process.resolve("stat");
        if (!Files.exists(stat)) return null;
        String text = readSmall(stat);
        int end = text.lastIndexOf(") ");
        if (end < 0) return null;
        String[] fields = text.substring(end + 2).trim().split("\\s+");
        if (fields.length < 20 || "Z".equals(fields[0]) || "X".equals(fields[0])
                || !fields[19].matches("[0-9]+")) return null;
        return fields[19];
    }

    private static String readSmall(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) throw new IOException("Symlink IPC file rejected");
        try (InputStream in = Files.newInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[512]; int n;
            while ((n = in.read(buf)) != -1) {
                if (bytes.size() + n > 4096) throw new IOException("Oversized IPC record");
                bytes.write(buf, 0, n);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void atomicWrite(Path target, String value) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer b = StandardCharsets.UTF_8.encode(value);
                while (b.hasRemaining()) out.write(b);
                out.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    static long durationMillis(Path wav) throws IOException {
        long length = Files.size(wav);
        if (length < 44 || length > 8 * 1024 * 1024) return -1;
        try (RandomAccessFile f = new RandomAccessFile(wav.toFile(), "r")) {
            if (f.readInt() != 0x52494646) return -1; // RIFF
            long riff = le32(f);
            if (riff + 8 != length || f.readInt() != 0x57415645) return -1;
            long rate = 0; int align = 0;
            while (f.getFilePointer() + 8 <= length) {
                int id = f.readInt(); long size = le32(f); long start = f.getFilePointer();
                if (size > length - start) return -1;
                if (id == 0x666d7420) {
                    if (size < 16) return -1;
                    int format = le16(f), channels = le16(f); long samples = le32(f);
                    rate = le32(f); align = le16(f); int bits = le16(f);
                    if (format != 1 || channels < 1 || channels > 2 || samples < 8000 || samples > 96000
                            || (bits != 8 && bits != 16) || align != channels * bits / 8 || rate != samples * align) return -1;
                } else if (id == 0x64617461) {
                    if (rate <= 0 || size <= 0 || size % align != 0) return -1;
                    return (size * 1000 + rate - 1) / rate;
                }
                f.seek(start + size + (size & 1));
            }
        }
        return -1;
    }
    private static int le16(RandomAccessFile f) throws IOException { return f.readUnsignedByte() | f.readUnsignedByte() << 8; }
    private static long le32(RandomAccessFile f) throws IOException { return (long)le16(f) | (long)le16(f) << 16; }
}
