#!/system/bin/sh
# Xperia Stream Fan Control module - late_start service
# Starts the USB keep-awake watchdog (disables runtime autosuspend for the
# Stream's hub / MCU so the USB audio function does not disappear when idle).

MODDIR=${0%/*}
SCRIPT="$MODDIR/tools/usb-keepawake.sh"
PIDFILE="$MODDIR/.keepawake.pid"

[ -f "$SCRIPT" ] || exit 0
chmod 0755 "$SCRIPT" 2>/dev/null

if [ -f "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
        exit 0
    fi
fi

sh "$SCRIPT" >/dev/null 2>&1 &
echo $! > "$PIDFILE"
