#!/system/bin/sh
DIR="/data/local/window/gear_audio"
JAR="$DIR/DoorDoaPoc.jar"
WAV="$DIR/door_salam.wav"
LOG="$DIR/door_salam.log"
PIDFILE="$DIR/door_salam.pid"

if [ -f "$PIDFILE" ]; then
    OLD_PID=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "$(date) Stopping existing DoorSalam PID $OLD_PID..."
        kill "$OLD_PID" 2>/dev/null
        sleep 1
    fi
    rm -f "$PIDFILE"
fi

pkill -f "DoorDoaPoc" 2>/dev/null

if [ ! -f "$JAR" ] || [ ! -f "$WAV" ]; then
    echo "$(date) ERROR: $JAR or $WAV missing; not starting."
    exit 1
fi

echo "$(date) Starting DoorSalam (one-shot per reboot)..."
export CLASSPATH="$JAR"
nohup app_process /data/local/window/gear_audio DoorDoaPoc --wav "$WAV" > "$LOG" 2>&1 &
NEW_PID=$!
echo $NEW_PID > "$PIDFILE"
echo "$(date) DoorSalam started with PID $NEW_PID. Log: $LOG"
