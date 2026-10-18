package com.emas.avas;

import java.nio.charset.StandardCharsets;

/**
 * AVAS Tuning File Encoder & Decoder for Geely Xingyuan / Proton e.MAS 5 (IHU801P).
 *
 * Implements reverse-engineered Molead Yin library format:
 * - CRC16-CCITT with polynomial 0x1021, initial value 0xFFFF, skipping '\r' (0x0D).
 * - XOR 0x70 cipher with 4 preserved exception bytes: 0x0D (\r), 0x0A (\n), 0x70 ('p'), 0x7D ('}').
 * - Format: Header "Encryption_Code:<CRC>\r\n" followed by plaintext body.
 *
 * Hardened to resolve cipher asymmetry on forbidden bytes {0x7D, 0x7A, 0x00, 0x0D}
 * and enforce strict safety limits on volume (0..20) and pitch (80..130) curves.
 */
public final class AvasTuningEncoder {

    public static final int MIN_VOLUME = 0;
    public static final int MAX_VOLUME = 20;
    public static final int MIN_PITCH = 80;
    public static final int MAX_PITCH = 130;
    public static final int REQUIRED_CURVE_POINTS = 7;

    private static final int[] CRC_TABLE = new int[256];

    static {
        int poly = 0x1021;
        for (int i = 0; i < 256; i++) {
            int curr = i << 8;
            for (int j = 0; j < 8; j++) {
                if ((curr & 0x8000) != 0) {
                    curr = ((curr << 1) ^ poly) & 0xFFFF;
                } else {
                    curr = (curr << 1) & 0xFFFF;
                }
            }
            CRC_TABLE[i] = curr;
        }
    }

    private AvasTuningEncoder() {}

    /**
     * Compute CRC16-CCITT checksum over byte array, skipping byte 0x0D ('\r').
     */
    public static int computeCrc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            int val = b & 0xFF;
            if (val == 0x0D) continue; // skip \r
            crc = ((crc << 8) & 0xFF00) ^ CRC_TABLE[(val ^ (crc >> 8)) & 0xFF];
        }
        return crc & 0xFFFF;
    }

    /**
     * Apply XOR 0x70 with 4 exceptions: 0x0D (\r), 0x0A (\n), 0x70, 0x7D.
     */
    public static byte[] xor70(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            int b = in[i] & 0xFF;
            if (b == 0x0D || b == 0x0A || b == 0x70 || b == 0x7D) {
                out[i] = (byte) b;
            } else {
                out[i] = (byte) (b ^ 0x70);
            }
        }
        return out;
    }

    /**
     * Sanitizes input text to eliminate characters in the forbidden set {0x7D, 0x7A, 0x00, 0x0D}:
     * - 'z' (0x7A) -> 'Z' (0x5A) to avoid 0x7A ^ 0x70 = 0x0A (\n collision during decode)
     * - '}' (0x7D) -> ']' (0x5D) to avoid 0x7D exception collision
     * - 0x00 (NUL) -> ' ' (0x20) to avoid 0x00 ^ 0x70 = 0x70 ('p' collision)
     * - Standalone 0x0D (\r) not part of \r\n -> ' ' (0x20)
     */
    public static String sanitizeText(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length());
        int len = input.length();
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            int code = (int) c;
            if (code == 0x7A) {
                sb.append('Z');
            } else if (code == 0x7D) {
                sb.append(']');
            } else if (code == 0x00) {
                sb.append(' ');
            } else if (code == 0x0D) {
                if (i + 1 < len && input.charAt(i + 1) == 0x0A) {
                    sb.append(c);
                } else {
                    sb.append(' ');
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Generates a fully encoded (XOR 0x70 + CRC16) tuning.txt byte array
     * for a given AVAS sound slot.
     */
    public static byte[] generateEncodedTuning(int slot, int[] volume, int[] pitch, boolean muteChime) {
        String trackName;
        switch (slot) {
            case 1: trackName = "yinheyinfu.wav"; break;
            case 2: trackName = "space.wav"; break;
            case 3:
            default:
                trackName = "pixel.wav"; break;
        }

        // Validate exact 7-point curve requirement
        if (volume == null || volume.length != REQUIRED_CURVE_POINTS) {
            throw new IllegalArgumentException("Volume curve must have exactly " + REQUIRED_CURVE_POINTS + " points");
        }
        if (pitch == null || pitch.length != REQUIRED_CURVE_POINTS) {
            throw new IllegalArgumentException("Pitch curve must have exactly " + REQUIRED_CURVE_POINTS + " points");
        }

        // Clamp volume (0..20) and pitch (80..130) to safe hardware limits
        int[] clampedVol = new int[REQUIRED_CURVE_POINTS];
        int[] clampedPitch = new int[REQUIRED_CURVE_POINTS];
        boolean allZero = true;
        for (int i = 0; i < REQUIRED_CURVE_POINTS; i++) {
            clampedVol[i] = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, volume[i]));
            clampedPitch[i] = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch[i]));
            if (clampedVol[i] > 0) allZero = false;
        }

        // Anti-mute: An all-zero curve is prohibited by UNECE R138
        if (allZero) {
            throw new IllegalArgumentException("UNECE_R138_PROHIBITED: All-zero volume curve is prohibited. AVAS must emit audible acoustic warning.");
        }

        // Chime track (Track 1): if muteChime is requested, set volumes to 0
        String chimeVolume = muteChime ? "0,0,0,0,0,0,0" : "0,2,5,6,7,2,0";
        String chimePitch = "100,105,110,115,120,125,130";

        StringBuilder sbVol = new StringBuilder();
        StringBuilder sbPitch = new StringBuilder();
        for (int i = 0; i < REQUIRED_CURVE_POINTS; i++) {
            if (i > 0) {
                sbVol.append(',');
                sbPitch.append(',');
            }
            sbVol.append(clampedVol[i]);
            sbPitch.append(clampedPitch[i]);
        }

        // Plaintext body (starts immediately after Encryption_Code:XXXXX\r\n)
        StringBuilder body = new StringBuilder();
        body.append("Sound_Mode:").append(slot).append("\r\n");
        body.append("Pedal:0\r\n");
        body.append("Break:0\r\n");
        body.append("Volume_type:31,31,31\r\n");
        body.append("Track:/vendor/etc/avas/sound_type_").append(slot).append("/bDM_01_01.wav\r\n");
        body.append("Mixer_Volume:10,10,10\r\n");
        body.append("Pitch:").append(chimePitch).append("\r\n");
        body.append("Volume:").append(chimeVolume).append("\r\n");
        body.append("Track:/vendor/etc/avas/sound_type_").append(slot).append("/").append(trackName).append("\r\n");
        body.append("Mixer_Volume:10,10,10\r\n");
        body.append("Pitch:").append(sbPitch).append("\r\n");
        body.append("Volume:").append(sbVol).append("\r\n");
        body.append("[stop]\r\n");

        String sanitizedBody = sanitizeText(body.toString());
        byte[] bodyBytes = sanitizedBody.getBytes(StandardCharsets.US_ASCII);
        int crc = computeCrc16(bodyBytes);

        String header = "Encryption_Code:" + crc + "\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);

        byte[] fullPlain = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, fullPlain, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, fullPlain, headerBytes.length, bodyBytes.length);

        return xor70(fullPlain);
    }
}
