#!/system/bin/sh
DIR="/data/local/window"
JAR="$DIR/door_pressure_relief.jar"
LOG="$DIR/pressure_relief.log"
PIDFILE="$DIR/pressure_relief.pid"

if [ -f "$PIDFILE" ]; then
    OLD_PID=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "$(date) Stopping existing daemon PID $OLD_PID..."
        kill "$OLD_PID" 2>/dev/null
        sleep 1
    fi
    rm -f "$PIDFILE"
fi

pkill -f "com.emas.window.DoorPressureReliefDaemon" 2>/dev/null

if [ ! -f "$JAR" ]; then
    echo "$(date) ERROR: $JAR missing; not starting."
    exit 1
fi

echo "$(date) Starting DoorPressureReliefDaemon..."
export CLASSPATH="$JAR"
nohup app_process /data/local/window com.emas.window.DoorPressureReliefDaemon > "$LOG" 2>&1 &
NEW_PID=$!
echo $NEW_PID > "$PIDFILE"
echo "$(date) Daemon started with PID $NEW_PID. Logging to $LOG"