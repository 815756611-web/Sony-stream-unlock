#!/system/bin/sh
# Xperia Stream 四合一检测：充电 / 3.5mm 音频 / HDMI / 网卡
#
# 用法:
#   sh stream-diag.sh              完整报告（每项细节 + 汇总）
#   sh stream-diag.sh --brief      只输出汇总（KernelSU 模块「执行」按钮用）
#   sh stream-diag.sh --only charge|audio|hdmi|eth
#
# 报告保存到 /sdcard/Download/stream_diag_<时间>.txt
# 同时写入 logcat: logcat -s XperiaStreamDiag

OUT="/sdcard/Download/stream_diag_$(date '+%Y%m%d_%H%M%S').txt"
[ -d /sdcard/Download ] || OUT="/data/local/tmp/stream_diag_$(date '+%Y%m%d_%H%M%S').txt"

BRIEF=0
ONLY=""
while [ $# -gt 0 ]; do
    case "$1" in
        --brief) BRIEF=1 ;;
        --only) ONLY="$2"; shift ;;
    esac
    shift
done

R_CHARGE="SKIP"; S_CHARGE="-"
R_AUDIO="SKIP";  S_AUDIO="-"
R_HDMI="SKIP";   S_HDMI="-"
R_ETH="SKIP";    S_ETH="-"

say() { echo "$*"; echo "$*" >> "$OUT"; }
detail() { [ "$BRIEF" = "1" ] && return 0; echo "$*"; echo "$*" >> "$OUT"; }
logcat_line() { log -t XperiaStreamDiag -p i "$*" 2>/dev/null; }

have() { command -v "$1" >/dev/null 2>&1; }

# ---------------------------------------------------------------- 充电

check_charge() {
    detail ""
    detail "===== 1. 充电 ====="
    PS=/sys/class/power_supply
    if [ ! -d "$PS" ]; then
        R_CHARGE="FAIL"; S_CHARGE="没有 /sys/class/power_supply"
        return
    fi

    # 找到 USB 类型的供电节点（名字可能是 usb / charger / pc_port）
    USB_DIR=""
    for d in "$PS"/*; do
        [ -d "$d" ] || continue
        t=$(cat "$d/type" 2>/dev/null)
        if [ "$t" = "USB" ] || [ "$t" = "Mains" ]; then
            USB_DIR="$d"
            [ "$t" = "USB" ] && break
        fi
    done

    u_online=""; u_v=""; u_i=""
    if [ -n "$USB_DIR" ]; then
        u_online=$(cat "$USB_DIR/online" 2>/dev/null)
        u_v=$(cat "$USB_DIR/voltage_now" 2>/dev/null)
        u_i=$(cat "$USB_DIR/current_now" 2>/dev/null)
        detail "  供电节点: $(basename "$USB_DIR")  online=$u_online"
        detail "  输入电压: ${u_v:-?} uV  输入电流: ${u_i:-?} uA"
    else
        detail "  未找到 USB 供电节点"
    fi

    b_status=$(cat "$PS/battery/status" 2>/dev/null)
    b_cap=$(cat "$PS/battery/capacity" 2>/dev/null)
    b_v=$(cat "$PS/battery/voltage_now" 2>/dev/null)
    b_i=$(cat "$PS/battery/current_now" 2>/dev/null)
    b_temp=$(cat "$PS/battery/temperature" 2>/dev/null)

    detail "  电池: status=$b_status capacity=${b_cap}% voltage=${b_v:-?} uV current=${b_i:-?} uA temp=${b_temp:-?}"

    w=""
    if [ -n "$u_v" ] && [ -n "$u_i" ]; then
        w=$(awk -v v="$u_v" -v i="$u_i" 'BEGIN{printf "%.2f", (v*i)/1000000000000}')
        detail "  输入功率: ${w} W"
    fi

    bw=""
    if [ -n "$b_v" ] && [ -n "$b_i" ]; then
        bw=$(awk -v v="$b_v" -v i="$b_i" 'BEGIN{printf "%.2f", (v*i)/1000000000000}')
        detail "  电池功率: ${bw} W (正=充电 负=放电)"
    fi

    # PD/端口信息
    if [ "$BRIEF" = "0" ]; then
        detail "  --- USB 端口 ---"
        dumpsys usb 2>/dev/null | grep -i -E "role|mode|connected|speed" | head -6 | while read -r l; do
            detail "  $l"
        done
    fi

    if [ "$u_online" = "1" ] && [ -n "$w" ] && awk -v w="$w" 'BEGIN{exit !(w>=1)}'; then
        R_CHARGE="OK"; S_CHARGE="${w} W · 电池 ${b_cap}% ${b_status}"
    elif [ "$u_online" = "1" ]; then
        R_CHARGE="WARN"; S_CHARGE="已连接但功率低 (${w:-?} W)"
    else
        R_CHARGE="FAIL"; S_CHARGE="未检测到充电输入"
    fi
}

# ---------------------------------------------------------------- 3.5mm

check_audio() {
    detail ""
    detail "===== 2. 3.5mm 音频 ====="
    cards=$(cat /proc/asound/cards 2>/dev/null)
    detail "  /proc/asound/cards:"
    echo "$cards" | while read -r l; do
        [ -n "$l" ] && detail "  $l"
    done

    usb_card=$(echo "$cards" | grep -i "USB-Audio" | head -1)
    drv=$(ls /sys/bus/usb/drivers/ 2>/dev/null | grep -i "snd-usb-audio")
    detail "  USB 声卡: ${usb_card:-未发现}"
    detail "  snd-usb-audio 驱动: ${drv:-未加载}"

    # 设备上的 audio class 接口
    aifs=""
    for i in /sys/bus/usb/devices/*/*/bInterfaceClass; do
        [ -f "$i" ] || continue
        [ "$(cat "$i" 2>/dev/null)" = "01" ] && aifs="$aifs $(dirname "$i")"
    done
    detail "  audio class 接口: ${aifs:-无}"

    routes=$(dumpsys audio 2>/dev/null | grep -i -E "USB_HEADSET|WIRED_HEADSET|USB_DEVICE|headset" | head -4)
    detail "  音频路由:"
    if [ -n "$routes" ]; then
        echo "$routes" | while read -r l; do
            detail "  $l"
        done
    else
        detail "  （没有 headset 相关路由）"
    fi

    if [ -n "$usb_card" ] || [ -n "$routes" ]; then
        R_AUDIO="OK"
        S_AUDIO="${usb_card:-headset 路由已存在}"
    else
        R_AUDIO="FAIL"
        S_AUDIO="未检测到 USB 声卡/耳机路由"
    fi
}

# ---------------------------------------------------------------- HDMI

check_hdmi() {
    detail ""
    detail "===== 3. HDMI / DP ====="
    conns=""
    for c in /sys/class/drm/card*-*; do
        [ -f "$c/status" ] || continue
        name=$(basename "$c")
        case "$name" in *DSI*) continue ;; esac
        st=$(cat "$c/status" 2>/dev/null)
        en=$(cat "$c/enabled" 2>/dev/null)
        pref=$(head -1 "$c/modes" 2>/dev/null)
        detail "  $name: status=$st enabled=${en:-?} preferred=${pref:-none}"
        [ "$st" = "connected" ] && conns="$conns $name"
    done

    line=$(dumpsys display 2>/dev/null | grep -i "DisplayDeviceInfo" | grep -i "external" | head -1)
    [ -z "$line" ] && line=$(dumpsys display 2>/dev/null | grep -i "external" | head -1)
    res=""; f=""
    if [ -n "$line" ]; then
        res=$(echo "$line" | grep -o "[0-9][0-9]* x [0-9][0-9]*" | head -1)
        tmp=$(echo "$line" | grep -o "[0-9][0-9.]* fps" | head -1)
        if [ -n "$tmp" ]; then
            f=${tmp% fps*}
        else
            f=$(echo "$line" | sed -n 's/.*renderFrameRate \([0-9.]*\).*/\1/p')
        fi
    fi

    if [ -n "$res" ]; then
        w=${res% x *}
        h=${res#* x }
        detail "  外接显示: ${w}x${h} @ ${f:-?} fps"
        if [ -n "$f" ]; then
            gbps=$(awk -v w="$w" -v h="$h" -v f="$f" 'BEGIN{printf "%.2f", w*h*f*24/1000000000}')
            detail "  像素率: $(awk -v w="$w" -v h="$h" -v f="$f" 'BEGIN{printf "%.1f", w*h*f/1000000}') MP/s"
            detail "  等效数据率(24bpp): ${gbps} Gbps"
            S_HDMI="${w}x${h}@${f} ≈${gbps} Gbps"
        else
            S_HDMI="${w}x${h}"
        fi
    else
        detail "  未发现活动的 EXTERNAL 显示"
    fi

    if [ -n "$conns" ] && [ -n "$res" ]; then
        R_HDMI="OK"
    elif [ -n "$conns" ]; then
        R_HDMI="WARN"; S_HDMI="接口已连接但无活动显示"
    else
        R_HDMI="FAIL"; S_HDMI="没有 connected 的 HDMI/DP 接口"
    fi
}

# ---------------------------------------------------------------- 网卡

check_eth() {
    detail ""
    detail "===== 4. 网卡 ====="
    found=""
    for i in /sys/class/net/*; do
        ifn=$(basename "$i")
        case "$ifn" in
            lo|wlan*|rmnet*|dummy*|sit*|ip6tnl*|tun*|p2p*|bond*|hwsim*) continue ;;
        esac
        carrier=$(cat "$i/carrier" 2>/dev/null)
        oper=$(cat "$i/operstate" 2>/dev/null)
        speed=$(cat "$i/speed" 2>/dev/null)
        mac=$(cat "$i/address" 2>/dev/null)
        detail "  $ifn: carrier=${carrier:-?} operstate=${oper:-?} speed=${speed:-?} mac=${mac:-?}"
        if [ "$carrier" = "1" ]; then
            found="$ifn"
        fi
    done

    if [ -z "$found" ]; then
        R_ETH="FAIL"; S_ETH="没有 carrier=1 的网卡（LAN 未连接）"
        return
    fi

    ifc="/sys/class/net/$found"
    speed=$(cat "$ifc/speed" 2>/dev/null)
    addr=$(ip -o -4 addr show dev "$found" 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="inet"){print $(i+1); exit}}')
    if [ -z "$addr" ] && have ifconfig; then
        addr=$(ifconfig "$found" 2>/dev/null | awk '/inet /{print $2; exit}')
    fi
    detail "  使用接口: $found  链路速率=${speed:-?} Mbps  IP=${addr:-无}"

    rx1=$(cat "$ifc/statistics/rx_bytes" 2>/dev/null)
    tx1=$(cat "$ifc/statistics/tx_bytes" 2>/dev/null)
    sleep 2
    rx2=$(cat "$ifc/statistics/rx_bytes" 2>/dev/null)
    tx2=$(cat "$ifc/statistics/tx_bytes" 2>/dev/null)

    rxm="?"; txm="?"
    if [ -n "$rx1" ] && [ -n "$rx2" ]; then
        rxm=$(awk -v a="$rx1" -v b="$rx2" 'BEGIN{printf "%.2f", (b-a)*8/2/1000000}')
        txm=$(awk -v a="$tx1" -v b="$tx2" 'BEGIN{printf "%.2f", (b-a)*8/2/1000000}')
        detail "  当前速率: 下行 ${rxm} Mbps / 上行 ${txm} Mbps"
    fi

    link="${speed}"
    [ -z "$link" ] || [ "$link" = "-1" ] || [ "$link" = "0" ] && link="usbnet"
    if [ -n "$addr" ]; then
        R_ETH="OK"; S_ETH="链路 ${link} · ↓${rxm} ↑${txm} Mbps · ${addr}"
    else
        R_ETH="WARN"; S_ETH="链路已连接但无 IP"
    fi
}

# ---------------------------------------------------------------- main

say "Xperia Stream 四合一检测  $(date '+%Y-%m-%d %H:%M:%S')"
say "报告文件: $OUT"

[ -z "$ONLY" ] || [ "$ONLY" = "charge" ] && check_charge
[ -z "$ONLY" ] || [ "$ONLY" = "audio" ]  && check_audio
[ -z "$ONLY" ] || [ "$ONLY" = "hdmi" ]   && check_hdmi
[ -z "$ONLY" ] || [ "$ONLY" = "eth" ]    && check_eth

say ""
say "===== 汇总 ====="
say "  充电   [$R_CHARGE] $S_CHARGE"
say "  3.5mm  [$R_AUDIO] $S_AUDIO"
say "  HDMI   [$R_HDMI] $S_HDMI"
say "  网卡   [$R_ETH] $S_ETH"
say ""
say "RESULT charge=$R_CHARGE audio=$R_AUDIO hdmi=$R_HDMI eth=$R_ETH"
say "报告: $OUT"

logcat_line "charge=$R_CHARGE ($S_CHARGE)"
logcat_line "audio=$R_AUDIO ($S_AUDIO)"
logcat_line "hdmi=$R_HDMI ($S_HDMI)"
logcat_line "eth=$R_ETH ($S_ETH)"
