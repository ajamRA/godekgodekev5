package com.emas.telemetry;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** A root daemon publishes a fresh capability only inside the trusted app's private directory. */
final class ActuatorAuth {
    private static volatile String token;
    private static final String APP_DIR = "/data/user/0/com.noor.thenoorcar";
    private ActuatorAuth() {}
    static String generate() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder(64);
        for (byte b : bytes) value.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return value.toString();
    }
    static boolean matches(String expected, String supplied) {
        return expected != null && expected.matches("[0-9a-f]{64}") && supplied != null
                && supplied.matches("[0-9a-f]{64}") && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }
    static synchronized void provision() throws Exception {
        token = null; // Provisioning failure must never leave a usable old capability in memory.
        StructStat app = Os.lstat(APP_DIR);
        if (!OsConstants.S_ISDIR(app.st_mode) || app.st_uid < 10000 || (app.st_mode & 0002) != 0
                || ((app.st_mode & 0020) != 0 && app.st_gid != app.st_uid))
            throw new IOException("Unsafe app sandbox");
        String files = APP_DIR + "/files";
        if (!new File(files).exists()) { Os.mkdir(files, 0700); Os.chown(files, app.st_uid, app.st_gid); }
        StructStat dir = Os.lstat(files);
        if (!OsConstants.S_ISDIR(dir.st_mode) || dir.st_uid != app.st_uid || dir.st_gid != app.st_gid
                || (dir.st_mode & 0002) != 0 || ((dir.st_mode & 0020) != 0 && dir.st_gid != dir.st_uid))
            throw new IOException("Unsafe app files directory");
        String fresh = generate();
        String temporary = files + "/.vehicle-auth-" + java.util.UUID.randomUUID();
        java.io.FileDescriptor fd = Os.open(temporary,
                OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
        try {
            try (FileOutputStream out = new FileOutputStream(fd)) {
                out.write(fresh.getBytes(StandardCharsets.US_ASCII)); out.getFD().sync();
            }
            Os.chmod(temporary, 0600); Os.chown(temporary, app.st_uid, app.st_gid);
            Os.rename(temporary, files + "/vehicle-auth.token");
            token = fresh;
        } finally { new File(temporary).delete(); }
    }
    static boolean authorized(String supplied) { return matches(token, supplied); }
}
