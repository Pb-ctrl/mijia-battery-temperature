#!/system/bin/sh
[ "$KSU" = "true" ] || abort "This package requires KernelSU."
MI_VERSION=$(/system/bin/dumpsys package com.xiaomi.smarthome | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)
SC_VERSION=$(/system/bin/dumpsys package com.miui.securitycenter | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)
[ "$MI_VERSION" = "11011845" ] || abort "Unsupported Xiaomi Home version. Required: 11011845."
[ "$SC_VERSION" = "40001381" ] || abort "Unsupported Security Center version. Required: 40001381."
ui_print "Battery temperature extension: experimental build"
ui_print "Install the companion APK, then enable its two recommended LSPosed scopes."
ui_print "No framework is installed automatically. Reboot after setup."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/power-events" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
