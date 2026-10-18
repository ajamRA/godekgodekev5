#!/system/bin/sh
# ==========================================================
# Proton e.MAS 5 — MOD SERVIS PROTON (STEALTH / FACTORY STOCK)
# Toggle / Enable / Disable all custom mods & third-party apps
# ==========================================================

FLAG="/data/local/window/SERVICE_MODE_ACTIVE"
STOCK_LOCK="/data/local/window/locksounds/lock_original.wav"
ACTIVE_LOCK="/data/local/window/locksounds/lock_active.wav"
# Sumber pemulihan pilihan pengguna; dibaca daripada fail pilihan jika ada.
LOCK_SEL="/data/local/window/locksounds/lock_selection.properties"
TARGET_LOCK="/sdcard/V3_Vehicle_Locked_Single.wav"
CHIME="/data/local/window/mode_chime.wav"

APPS="org.smarttube.stable com.netflix.mediaclient com.noor.thenoorcar com.vivaldi.browser.automotive me.zhanghai.android.files com.malaysia.syok com.thisisaim.hotfm com.cybercat.acbridge com.retroarch.aarch64"

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
    # Tulis KEDUA-DUA bendera. /system/bin/avas_boot.sh hanya menyemak
    # .service_mode; resume_avas_on_boot.sh menyemak kedua-duanya.
    # Tanpa .service_mode, daemon AVAS kustom akan dihidupkan semula pada boot.
    touch "$FLAG"
    touch /data/local/window/.service_mode

    # 1. Matikan SEMUA daemon kustom.
    #    Urutan penting: bunuh SKRIP PELANCAR dahulu. Skrip seperti
    #    start_salam.sh kekal berjalan dan akan menghidupkan semula anaknya
    #    jika anak dibunuh dahulu. Kemudian bunuh daemon itu sendiri.
    pkill -f "start_salam.sh" 2>/dev/null
    pkill -f "start_door_chime.sh" 2>/dev/null
    pkill -f "start_pressure_relief.sh" 2>/dev/null
    pkill -f "start_megaphone.sh" 2>/dev/null
    pkill -f "start_telemetry.sh" 2>/dev/null
    sleep 0.5
    pkill -f "com.emas.window.DoorPressureReliefDaemon" 2>/dev/null
    pkill -f "com.emas.megaphone.MegaphoneDaemon" 2>/dev/null
    pkill -f "com.emas.telemetry.TelemetryDaemon" 2>/dev/null
    pkill -f "DoorChime" 2>/dev/null
    pkill -f "SalamBoot" 2>/dev/null
    sleep 1
    # Pusingan kedua: tangkap yang bangkit semula atau terlepas
    pkill -f "start_salam.sh" 2>/dev/null
    pkill -f "com.emas.window.DoorPressureReliefDaemon" 2>/dev/null
    pkill -f "com.emas.megaphone.MegaphoneDaemon" 2>/dev/null
    pkill -f "com.emas.telemetry.TelemetryDaemon" 2>/dev/null
    pkill -f "DoorChime" 2>/dev/null
    pkill -f "SalamBoot" 2>/dev/null
    rm -f /data/local/window/salam/salam.pid 2>/dev/null
    echo "[SERVICE_MODE] Custom daemons and launchers stopped (double pass)."

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

    # 4b. SteeringModeDaemon sengaja DIKEKALKAN — ia mengesan butang
    #     Volume Down 5s untuk toggle Mod Servis.

    # 5b. Lumpuhkan aplikasi DAHULU supaya proses dilepaskan, kemudian
    #     batal bind mount. Jika umount dibuat dahulu, aplikasi yang masih
    #     berjalan memegang fail dan umount gagal secara senyap.
    for pkg in $APPS; do
        am force-stop "$pkg" 2>/dev/null
        pm disable-user --user 0 "$pkg" 2>/dev/null
    done
    echo "[SERVICE_MODE] Third-party apps disabled and hidden from app drawer."

    # 5c. Batal bind mount slot aplikasi supaya APK asal kilang kembali terbaca.
    #     Laluan custom disimpan; Stealth off akan mount semula.
    #     pm disable-user adalah tidak segerak — proses mungkin masih memegang
    #     fail. Cuba beberapa kali dengan jeda.
    sleep 1
    _try=0
    while [ $_try -lt 4 ]; do
        umount -l /system/app/ttincar_tiktok_ACO 2>/dev/null
        umount -l /system/app/JOOXMusic_ACO 2>/dev/null
        umount -l /system/app/Settings_MOLEAD/Settings_MOLEAD.apk 2>/dev/null
        sleep 1
        _left=$(mount | grep -cE 'ttincar|JOOX')
        [ "$_left" -eq 0 ] && break
        _try=$((_try + 1))
    done
    echo "[SERVICE_MODE] App slot bind mounts released (attempts: $((_try + 1)), remaining: ${_left:-0})."

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
    rm -f /data/local/window/.service_mode 2>/dev/null

    # 1. Hidupkan semula semua apps
    for pkg in $APPS; do
        pm enable "$pkg" 2>/dev/null
    done
    echo "[SERVICE_MODE] Apps restored and visible in app drawer."

    # 1a. Pasang semula bind mount slot aplikasi (dibalikkan semasa 'on')
    if [ -d /data/local/custom_apps/SmartTube ]; then
        mount -o bind /data/local/custom_apps/SmartTube /system/app/ttincar_tiktok_ACO 2>/dev/null
    fi
    if [ -d /data/local/custom_apps/Netflix ]; then
        mount -o bind /data/local/custom_apps/Netflix /system/app/JOOXMusic_ACO 2>/dev/null
    fi
    if [ -f /data/local/avas/Settings_MOLEAD_v8.apk ]; then
        mount -o bind /data/local/avas/Settings_MOLEAD_v8.apk /system/app/Settings_MOLEAD/Settings_MOLEAD.apk 2>/dev/null
    fi
    echo "[SERVICE_MODE] App slot bind mounts restored."

    # 1b. Senyapkan bendera lama yang digunakan oleh sc_stealth_mode.ps1
    #     supaya skrip boot tidak menyangka Stealth masih aktif.
    rm -f /data/local/window/.service_mode 2>/dev/null

    # 1c. Mulakan semula TelemetryDaemon — EmasEnergy memerlukan port 8888.
    #     Tanpa ini, kokpit kembali tetapi semua bacaan kosong.
    if [ -f /data/local/window/start_telemetry.sh ]; then
        sh /data/local/window/start_telemetry.sh 2>/dev/null &
        echo "[SERVICE_MODE] Telemetry daemon restarted."
    fi

    # 1d. Mulakan semula MegaphoneDaemon
    if [ -f /data/local/window/start_megaphone.sh ]; then
        sh /data/local/window/start_megaphone.sh 2>/dev/null &
        echo "[SERVICE_MODE] Megaphone daemon restarted."
    fi

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
