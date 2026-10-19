#!/system/bin/sh
# Xperia Stream USB link monitor (root)
#
# Usage: sh usb-monitor.sh [seconds]      default 300
#
# Logs every USB device-list change together with:
#   - power_supply online / voltage / current / input_current_limit
#   - the last 25 kernel lines (dmesg), which show over-current / resets
#
# Writes /sdcard/Download/usb_monitor_<timestamp>.txt

DUR=${1:-300}
OUT="/sdcard/Download/usb_monitor_$(date '+%Y%m%d_%H%M%S').txt"
[ -d /sdcard/Download ] || OUT="/data/local/tmp/usb_monitor_$(date '+%Y%m%d_%H%M%S').txt"

usb_list() {
    for d in /sys/bus/usb/devices/*; do
        [ -f "$d/idVendor" ] || continue
        printf '%s=%s:%s ' "$(basename "$d")" \
            "$(cat "$d/idVendor" 2>/dev/null)" "$(cat "$d/idProduct" 2>/dev/null)"
    done
}

power_snap() {
    for p in /sys/class/power_supply/*; do
        [ -d "$p" ] || continue
        echo "    $(basename "$p"): online=$(cat "$p/online" 2>/dev/null)" \
             "status=$(cat "$p/status" 2>/dev/null)" \
             "v=$(cat "$p/voltage_now" 2>/dev/null)" \
             "i=$(cat "$p/current_now" 2>/dev/null)" \
             "limit=$(cat "$p/input_current_limit" 2>/dev/null)"
    done
}

{
    echo "=== Xperia Stream USB link monitor $(date) ==="
    echo "duration: ${DUR}s"
    echo "--- initial devices ---"
    usb_list
    echo
    echo "--- initial power ---"
    power_snap
    echo
} >> "$OUT" 2>&1

prev=$(usb_list)
i=0
while [ "$i" -lt "$DUR" ]; do
    sleep 1
    i=$((i + 1))
    cur=$(usb_list)
    if [ "$cur" != "$prev" ]; then
        {
            echo "[$(date '+%H:%M:%S')] USB CHANGE"
            echo "  before: $prev"
            echo "  after : $cur"
            power_snap
            echo "  --- dmesg (last 25) ---"
            dmesg 2>/dev/null | tail -25
            echo
        } >> "$OUT" 2>&1
        prev=$cur
    fi
done

{
    echo "=== end $(date) ==="
    echo "--- dmesg usb/over-current (last 80) ---"
    dmesg 2>/dev/null | grep -i -E "usb|over-current|overcurrent|reset|disconnect|xhci|hub" | tail -80
} >> "$OUT" 2>&1

echo "report: $OUT"
