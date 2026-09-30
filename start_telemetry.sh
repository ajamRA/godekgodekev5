#!/system/bin/sh
# Telemetry-only daemon launcher — single instance via PID file, clean stop of stale PIDs.
DIR="/data/local/window"
JAR="$DIR/telemetry_daemon.jar"
LOG="$DIR/telemetry_daemon.log"
PIDFILE="$DIR/telemetry_daemon.pid"

if [ -f "$PIDFILE" ]; then
    OLD_PID=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo "$(date) Stopping existing telemetry daemon PID $OLD_PID..."
        kill "$OLD_PID" 2>/dev/null
        sleep 1
    fi
    rm -f "$PIDFILE"
fi

# Belt & braces: kill any stray instance by class name.
pkill -f "com.emas.telemetry.TelemetryDaemon" 2>/dev/null

if [ ! -f "$JAR" ]; then
    echo "$(date) ERROR: $JAR missing; not starting." 
    exit 1
fi

echo "$(date) Starting TelemetryDaemon..."
export CLASSPATH="$JAR"
nohup app_process /data/local/window com.emas.telemetry.TelemetryDaemon > "$LOG" 2>&1 &
NEW_PID=$!
echo $NEW_PID > "$PIDFILE"
echo "$(date) TelemetryDaemon started with PID $NEW_PID. Log: $LOG"
sh /data/local/window/start_megaphone.sh &
sh /data/local/window/start_pressure_relief.sh &
