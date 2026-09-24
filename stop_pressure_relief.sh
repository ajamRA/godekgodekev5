#!/system/bin/sh
PIDFILE="/data/local/tmp/pressure_relief.pid"
if [ -f "$PIDFILE" ]; then
    PID=$(cat "$PIDFILE")
    kill "$PID" 2>/dev/null
    rm -f "$PIDFILE"
    echo "Stopped daemon PID $PID"
fi
pkill -f "DoorPressureReliefDaemon" 2>/dev/null
echo "Door Pressure Relief Daemon stopped."
