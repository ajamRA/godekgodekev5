#!/system/bin/sh
LOG=/data/local/avas/avas_boot.log
echo "=== AVAS FACTORY RESTORE START: $(date) ===" > $LOG

SVC=vendor.molead.hardware.avas_service@1.0-service

# 1. Unmount all custom AVAS mounts
umount -l /vendor/etc/avas/sound_type_1/yinheyinfu.wav 2>/dev/null
umount -l /vendor/etc/avas/sound_type_1/bDM_01_01.wav 2>/dev/null
umount -l /vendor/etc/avas/sound_type_1/tuning.txt 2>/dev/null
umount -l /vendor/etc/avas/sound_type_1/tuning_r.txt 2>/dev/null

umount -l /vendor/etc/avas/sound_type_2/space.wav 2>/dev/null
umount -l /vendor/etc/avas/sound_type_2/bDM_01_01.wav 2>/dev/null
umount -l /vendor/etc/avas/sound_type_2/tuning.txt 2>/dev/null
umount -l /vendor/etc/avas/sound_type_2/tuning_r.txt 2>/dev/null

umount -l /vendor/etc/avas/sound_type_3/pixel.wav 2>/dev/null
umount -l /vendor/etc/avas/sound_type_3/bDM_01_01.wav 2>/dev/null
umount -l /vendor/etc/avas/sound_type_3/tuning.txt 2>/dev/null
umount -l /vendor/etc/avas/sound_type_3/tuning_r.txt 2>/dev/null

# 2. Bind-mount ONLY genuine factory audio files (because /vendor partition is 0-byte dynamic ro)
# Slot 1: Yin He Yin Fu (Galaxy Chord - Factory)
if [ -f /data/local/avas/factory_backup/yinheyinfu.wav ]; then
    mount -o bind /data/local/avas/factory_backup/yinheyinfu.wav /vendor/etc/avas/sound_type_1/yinheyinfu.wav >> $LOG 2>&1
    restorecon -F /vendor/etc/avas/sound_type_1/yinheyinfu.wav >> $LOG 2>&1
    echo "[SLOT 1] Mounted factory yinheyinfu.wav" >> $LOG
fi

# Slot 2: Space (Tai Kong - Factory)
if [ -f /data/local/avas/factory_backup/space.wav ]; then
    mount -o bind /data/local/avas/factory_backup/space.wav /vendor/etc/avas/sound_type_2/space.wav >> $LOG 2>&1
    restorecon -F /vendor/etc/avas/sound_type_2/space.wav >> $LOG 2>&1
    echo "[SLOT 2] Mounted factory space.wav" >> $LOG
fi

# Slot 3: Pixel (Sport - Factory)
if [ -f /data/local/avas/factory_backup/pixel.wav ]; then
    mount -o bind /data/local/avas/factory_backup/pixel.wav /vendor/etc/avas/sound_type_3/pixel.wav >> $LOG 2>&1
    restorecon -F /vendor/etc/avas/sound_type_3/pixel.wav >> $LOG 2>&1
    echo "[SLOT 3] Mounted factory pixel.wav" >> $LOG
fi

# All tuning.txt, tuning_r.txt and bDM_01_01.wav are UNTOUCHED on /vendor (100% factory original curves & chime)

# 3. Synchronize IHU screen settings (persist.sys.avas.*) with AVAS HAL (persist.avas.*)
MODE=$(getprop persist.sys.avas.mode)
[ -z "$MODE" ] && MODE=1
SWITCH=$(getprop persist.sys.avas.switch)
[ -z "$SWITCH" ] && SWITCH=1

setprop persist.avas.mode "$MODE"
setprop persist.avas.switch "$SWITCH"
echo "Synced props on boot: mode=$MODE, switch=$SWITCH" >> $LOG

# 4. Restart AVAS HAL service so it starts with synced mode & mounts
P=$(pidof $SVC)
if [ -n "$P" ]; then
    echo "Restarting $SVC (old PID $P)..." >> $LOG
    kill $P
    sleep 2
    echo "New PID: $(pidof $SVC)" >> $LOG
fi

# 5. Start background AVAS Sync Daemon (keeps screen taps synced live)
if [ -f /data/local/avas/sync_avas.sh ]; then
    sh /data/local/avas/sync_avas.sh >> $LOG 2>&1 &
    echo "Started AVAS Sync Daemon" >> $LOG
fi

# Check if Service Centre Stealth Mode is active
if [ -f /data/local/window/.service_mode ]; then
    echo "[SERVICE_MODE] ACTIVE! Skipping all custom daemons for SC visit." >> $LOG
    echo "=== AVAS FACTORY RESTORE COMPLETED: ALL SOUNDS 100% FACTORY ORIGINAL ===" >> $LOG
    cat $LOG
    exit 0
fi

# 6. Auto-start Cabin Pressure Relief Daemon if not already running
if [ -f /data/local/window/start_pressure_relief.sh ]; then
    sh /data/local/window/start_pressure_relief.sh >> $LOG 2>&1
fi

# 7. Auto-start Megaphone Daemon if not already running
if [ -f /data/local/window/start_megaphone.sh ]; then
    sh /data/local/window/start_megaphone.sh >> $LOG 2>&1
fi

# 8. Auto-start Gear Audio Doa Trigger (one-shot per reboot)
if [ -f /data/local/window/start_gear_audio.sh ]; then
    sh /data/local/window/start_gear_audio.sh >> $LOG 2>&1
fi

# 9. Auto-start Door Salam Trigger (one-shot per reboot)
if [ -f /data/local/window/start_door_salam.sh ]; then
    sh /data/local/window/start_door_salam.sh >> $LOG 2>&1
fi

echo "=== AVAS FACTORY RESTORE COMPLETED: ALL SOUNDS 100% FACTORY ORIGINAL ===" >> $LOG
cat $LOG