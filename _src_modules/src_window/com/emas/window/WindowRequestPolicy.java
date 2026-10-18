package com.emas.window;

/** Exact route contract: status reads never dispatch window movement. */
public final class WindowRequestPolicy {
    public enum Action { STATUS, DOOR_STATE, CRACK, CLOSE, NOT_FOUND, METHOD_NOT_ALLOWED }
    private WindowRequestPolicy() {}
    public static Action classify(String method,String target) {
        if (!"GET".equals(method)) return Action.METHOD_NOT_ALLOWED;
        if ("/api/door/state".equals(target)) return Action.DOOR_STATE;
        if ("/".equals(target) || "/api/status".equals(target) || "/api/window/status".equals(target)) return Action.STATUS;
        if ("/api/window".equals(target) || "/api/window?action=crack".equals(target)) return Action.CRACK;
        if ("/api/window?action=close".equals(target) || "/api/window?percent=0".equals(target)) return Action.CLOSE;
        return Action.NOT_FOUND;
    }
}
