package com.emas.avas;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Validate the complete saved plan before the first vehicle mutation. */
public final class AvasBootPlan {
    public final List<AvasTransaction.Change> changes=new ArrayList<>();
    public String[] selection;
    public static AvasBootPlan load(JSONObject config,File base) throws Exception {
        AvasBootPlan plan=new AvasBootPlan();
        if(!config.has("persistence_version")) return plan; // old installs are never silently migrated at boot
        if(config.getInt("persistence_version")!=1) throw new IllegalStateException("UNKNOWN_PERSISTENCE_VERSION");
        plan.selection=AvasProperties.selected(config.getInt("active_slot"),config.getInt("switch"));
        JSONObject slots=config.getJSONObject("slots");
        for(int slot=1;slot<=3;slot++) {
            JSONObject state=slots.getJSONObject(""+slot);
            String prefix="/vendor/etc/avas/sound_type_"+slot+"/";
            if("custom".equals(state.optString("source"))) {
                File audio=verified(base,state.getString("audio_file"),state.getString("audio_sha256"));
                if(!AvasSoundDaemon.validatePcmWav48k16BitMono(audio)) throw new IllegalStateException("SAVED_WAV_INVALID");
                String name=slot==1?"yinheyinfu.wav":slot==2?"space.wav":"pixel.wav";
                plan.changes.add(new AvasTransaction.Change(audio.getCanonicalPath(),prefix+name));
            }
            if(state.has("tuning_file")) {
                File tuning=verified(base,state.getString("tuning_file"),state.getString("tuning_sha256"));
                plan.changes.add(new AvasTransaction.Change(tuning.getCanonicalPath(),prefix+"tuning.txt"));
            }
        }
        return plan;
    }
    static File verified(File base,String path,String expected) throws Exception {
        File file=new File(path);
        String root=base.getCanonicalPath()+File.separator, actual=file.getCanonicalPath();
        if(!file.isFile() || Files.isSymbolicLink(file.toPath()) || !actual.startsWith(root)
                || !actual.equals(file.getAbsoluteFile().toPath().normalize().toString()) || !expected.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("SAVED_PATH_INVALID");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(file.toPath())) {byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format("%02x",b&255));
        if(!expected.equals(hash.toString()))throw new IllegalStateException("SAVED_HASH_MISMATCH");
        return file;
    }
    private AvasBootPlan() {}
}
