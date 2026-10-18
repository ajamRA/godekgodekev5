#!/system/bin/sh
# ==============================================================================
# boot_hook.sh — Proton e.MAS 5 (IHU801P)
#
# Dipanggil SEBELUM aplikasi dimuatkan (post-fs-data) melalui satu baris
# dalam /system/etc/init/zzz_custom_apps.rc:
#
#   exec u:r:su:s0 root root -- /system/bin/timeout 25 /system/bin/sh /data/local/window/boot_hook.sh
#
# Tanggungjawab:
#   1. Semak fail bendera Mod Servis
#   2. Jika Stealth AKTIF  -> jangan mount, jangan start daemon
#   3. Jika Stealth TIDAK  -> mount slot, kunci RetroArch, start daemon
#   4. Start SteeringModeDaemon SENTIASA (diperlukan untuk toggle Volume Down)
#   5. Tulis log ke /data/local/window/boot_hook.log
#
# Guna:
#   sh boot_hook.sh --dry-run     tunjuk apa yang AKAN dibuat, tiada perubahan
#   sh boot_hook.sh --status      tunjuk keadaan sekarang
#   sh boot_hook.sh               jalankan sebenar
#
# KESELAMATAN: skrip ini TIDAK BOLEH hang. Semua panggilan daemon guna &,
# semua mount dihadkan, dan setiap blok dibalut supaya kegagalan tidak
# menghentikan boot.
# ==============================================================================

FLAG_A=/data/local/window/SERVICE_MODE_ACTIVE
FLAG_B=/data/local/window/.service_mode
LOG=/data/local/window/boot_hook.log
DONE=/data/local/window/boot_hook.done

DRY=0
case "$1" in
    --dry-run) DRY=1 ;;
    --status)
        echo "flag A : $([ -f "$FLAG_A" ] && echo PRESENT || echo absent)"
        echo "flag B : $([ -f "$FLAG_B" ] && echo PRESENT || echo absent)"
        [ -f "$FLAG_A" ] || [ -f "$FLAG_B" ] && echo "state  : STEALTH" || echo "state  : NORMAL"
        echo -n "steering: "
        pgrep -f "com.emas.steering.SteeringModeDaemon" >/dev/null 2>&1 && echo RUNNING || echo STOPPED
        exit 0
        ;;
esac

# --- log ---
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"
    [ "$DRY" = "1" ] && echo "$*"
}

# Rotate: simpan hanya boot terakhir
if [ "$DRY" = "0" ]; then
    echo "=== boot_hook start $(date) ===" > "$LOG"
fi

STEALTH=0
[ -f "$FLAG_A" ] && STEALTH=1
[ -f "$FLAG_B" ] && STEALTH=1

log "stealth=$STEALTH dry=$DRY"

# ==============================================================================
# 1. MOUNT SLOT APLIKASI — hanya jika bukan Stealth
# ==============================================================================
if [ "$STEALTH" = "0" ]; then
    for pair in \
        "/data/local/custom_apps/SmartTube:/system/app/ttincar_tiktok_ACO" \
        "/data/local/custom_apps/Netflix:/system/app/JOOXMusic_ACO"
    do
        SRC="${pair%%:*}"
        TGT="${pair##*:}"
        if [ -d "$SRC" ]; then
            if [ "$DRY" = "1" ]; then
                log "WOULD MOUNT $SRC -> $TGT"
            else
                mount none "$SRC" "$TGT" bind rec 2>>"$LOG" \
                    && log "mount OK $TGT" \
                    || log "mount FAIL $TGT"
            fi
        else
            log "skip (tiada sumber): $SRC"
        fi
    done

    # Settings_MOLEAD — fail tunggal
    if [ -f /data/local/avas/Settings_MOLEAD_v8.apk ]; then
        if [ "$DRY" = "1" ]; then
            log "WOULD MOUNT Settings_MOLEAD"
        else
            mount none /data/local/avas/Settings_MOLEAD_v8.apk \
                /system/app/Settings_MOLEAD/Settings_MOLEAD.apk bind rec 2>>"$LOG" \
                && log "mount OK Settings_MOLEAD" \
                || log "mount FAIL Settings_MOLEAD"
        fi
    fi
else
    log "STEALTH: skip semua mount slot aplikasi"
fi

# ==============================================================================
# 2. RETROARCH CONFIG LOCK
# ==============================================================================
if [ "$STEALTH" = "0" ]; then
    if [ -f /data/local/retroarch_golden.cfg ] && [ -d /data/data/com.retroarch.aarch64 ]; then
        if [ "$DRY" = "1" ]; then
            log "WOULD install retroarch.cfg (0444, u0_a90)"
        else
            cp /data/local/retroarch_golden.cfg \
               /data/data/com.retroarch.aarch64/retroarch.cfg 2>>"$LOG" \
                && chmod 0444 /data/data/com.retroarch.aarch64/retroarch.cfg 2>>"$LOG" \
                && chown u0_a90 u0_a90 /data/data/com.retroarch.aarch64/retroarch.cfg 2>>"$LOG" \
                && log "retroarch cfg OK" \
                || log "retroarch cfg FAIL"
        fi
    fi
else
    log "STEALTH: skip retroarch cfg"
fi

# ==============================================================================
# 3. STEERING DAEMON — SENTIASA dimulakan
#    Ini satu-satunya jalan untuk toggle Volume Down. Tanpa ia, Mod Servis
#    tidak boleh dimatikan dari dalam kereta.
# ==============================================================================
if [ -f /data/local/window/start_steering_daemon.sh ]; then
    if [ "$DRY" = "1" ]; then
        log "WOULD start SteeringModeDaemon"
    else
        pgrep -f "com.emas.steering.SteeringModeDaemon" >/dev/null 2>&1 \
            || sh /data/local/window/start_steering_daemon.sh >>"$LOG" 2>&1
        log "steering daemon: $(pgrep -f 'com.emas.steering.SteeringModeDaemon' >/dev/null 2>&1 && echo RUNNING || echo FAILED)"
    fi
else
    log "AMARAN: start_steering_daemon.sh tiada"
fi

# ==============================================================================
# 4. DAEMON KUSTOM — hanya jika bukan Stealth
# ==============================================================================
if [ "$STEALTH" = "0" ]; then
    for s in start_telemetry.sh start_megaphone.sh start_door_chime.sh start_salam.sh; do
        if [ -f "/data/local/window/$s" ]; then
            if [ "$DRY" = "1" ]; then
                log "WOULD start $s"
            else
                sh "/data/local/window/$s" >>"$LOG" 2>&1 &
                log "started $s"
            fi
        fi
    done
else
    log "STEALTH: skip semua daemon kustom"
fi

# ==============================================================================
# 5. PENANDA
# ==============================================================================
if [ "$DRY" = "0" ]; then
    echo "$(date) selesai" > "$DONE"
    log "=== boot_hook end ==="
fi

# SELALU exit 0. Kegagalan tidak boleh menghentikan boot.
exit 0
