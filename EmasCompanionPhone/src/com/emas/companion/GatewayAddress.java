package com.emas.companion;

import java.net.URI;

public final class GatewayAddress {
    private GatewayAddress() {}

    public static String normalize(String input) {
        if (input == null || input.trim().isEmpty() || input.length() > 128)
            throw new IllegalArgumentException("Masukkan IP gateway pada laptop.");
        String value = input.trim();
        if (!value.contains("://")) value = "http://" + value;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"http".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) throw new Exception();
            String[] octets = host.split("\\.");
            if (octets.length != 4) throw new Exception();
            int[] numbers = new int[4];
            for (int i = 0; i < 4; i++) {
                numbers[i] = Integer.parseInt(octets[i]);
                if (numbers[i] < 0 || numbers[i] > 255
                        || !Integer.toString(numbers[i]).equals(octets[i])) throw new Exception();
            }
            if (!(numbers[0] == 10 || numbers[0] == 192 && numbers[1] == 168
                    || numbers[0] == 172 && numbers[1] >= 16 && numbers[1] <= 31)) throw new Exception();
            int port = uri.getPort() == -1 ? 8766 : uri.getPort();
            if (port == 5555) throw new IllegalArgumentException("5555 ialah port ADB. Gunakan port gateway 8766.");
            if (port < 1024 || port > 65535) throw new Exception();
            return "http://" + host + ":" + port + "/";
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("5555")) throw error;
            throw new IllegalArgumentException("Gunakan IP laptop, contohnya 192.168.1.8:8766.");
        } catch (Exception error) {
            throw new IllegalArgumentException("Gunakan IP laptop, contohnya 192.168.1.8:8766.");
        }
    }

    public static boolean sameOrigin(String base, String candidate) {
        try {
            URI a = new URI(base), b = new URI(candidate);
            return a.getScheme().equals(b.getScheme()) && a.getHost().equals(b.getHost())
                    && a.getPort() == b.getPort() && b.getUserInfo() == null;
        } catch (Exception error) { return false; }
    }
}
