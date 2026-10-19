#!/system/bin/sh
# Xperia Stream audio link monitor  (shipped with the stream-fanctl module)
#
# Usage (root):
#   sh /data/adb/modules/stream-fanctl/tools/audio-diag.sh [seconds]
#
# Watches for:
#   - USB device tree changes (re-enumeration of hub / LAN / MCU / UAC)
#   - ALSA card count changes (USB audio card appearing / disappearing)
#   - Android audio route changes
#   - matching kernel (dmesg) and logcat messages
#
# Writes /sdcard/Download/stream_audio_diag_<timestamp>.txt

DUR=${1:-180}
OUT="/sdcard/Download/stream_audio_diag_$(date '+%Y%m%d_%H%M%S').txt"
[ -d /sdcard/Download ] || OUT="/data/local/tmp/stream_audio_diag_$(date '+%Y%m%d_%H%M%S').txt"

usb_snapshot() {
    for d in /sys/bus/usb/devices/*; do
        [ -f "$d/idVendor" ] || continue
        printf '%s=%s:%s ' "$(basename "$d")" \
            "$(cat "$d/idVendor" 2>/dev/null)" "$(cat "$d/idProduct" 2>/dev/null)"
    done
}

card_count() {
    grep -c '^[ ]*[0-9]' /proc/asound/cards 2>/dev/null
}

route_line() {
    dumpsys audio 2>/dev/null | grep -i "Devices:" | head -1
}

{
    echo "=== Xperia Stream audio diag $(date) ==="
    echo "duration: ${DUR}s"
    echo
    echo "--- USB tree (with interface classes) ---"
    for d in /sys/bus/usb/devices/*; do
        [ -f "$d/idVendor" ] || continue
        echo "$(basename "$d")  $(cat "$d/idVendor"):$(cat "$d/idProduct")  $(cat "$d/product" 2>/dev/null)"
        for i in "$d"/*/bInterfaceClass; do
            [ -f "$i" ] || continue
            echo "    if=$(basename "$(dirname "$i")") class=$(cat "$i") sub=$(cat "$(dirname "$i")/bInterfaceSubClass" 2>/dev/null)"
        done
    done
    echo
    echo "--- USB audio driver bindings ---"
    ls /sys/bus/usb/drivers/ 2>/dev/null | grep -i -E "audio|snd"
    ls -l /sys/bus/usb/drivers/snd-usb-audio/ 2>/dev/null | head -10
    echo
    echo "--- /proc/asound/cards ---"
    cat /proc/asound/cards 2>&1
    echo "--- /proc/asound/pcm ---"
    cat /proc/asound/pcm 2>&1
    echo
    echo "--- audio routes ---"
    dumpsys audio 2>/dev/null | grep -i -E "Devices:|USB|HDMI|headset|speaker" | head -20
    echo
    echo "--- usb framework ---"
    dumpsys usb 2>/dev/null | grep -i -E "device|port|role|speed|connected" | head -20
    echo
    echo "--- monitoring ---"
} >> "$OUT" 2>&1

prev_usb=$(usb_snapshot)
prev_cards=$(card_count)
prev_route=$(route_line)
changes=0
i=0

while [ "$i" -lt "$DUR" ]; do
    sleep 2
    i=$((i + 2))

    usb=$(usb_snapshot)
    if [ "$usb" != "$prev_usb" ]; then
        changes=$((changes + 1))
        {
            echo "[$(date '+%H:%M:%S')] USB CHANGED"
            echo "  before: $prev_usb"
            echo "  after : $usb"
            dmesg 2>/dev/null | grep -i -E "usb|audio|snd|xhci" | tail -8
        } >> "$OUT" 2>&1
        prev_usb=$usb
    fi

    cards=$(card_count)
    if [ "$cards" != "$prev_cards" ]; then
        changes=$((changes + 1))
        {
            echo "[$(date '+%H:%M:%S')] ALSA CARD COUNT $prev_cards -> $cards"
            cat /proc/asound/cards 2>&1
        } >> "$OUT" 2>&1
        prev_cards=$cards
    fi

    route=$(route_line)
    if [ "$route" != "$prev_route" ]; then
        changes=$((changes + 1))
        {
            echo "[$(date '+%H:%M:%S')] AUDIO ROUTE CHANGED"
            echo "  before: $prev_route"
            echo "  after : $route"
        } >> "$OUT" 2>&1
        prev_route=$route
    fi
done

{
    echo
    echo "=== summary: $changes change(s) in ${DUR}s ==="
    echo
    echo "--- kernel usb/audio messages (last 60) ---"
    dmesg 2>/dev/null | grep -i -E "usb|audio|snd|xhci|over-current|disconnect" | tail -60
    echo
    echo "--- logcat usb/audio (last 120) ---"
    logcat -d -b all -t 600 2>/dev/null | grep -i -E "usb|audio|snd|headset|uac" | tail -120
} >> "$OUT" 2>&1

echo "report: $OUT"
