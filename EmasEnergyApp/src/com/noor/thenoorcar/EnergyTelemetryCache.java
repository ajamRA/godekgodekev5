package com.noor.thenoorcar;

import org.json.JSONObject;

/** Compatibility snapshot. Reading it never renews the age of the original observation. */
public final class EnergyTelemetryCache {
    private String json = "{}";
    private long receivedAt = -1;
    private double ageAtReceipt = -1;
    private boolean available;

    public synchronized void receive(JSONObject data, long startedAt, long now) throws Exception {
        if (!Boolean.TRUE.equals(data.opt("ok"))) { invalidate(); return; }
        Object age = data.opt("age_ms");
        double rawAge = age instanceof Number ? ((Number) age).doubleValue() : -1;
        ageAtReceipt = Double.isFinite(rawAge) && rawAge >= 0 && now >= startedAt
                ? rawAge + (now - startedAt) : -1;
        json = data.toString();
        receivedAt = now;
        available = true;
    }

    public synchronized void invalidate() { available = false; }

    public synchronized JSONObject snapshot(long now) throws Exception {
        JSONObject data = new JSONObject(json);
        double age = receivedAt >= 0 && now >= receivedAt && ageAtReceipt >= 0
                ? ageAtReceipt + (now - receivedAt) : -1;
        data.put("age_ms", age);
        data.put("ok", available && receivedAt >= 0 && now >= receivedAt);
        return data;
    }
}
