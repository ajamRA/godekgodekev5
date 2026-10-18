package com.emas.door;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, four-door snapshot. Wall time is for display; freshness uses elapsed time. */
public final class DoorSnapshot {
    public enum State { OPEN, CLOSED, UNKNOWN }
    public static final long MAX_AGE_MS = 400;
    private static final String[] KEYS = {"fl", "fr", "rl", "rr"};
    public final String generation;
    public final long sequence, sampledElapsedMs, timestamp;
    private final State[] states;
    private final long[] epochs;

    public DoorSnapshot(String generation, long sequence, long sampledElapsedMs,
                        long timestamp, State[] states) {
        this(generation, sequence, sampledElapsedMs, timestamp, states, new long[4]);
    }
    public DoorSnapshot(String generation, long sequence, long sampledElapsedMs,
                        long timestamp, State[] states, long[] epochs) {
        if (!generation.matches("[a-f0-9]{32}") || sequence < 0 || sampledElapsedMs < 0
                || timestamp < 0 || states.length != 4 || epochs.length != 4) throw new IllegalArgumentException("snapshot");
        this.generation = generation;
        this.sequence = sequence;
        this.sampledElapsedMs = sampledElapsedMs;
        this.timestamp = timestamp;
        this.states = states.clone();
        this.epochs = epochs.clone();
        for (State state : this.states) if (state == null) throw new IllegalArgumentException("state");
        for (long epoch : this.epochs) if (epoch < 0) throw new IllegalArgumentException("epoch");
    }

    public State state(int door) { return states[door]; }
    public State[] states() { return states.clone(); }
    public long epoch(int door) { return epochs[door]; }
    public long[] epochs() { return epochs.clone(); }
    public boolean isFresh(long elapsedMs) {
        return sequence > 0 && elapsedMs >= sampledElapsedMs
                && elapsedMs - sampledElapsedMs <= MAX_AGE_MS;
    }
    public Boolean opened() {
        boolean unknown = false;
        for (State state : states) {
            if (state == State.OPEN) return true;
            unknown |= state == State.UNKNOWN;
        }
        return unknown ? null : false;
    }
    public boolean complete() {
        for (State state : states) if (state == State.UNKNOWN) return false;
        return true;
    }
    public String toJson(long elapsedMs) {
        boolean fresh = isFresh(elapsedMs);
        StringBuilder out = new StringBuilder(350);
        out.append("{\"schema\":1,\"ok\":").append(fresh)
           .append(",\"generation\":\"").append(generation).append("\",\"sequence\":").append(sequence)
           .append(",\"timestamp\":").append(timestamp)
           .append(",\"sampled_elapsed_ms\":").append(sampledElapsedMs)
           .append(",\"age_ms\":").append(sequence == 0 || elapsedMs < sampledElapsedMs ? -1 : elapsedMs - sampledElapsedMs);
        for (int i = 0; i < 4; i++) out.append(",\"").append(KEYS[i]).append("\":\"")
                .append(fresh ? states[i] : State.UNKNOWN).append('"')
                .append(",\"").append(KEYS[i]).append("_epoch\":").append(epochs[i]);
        return out.append(",\"opened\":").append(fresh ? opened() : null)
                .append(",\"complete\":").append(fresh && complete()).append('}').toString();
    }

    /** Small strict flat JSON codec: no Android dependency or extra runtime/library in either JAR. */
    public static DoorSnapshot parse(String json, long elapsedMs) throws IOException {
        Map<String, Object> fields = new FlatJson(json).read();
        if (fields.size() != 17 || number(fields, "schema") != 1
                || !Boolean.TRUE.equals(fields.get("ok"))) throw new IOException("Unavailable/schema");
        Object gen = fields.get("generation");
        if (!(gen instanceof String) || !((String) gen).matches("[a-f0-9]{32}")) throw new IOException("Generation");
        State[] states = new State[4];
        long[] epochs = new long[4];
        try {
            for (int i = 0; i < 4; i++) {
                states[i] = State.valueOf((String) fields.get(KEYS[i])); epochs[i] = number(fields, KEYS[i] + "_epoch");
            }
            DoorSnapshot snapshot = new DoorSnapshot((String) gen, number(fields, "sequence"),
                    number(fields, "sampled_elapsed_ms"), number(fields, "timestamp"), states, epochs);
            long age = number(fields, "age_ms");
            if (!snapshot.isFresh(elapsedMs) || age < 0 || age > MAX_AGE_MS
                    || !Boolean.valueOf(snapshot.complete()).equals(fields.get("complete"))
                    || !java.util.Objects.equals(snapshot.opened(), fields.get("opened"))) throw new IOException("Stale/inconsistent snapshot");
            return snapshot;
        } catch (IllegalArgumentException | NullPointerException | ClassCastException e) {
            throw new IOException("Invalid snapshot", e);
        }
    }
    private static long number(Map<String, Object> fields, String key) throws IOException {
        Object value = fields.get(key);
        if (!(value instanceof Long)) throw new IOException("Missing/invalid " + key);
        return (Long) value;
    }
    private static final class FlatJson {
        private final String text;
        private int pos;
        FlatJson(String text) throws IOException {
            if (text == null || text.length() > 4096) throw new IOException("JSON size");
            this.text = text;
        }
        Map<String, Object> read() throws IOException {
            Map<String, Object> fields = new LinkedHashMap<>();
            expect('{');
            while (true) {
                String key = string(); expect(':'); Object value = value();
                if (fields.containsKey(key) || fields.size() >= 20) throw new IOException("Duplicate/too many fields");
                fields.put(key, value); whitespace();
                if (take('}')) break;
                expect(',');
            }
            whitespace();
            if (pos != text.length()) throw new IOException("Trailing JSON");
            return fields;
        }
        Object value() throws IOException {
            whitespace();
            if (pos < text.length() && text.charAt(pos) == '"') return string();
            for (String token : new String[]{"true", "false", "null"}) {
                if (text.startsWith(token, pos)) {
                    pos += token.length();
                    return token.equals("null") ? null : Boolean.valueOf(token);
                }
            }
            int begin = pos;
            if (pos < text.length() && text.charAt(pos) == '-') pos++;
            int digits = pos;
            while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') pos++;
            if (pos == digits || pos - digits > 1 && text.charAt(digits) == '0') throw new IOException("Number");
            try { return Long.valueOf(text.substring(begin, pos)); }
            catch (NumberFormatException e) { throw new IOException("Number overflow", e); }
        }
        String string() throws IOException {
            expect('"'); int begin = pos;
            while (pos < text.length() && text.charAt(pos) != '"') {
                char c = text.charAt(pos++);
                if (c < 32 || c > 126 || c == '\\') throw new IOException("Unsupported string");
            }
            if (pos >= text.length()) throw new IOException("String");
            return text.substring(begin, pos++);
        }
        void whitespace() { while (pos < text.length() && " \r\n\t".indexOf(text.charAt(pos)) >= 0) pos++; }
        boolean take(char c) { whitespace(); if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; } return false; }
        void expect(char c) throws IOException { if (!take(c)) throw new IOException("Expected " + c); }
    }
}
