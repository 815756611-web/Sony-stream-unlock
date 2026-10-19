#!/system/bin/sh
# Disable USB runtime autosuspend for the Xperia Stream devices.
#
# The Stream's 3.5 mm jack is a USB audio function.  If the kernel runtime-PMs
# the hub / MCU while idle, the audio device can disappear ("jumps off").
# Writing "on" to power/control keeps those devices powered.
#
# Started in the background by service.sh; harmless when the device is absent.

MODDIR=$(cd "${0%/*}/.." 2>/dev/null && pwd)
MODDIR=${MODDIR:-/data/adb/modules/stream-fanctl}
LOG="$MODDIR/usb-keepawake.log"

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null; }

log "watchdog started"
while true; do
    for d in /sys/bus/usb/devices/*; do
        [ -f "$d/idVendor" ] || continue
        v=$(cat "$d/idVendor" 2>/dev/null)
        case "$v" in
            054c|0424)
                ctl="$d/power/control"
                [ -f "$ctl" ] || continue
                cur=$(cat "$ctl" 2>/dev/null)
                if [ "$cur" != "on" ]; then
                    if echo on > "$ctl" 2>/dev/null; then
                        log "$(basename "$d") power/control on (was $cur)"
                    else
                        log "$(basename "$d") power/control write denied"
                    fi
                fi
                ;;
        esac
    done
    sleep 10
done
