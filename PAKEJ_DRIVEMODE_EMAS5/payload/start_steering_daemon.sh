#!/system/bin/sh
# Pelancar SteeringModeDaemon (Drive Mode + Regen + Stealth toggle)
# Diletakkan di /data/local/window/start_steering_daemon.sh

JAR="/data/local/window/steering_mode_controller.jar"
LOG="/data/local/window/steering.log"
PIDFILE="/data/local/window/steering.pid"

if [ -f "$PIDFILE" ]; then
    OLD_PID=$(cat "$PIDFILE")
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        kill "$OLD_PID" 2>/dev/null
        sleep 1
    fi
    rm -f "$PIDFILE"
fi

pkill -f "SteeringModeDaemon" 2>/dev/null

if [ ! -s "$JAR" ]; then
    echo "$(date) ERROR: $JAR missing/empty; daemon not started." >> "$LOG"
    exit 1
fi

export CLASSPATH="$JAR"
nohup app_process /data/local/window com.emas.steering.SteeringModeDaemon > "$LOG" 2>&1 &
NEW_PID=$!
echo $NEW_PID > "$PIDFILE"
echo "Steering Daemon started with PID $NEW_PID"
