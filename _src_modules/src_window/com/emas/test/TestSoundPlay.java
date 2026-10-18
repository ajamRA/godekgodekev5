package com.emas.test;

import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.net.Uri;
import java.io.File;

public class TestSoundPlay {
    public static void main(String[] args) {
        String path = args.length > 0 ? args[0] : "/sdcard/V3_Vehicle_Locked_Single.wav";
        System.out.println("Testing playback of: " + path);
        try {
            File f = new File(path);
            if (!f.exists()) {
                System.out.println("File does not exist: " + path);
                return;
            }
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build());
            mp.setDataSource(path);
            mp.prepare();
            mp.start();
            System.out.println("Playback started! Duration: " + mp.getDuration() + "ms");
            Thread.sleep(mp.getDuration() + 500);
            mp.release();
            System.out.println("Playback finished!");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
