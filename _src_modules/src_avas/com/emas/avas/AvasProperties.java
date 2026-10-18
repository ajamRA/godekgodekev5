package com.emas.avas;

/** Mode and enable flag are separate. Reloads must preserve both. */
public final class AvasProperties {
    public static final String[] KEYS = {"persist.avas.mode", "persist.avas.switch", "persist.sys.avas.mode", "persist.sys.avas.switch"};
    public interface Store { String read(String key) throws Exception; boolean write(String key, String value) throws Exception; }
    public interface Reload { boolean run() throws Exception; }
    public static String[] read(Store store) throws Exception {
        String[] values = new String[4];
        for (int i=0;i<4;i++) values[i]=store.read(KEYS[i]).trim();
        return values;
    }
    public static String[] selected(int mode, int enabled) {
        if(mode<1 || mode>3 || (enabled!=0 && enabled!=1)) throw new IllegalArgumentException("INVALID_AVAS_SELECTION");
        // Molead's persisted switch is inverted: 0=enabled, 1=disabled.
        // The project's shadow property and config use 1=enabled.
        return new String[]{""+mode,enabled==1?"0":"1",""+mode,""+enabled};
    }
    public static String[] observed(Store store) throws Exception {
        String mode=store.read(KEYS[0]).trim();
        String oemSwitch=store.read(KEYS[1]).trim();
        if(!mode.equals(store.read(KEYS[0]).trim()) || !oemSwitch.equals(store.read(KEYS[1]).trim()))
            throw new IllegalStateException("AVAS_SELECTION_CHANGED");
        if(!mode.matches("[1-3]") || !oemSwitch.matches("[01]"))
            throw new IllegalStateException("AVAS_SELECTION_UNKNOWN");
        String enabled="0".equals(oemSwitch)?"1":"0";
        return selected(Integer.parseInt(mode),Integer.parseInt(enabled));
    }
    public static boolean matches(Store store,String[] values) throws Exception {
        return java.util.Arrays.equals(read(store),values);
    }
    public static void writeVerified(Store store,String[] values) throws Exception {
        for(int i=0;i<4;i++) if(!values[i].equals(store.read(KEYS[i]).trim()) && !store.write(KEYS[i],values[i])) throw new IllegalStateException("PROPERTY_WRITE_FAILED");
        if(!matches(store,values)) throw new IllegalStateException("PROPERTY_VERIFICATION_FAILED");
    }
    public static void reloadPreserving(Store store,String[] values,Reload reload) throws Exception {
        writeVerified(store,values);
        if(!reload.run()) throw new IllegalStateException("HAL_RELOAD_FAILED");
        // Some HAL versions reset an enable flag during initialization. Verify/reapply after reload.
        writeVerified(store,values);
    }
    private AvasProperties() {}
}
