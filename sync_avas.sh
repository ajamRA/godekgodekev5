#!/system/bin/sh
# AVAS Sync Daemon: Keeps IHU UI settings in sync with Molead AVAS HAL service

LOG=/data/local/avas/sync_avas.log
PIDFILE=/data/local/avas/sync_avas.pid

# Check and kill old instance
if [ -f "$PIDFILE" ]; then
    OLD_PID=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && [ -d "/proc/$OLD_PID" ]; then
        kill "$OLD_PID" 2>/dev/null
    fi
fi
echo $$ > "$PIDFILE"

echo "=== AVAS Sync Daemon Started: $(date) ===" > "$LOG"

LAST_MODE=""
LAST_SWITCH=""

while true; do
    MODE=$(getprop persist.sys.avas.mode)
    [ -z "$MODE" ] && MODE=1
    
    SWITCH=$(getprop persist.sys.avas.switch)
    [ -z "$SWITCH" ] && SWITCH=1
    
    if [ "$MODE" != "$LAST_MODE" ] || [ "$SWITCH" != "$LAST_SWITCH" ]; then
        echo "$(date): Mode changed: $LAST_MODE -> $MODE, Switch: $LAST_SWITCH -> $SWITCH" >> "$LOG"
        
        setprop persist.avas.mode "$MODE"
        setprop persist.avas.switch "$SWITCH"
        
        # 1. Official broadcast to Settings app
        if [ "$SWITCH" = "1" ]; then
            am broadcast -n ecarx.settings/.receiver.AvasSetReceiver --receiver-include-background --ei AVAS_SET_VALUE "$MODE" >> "$LOG" 2>&1
        else
            am broadcast -n ecarx.settings/.receiver.AvasSetReceiver --receiver-include-background --ei AVAS_SET_VALUE 0 >> "$LOG" 2>&1
        fi
        
        # 2. Restart HAL service to guarantee fresh ALSA and sound mode loading
        SVC_PID=$(pidof vendor.molead.hardware.avas_service@1.0-service)
        if [ -n "$SVC_PID" ]; then
            kill "$SVC_PID" 2>/dev/null
        fi
        
        LAST_MODE="$MODE"
        LAST_SWITCH="$SWITCH"
    fi
    
    sleep 2
done
