package com.emas.avas;

import java.util.ArrayList;
import java.util.List;

/** A new bind is layered over the previous file. Only our new layer may be undone. */
public final class AvasTransaction {
    public interface Backend {
        int depth(String target) throws Exception;
        String hash(String path) throws Exception;
        boolean sameFile(String source, String target) throws Exception;
        boolean bind(String source, String target) throws Exception;
        boolean unbind(String target) throws Exception;
        boolean context(String target) throws Exception;
        boolean serviceRunning() throws Exception;
        boolean baselineRecoverable(String target) throws Exception;
        boolean reload() throws Exception;
    }
    public interface Commit { void run() throws Exception; }
    public static final class Change {
        public final String source, target;
        public Change(String source, String target) { this.source = source; this.target = target; }
    }
    private static final class Before {
        final Change change;
        final int depth;
        final String hash;
        final boolean recoverable;
        Before(Change c, int d, String h, boolean r) { change = c; depth = d; hash = h; recoverable = r; }
    }
    private AvasTransaction() {}

    public static void execute(Backend backend, List<Change> changes, Commit commit) throws Exception {
        // Complete preflight for every target before changing any mount.
        if (!backend.serviceRunning()) throw new IllegalStateException("HAL_NOT_RUNNING: no changes made");
        List<Before> before = new ArrayList<>();
        for (Change c : changes) {
            int depth = backend.depth(c.target);
            if (depth < 0 || depth >= 8) throw new IllegalStateException("MOUNT_DEPTH_UNSAFE: no changes made");
            backend.hash(c.source); // refuse unreadable sources
            before.add(new Before(c, depth, backend.hash(c.target), backend.baselineRecoverable(c.target)));
        }
        List<Before> attempted = new ArrayList<>();
        boolean reloaded = false;
        try {
            for (Before b : before) {
                attempted.add(b); // a timed-out bind may still have taken effect
                if (!backend.bind(b.change.source, b.change.target)
                    || backend.depth(b.change.target) != b.depth + 1
                    || !backend.sameFile(b.change.source, b.change.target)
                    || !backend.hash(b.change.source).equals(backend.hash(b.change.target)))
                    throw new IllegalStateException("BIND_VERIFICATION_FAILED");
                if (!backend.context(b.change.target)) throw new IllegalStateException("RESTORECON_FAILED");
            }
            reloaded = true; // reload failure also requires recovery
            if (!backend.reload()) throw new IllegalStateException("HAL_RELOAD_FAILED");
            commit.run();
        } catch (Exception failure) {
            boolean restored = true;
            for (int i = attempted.size() - 1; i >= 0; i--) {
                Before b = attempted.get(i);
                if (!b.recoverable) restored = false; // an old broken WAV is not a safe audio recovery
                try {
                    int depth = backend.depth(b.change.target);
                    if (depth == b.depth + 1) {
                        if (!backend.unbind(b.change.target)) restored = false;
                    } else if (depth != b.depth) restored = false; // do not pop an unknown layer
                    if (backend.depth(b.change.target) != b.depth
                        || !b.hash.equals(backend.hash(b.change.target))) restored = false;
                } catch (Exception ignored) { restored = false; }
            }
            if (reloaded) {
                try { if (!backend.reload()) restored = false; }
                catch (Exception ignored) { restored = false; }
            }
            throw new IllegalStateException(failure.getMessage() + "; ROLLBACK_" +
                (restored ? "VERIFIED" : "UNVERIFIED: manual recovery required; do not retry"), failure);
        }
    }
}
