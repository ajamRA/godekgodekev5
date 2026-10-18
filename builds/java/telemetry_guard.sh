#!/system/bin/sh
# ==============================================================================
# telemetry_guard.sh — pembalut untuk zzz_telemetry_boot.rc
#
# Init tidak menghurai `sh -c "..."` dengan baik dalam definisi service,
# jadi logik semakan bendera dipindahkan ke sini.
# ==============================================================================

FLAG_A=/data/local/window/SERVICE_MODE_ACTIVE
FLAG_B=/data/local/window/.service_mode
LOG=/data/local/window/telemetry_guard.log

if [ -f "$FLAG_A" ] || [ -f "$FLAG_B" ]; then
    echo "$(date) STEALTH active: telemetry daemon NOT started" >> "$LOG"
    exit 0
fi

if [ ! -f /data/local/window/start_telemetry.sh ]; then
    echo "$(date) start_telemetry.sh tiada" >> "$LOG"
    exit 0
fi

echo "$(date) starting telemetry daemon" >> "$LOG"
sh /data/local/window/start_telemetry.sh >> "$LOG" 2>&1
exit 0
