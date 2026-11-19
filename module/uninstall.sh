#!/system/bin/sh
/system/bin/settings delete global mijia_bt_sample >/dev/null 2>&1
/system/bin/settings put global mijia_bt_heartbeat 0 >/dev/null 2>&1
/system/bin/settings put global mijia_bt_source_verified 0 >/dev/null 2>&1
/system/bin/settings delete global mijia_bt_source >/dev/null 2>&1
/system/bin/settings delete global mijia_bt_temp >/dev/null 2>&1
