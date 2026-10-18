package com.noor.thenoorcar;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** Per-install capability provisioned by the root daemon in the app's private sandbox. */
public final class LocalVehicleAuth {
    public interface Credentials { String read() throws IOException; }
    private LocalVehicleAuth() {}
    public static String readToken() throws IOException {
        return readToken(new File("/data/user/0/com.noor.thenoorcar/files/vehicle-auth.token"));
    }
    static String readToken(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[65]; int total = 0, n;
            while (total < bytes.length && (n = in.read(bytes, total, bytes.length - total)) != -1) total += n;
            String token = new String(bytes, 0, total, StandardCharsets.US_ASCII);
            if (!token.matches("[0-9a-f]{64}")) throw new IOException("Vehicle credentials unavailable");
            return token;
        }
    }
    public static void apply(HttpURLConnection connection, Credentials credentials) throws IOException {
        String token = credentials.read();
        if (token == null || !token.matches("[0-9a-f]{64}")) throw new IOException("Vehicle credentials unavailable");
        connection.setRequestProperty("X-Emas-Auth", token);
    }
}
