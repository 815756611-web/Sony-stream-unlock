#!/system/bin/sh
# Xperia Stream 四合一检测 - installer

ui_print "- Xperia Stream 四合一检测"
ui_print "  充电 / 3.5mm / HDMI / 网卡"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm 0 0 0755 "$MODPATH/action.sh"
set_perm_recursive "$MODPATH/tools" 0 0 0755 0755

ui_print "- 管理器「执行」按钮 = 跑一次汇总检测"
ui_print "- 完整报告: su -c 'sh /data/adb/modules/stream-diag/tools/stream-diag.sh'"
