package com.emas.window;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Small bounded HTTP parser for the native loopback bridge, not a public web server. */
public final class LocalHttpRequest {
    public final String method, target, body;
    private LocalHttpRequest(String method, String target, String body) {
        this.method = method; this.target = target; this.body = body;
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (b.size() < 2048) {
            int c = in.read();
            if (c < 0) throw new EOFException("Incomplete HTTP header");
            if (c == '\n') {
                byte[] bytes = b.toByteArray();
                if (bytes.length == 0 || bytes[bytes.length - 1] != '\r') throw new IOException("CRLF required");
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            b.write(c);
        }
        throw new IOException("HTTP line too long");
    }
    public static LocalHttpRequest read(InputStream in) throws IOException {
        String request = line(in);
        String[] parts = request.split(" ");
        if (parts.length != 3 || !parts[1].startsWith("/") || !parts[2].equals("HTTP/1.1"))
            throw new IOException("Invalid request line");
        if (!parts[0].equals("GET") && !parts[0].equals("POST")) throw new IOException("Unsupported method");
        int length = 0, total = request.length(); boolean haveLength = false;
        while (true) {
            String h = line(in); total += h.length() + 2;
            if (total > 8192) throw new IOException("Headers too large");
            if (h.isEmpty()) break;
            int colon = h.indexOf(':');
            if (colon <= 0) throw new IOException("Invalid header");
            String key = h.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = h.substring(colon + 1).trim();
            if (key.equals("transfer-encoding")) throw new IOException("Transfer encoding unsupported");
            // Browser-origin requests must use an authenticated native bridge, not direct control.
            if (key.equals("origin")) throw new IOException("Browser-origin request rejected");
            if (key.equals("content-length")) {
                if (haveLength || !value.matches("[0-9]{1,4}")) throw new IOException("Invalid length");
                haveLength = true; length = Integer.parseInt(value);
                if (length > 8192) throw new IOException("Body too large");
            }
        }
        if (parts[0].equals("GET") && length != 0) throw new IOException("GET body rejected");
        byte[] body = new byte[length];
        new DataInputStream(in).readFully(body);
        return new LocalHttpRequest(parts[0], parts[1], new String(body, StandardCharsets.UTF_8));
    }
}
