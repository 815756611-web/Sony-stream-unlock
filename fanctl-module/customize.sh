#!/system/bin/sh
# Install the standalone Stream fan controller as a normal app.
# The APK lives inside the module zip; the system is running while the module
# is installed, so pm install works right away.

ui_print "- 安装 Stream 风扇控制 APK"

# Remove any previous build first: the signing key was fixed after v1.0, so an
# update over an older signature would fail with UPDATE_INCOMPATIBLE.
old=$(pm uninstall com.xperiastream.fanctl 2>&1)
case "$old" in
    *Success*) ui_print "  已卸载旧版本" ;;
esac

if [ -f "$MODPATH/StreamFanCtl.apk" ]; then
    out=$(pm install -r -g "$MODPATH/StreamFanCtl.apk" 2>&1)
    ui_print "  $out"
    case "$out" in
        *Success*)
            ui_print "- 安装成功，重启后可在桌面找到「Stream 风扇控制」"
            ;;
        *)
            ui_print "! 自动安装失败，请手动安装:"
            ui_print "  su -c 'pm uninstall com.xperiastream.fanctl'"
            ui_print "  su -c 'pm install -r /data/adb/modules/stream-fanctl/StreamFanCtl.apk'"
            ;;
    esac
else
    ui_print "! 模块内缺少 StreamFanCtl.apk"
fi

set_perm 0 0 0755 "$MODPATH/uninstall.sh"
set_perm 0 0 0755 "$MODPATH/service.sh"
if [ -d "$MODPATH/tools" ]; then
    set_perm_recursive "$MODPATH/tools" 0 0 0755 0755
fi
