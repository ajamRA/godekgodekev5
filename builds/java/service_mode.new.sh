#!/system/bin/sh
# ==========================================================
# Proton e.MAS 5 — MOD SERVIS PROTON (STEALTH / FACTORY STOCK)
# Toggle / Enable / Disable all custom mods & third-party apps
# ==========================================================

FLAG="/data/local/window/SERVICE_MODE_ACTIVE"
STOCK_LOCK="/data/local/tmp/stock_lock.wav"
ACTIVE_LOCK="/data/local/window/locksounds/lock_active.wav"
TARGET_LOCK="/sdcard/V3_Vehicle_Locked_Single.wav"
CHIME="/data/local/window/mode_chime.wav"

APPS="org.smarttube.stable com.netflix.mediaclient com.noor.thenoorcar com.vivaldi.browser.automotive me.zhanghai.android.files com.malaysia.syok com.thisisaim.hotfm com.cybercat.acbridge"

ACTION="$1"
if [ "$ACTION" = "toggle" ] || [ -z "$ACTION" ]; then
    if [ -f "$FLAG" ]; then
        ACTION="off"
    else
        ACTION="on"
    fi
fi

if [ "$ACTION" = "on" ]; then
    echo "$(date) [SERVICE_MODE] ENABLING MOD SERVIS (STEALTH KILANG)..."
    touch "$FLAG"

    # 1. Matikan door pressure relief daemon & tutup tingkap rapat
    pkill -f "DoorPressureReliefDaemon" 2>/dev/null
    echo "[SERVICE_MODE] Door pressure relief daemon stopped."

    # 2. Matikan door chime / salam
    #    SalamBoot didaftarkan oleh start_salam.sh; pkill "DoorChime" tidak
    #    menyentuhnya. Perlu pola berasingan.
    pkill -f "DoorChime" 2>/dev/null
    pkill -f "SalamBoot" 2>/dev/null
    pkill -f "/data/local/window/salam" 2>/dev/null
    echo "[SERVICE_MODE] Door chime and salam daemons stopped."

    # 3. Kembalikan lock sound asal kilang Proton/Geely
    if [ -f "$STOCK_LOCK" ]; then
        chmod 666 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null
        cp "$STOCK_LOCK" /sdcard/V3_Vehicle_Locked_Single.wav 2>/dev/null
        cp "$STOCK_LOCK" /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null
        pkill -f ecarx_reverse_radar 2>/dev/null
        sleep 0.3
        nohup /system/bin/ecarx_reverse_radar >/dev/null 2>&1 &
        echo "[SERVICE_MODE] Factory lock chime restored."
    fi

    # 4. Pulihkan AVAS kilang — KETIGA-TIGA slot
    # /vendor ialah partisi dinamik ro berisi fail 0-bait. Umount sahaja
    # mendedahkan fail kosong, bukan bunyi kilang. Jadi kita bind mount
    # semula fail kilang dari /data/local/avas/factory_backup/.
    for _slot in 1 2 3; do
        case "$_slot" in
            1) _wav=yinheyinfu.wav ;;
            2) _wav=space.wav ;;
            3) _wav=pixel.wav ;;
        esac
        _tgt="/vendor/etc/avas/sound_type_${_slot}/${_wav}"
        _src="/data/local/avas/factory_backup/${_wav}"
        umount -l "$_tgt" 2>/dev/null
        if [ -f "$_src" ]; then
            mount -o bind "$_src" "$_tgt" 2>/dev/null
            restorecon -F "$_tgt" 2>/dev/null
        fi
        umount -l "/vendor/etc/avas/sound_type_${_slot}/bDM_01_01.wav" 2>/dev/null
        umount -l "/vendor/etc/avas/sound_type_${_slot}/tuning.txt" 2>/dev/null
        umount -l "/vendor/etc/avas/sound_type_${_slot}/tuning_r.txt" 2>/dev/null
    done
    setprop persist.avas.mode 2 2>/dev/null
    AVAS_PID=$(pidof vendor.molead.hardware.avas_service@1.0-service)
    [ -n "$AVAS_PID" ] && kill -9 "$AVAS_PID" 2>/dev/null
    echo "[SERVICE_MODE] AVAS restored to factory on all 3 slots."

    # 4b. Hentikan daemon telemetri & megafon (aplikasi klien sudah dilumpuhkan)
    #     SteeringModeDaemon DIKEKALKAN — ia mengesan butang Volume Down
    #     untuk toggle Mod Servis.
    pkill -f "TelemetryDaemon" 2>/dev/null
    pkill -f "MegaphoneDaemon" 2>/dev/null
    echo "[SERVICE_MODE] Telemetry and megaphone daemons stopped."

    # 5. Sembunyikan semua sideloaded apps (SmartTube, Netflix, EmasEnergy, dll)
    for pkg in $APPS; do
        pm disable-user --user 0 "$pkg" 2>/dev/null
    done
    echo "[SERVICE_MODE] Third-party apps disabled and hidden from app drawer."

    # 6. Audio chime feedback dua kali beep
    if [ -f "$CHIME" ]; then
        /data/local/window/tinyplay "$CHIME" -d 0 -p 1024 2>/dev/null
        sleep 1
        /data/local/window/tinyplay "$CHIME" -d 0 -p 1024 2>/dev/null
    fi

    echo "$(date) [SERVICE_MODE] MOD SERVIS AKTIF (KERETA KINI STANDARD KILANG)."
    exit 0

elif [ "$ACTION" = "off" ]; then
    echo "$(date) [SERVICE_MODE] DISABLING MOD SERVIS (PULIHKAN CUSTOM MODS)..."
    rm -f "$FLAG"

    # 1. Hidupkan semula semua apps
    for pkg in $APPS; do
        pm enable "$pkg" 2>/dev/null
    done
    echo "[SERVICE_MODE] Apps restored and visible in app drawer."

    # 2. Hidupkan semula door pressure relief daemon
    if [ -f /data/local/window/start_pressure_relief.sh ]; then
        sh /data/local/window/start_pressure_relief.sh 2>/dev/null &
        echo "[SERVICE_MODE] Door pressure relief daemon restarted."
    fi

    # 3. Hidupkan semula door chime daemon
    if [ -f /data/local/window/start_door_chime.sh ]; then
        sh /data/local/window/start_door_chime.sh 2>/dev/null &
        echo "[SERVICE_MODE] Door chime daemon restarted."
    fi

    # 3b. Hidupkan semula salam / doa daemon
    if [ -f /data/local/window/start_salam.sh ]; then
        sh /data/local/window/start_salam.sh 2>/dev/null &
        echo "[SERVICE_MODE] Salam daemon restarted."
    fi

    # 4. Pulihkan lock sound custom mario
    if [ -f "$ACTIVE_LOCK" ]; then
        chmod 666 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null
        cp "$ACTIVE_LOCK" /sdcard/V3_Vehicle_Locked_Single.wav 2>/dev/null
        cp "$ACTIVE_LOCK" /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null
        chmod 444 /sdcard/V3_Vehicle_Locked_Single.wav /data/media/0/V3_Vehicle_Locked_Single.wav 2>/dev/null
        pkill -f ecarx_reverse_radar 2>/dev/null
        sleep 0.3
        nohup /system/bin/ecarx_reverse_radar >/dev/null 2>&1 &
        echo "[SERVICE_MODE] Custom lock sound restored."
    fi

    # 5. Pulihkan AVAS custom
    # /data/local/tmp dibersihkan pada reboot; /data/local/avas kekal.
    # Cuba kedua-dua laluan, utamakan yang kekal.
    if [ -f /data/local/avas/avas_boot.sh ]; then
        sh /data/local/avas/avas_boot.sh 2>/dev/null &
        echo "[SERVICE_MODE] Custom AVAS restored (persistent path)."
    elif [ -f /data/local/tmp/avas_boot_new.sh ]; then
        sh /data/local/tmp/avas_boot_new.sh 2>/dev/null &
        echo "[SERVICE_MODE] Custom AVAS restored (tmp path)."
    else
        echo "[SERVICE_MODE] AMARAN: tiada skrip AVAS dijumpai; AVAS kekal kilang."
    fi

    # 6. Audio chime feedback satu kali beep
    if [ -f "$CHIME" ]; then
        /data/local/window/tinyplay "$CHIME" -d 0 -p 1024 2>/dev/null
    fi

    echo "$(date) [SERVICE_MODE] MOD NORMAL PULIH SEPENUHNYA."
    exit 0

elif [ "$ACTION" = "status" ]; then
    if [ -f "$FLAG" ]; then
        echo "SERVICE_MODE_ACTIVE"
    else
        echo "NORMAL_MODE_ACTIVE"
    fi
fi
