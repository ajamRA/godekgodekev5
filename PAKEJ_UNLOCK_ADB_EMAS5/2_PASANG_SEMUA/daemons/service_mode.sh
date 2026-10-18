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
    pkill -f "DoorChime" 2>/dev/null
    echo "[SERVICE_MODE] Door chime daemon stopped."

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

    # 4. Unmount custom AVAS sound -> kembali bunyi kilang
    umount -l /vendor/etc/avas/sound_type_1/yinheyinfu.wav 2>/dev/null
    umount -l /vendor/etc/avas/sound_type_1/bDM_01_01.wav 2>/dev/null
    umount -l /vendor/etc/avas/sound_type_3/pixel.wav 2>/dev/null
    umount -l /vendor/etc/avas/sound_type_3/bDM_01_01.wav 2>/dev/null
    setprop persist.avas.mode 2 2>/dev/null
    AVAS_PID=$(pidof vendor.molead.hardware.avas_service@1.0-service)
    [ -n "$AVAS_PID" ] && kill -9 "$AVAS_PID" 2>/dev/null
    echo "[SERVICE_MODE] AVAS restored to factory mode 2."

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
    if [ -f /data/local/tmp/avas_boot_new.sh ]; then
        sh /data/local/tmp/avas_boot_new.sh 2>/dev/null &
        echo "[SERVICE_MODE] Custom AVAS restored."
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
