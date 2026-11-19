#!/system/bin/sh
# Strict reader for the inspected zorn/OS4 battery thermal endpoint.
# Never use framework broadcasts, dumpsys, spoof-module snapshots or charger sensors.
SOURCE_REASON=not_checked
SOURCE_PATH=
SOURCE_TEMP=

is_original_sysfs() {
    p=$(readlink -f "$1") || return 1
    case "$p" in /sys/devices/*) ;; *) return 1 ;; esac
    [ "$(stat -f -c %T "$p" 2>/dev/null)" = "sysfs" ] || return 1
    # Reject file or ancestor bind mounts, including bind mounts from another sysfs node.
    awk -v p="$p" '
        $5 != "/" && $5 != "/sys" && ($5 == p || index(p, $5 "/") == 1) { bad=1 }
        END { exit bad ? 1 : 0 }
    ' /proc/self/mountinfo || return 1
    return 0
}

read_physical_source() {
    SOURCE_REASON=unverified_source
    SOURCE_PATH=
    SOURCE_TEMP=
    [ "$(getprop ro.product.device)" = "zorn" ] || { SOURCE_REASON=unsupported_device; return 1; }
    [ "$(getprop ro.build.version.incremental)" = "OS4.0.3.0.XOKCNXM" ] || { SOURCE_REASON=unsupported_system; return 1; }
    zone=/sys/devices/virtual/thermal/thermal_zone75
    raw=/sys/class/power_supply/battery/temp

    # The existing spoof module (temp_charge_boost) bind-mounts thermal_zone75
    # but never the driver node power_supply/battery/temp. Confirm its config
    # first, then trust the driver reading only when the config is known.
    spoof=/data/adb/modules/temp_charge_boost
    profile=/data/adb/temp_charge_boost/profile.conf
    spoof_known=0
    if [ -d "$spoof" ]; then
        [ "$(sed -n 's/^versionCode=//p' "$spoof/module.prop")" = "149" ] || { SOURCE_REASON=spoof_version_unverified; return 1; }
        injection=$(sed -n 's/^node_fake_temp=//p' "$profile")
        [ "$injection" = "/sys/class/thermal/thermal_zone0/emul_temp" ] || { SOURCE_REASON=spoof_injection_unverified; return 1; }
        [ "$(cat /sys/class/thermal/thermal_zone0/type 2>/dev/null)" = "aoss-0" ] || { SOURCE_REASON=spoof_injection_changed; return 1; }
        spoof_known=1
    fi

    # Driver-reported battery temperature is the trusted real source; it must
    # always stay original sysfs, otherwise refuse to publish anything.
    is_original_sysfs "$raw" || { SOURCE_REASON=source_mounted_or_replaced; return 1; }
    deci=$(cat "$raw" 2>/dev/null)
    case "$deci" in *[!0-9-]*|'') SOURCE_REASON=invalid_sample; return 1 ;; esac
    [ "$deci" -ge -400 ] 2>/dev/null && [ "$deci" -le 1200 ] 2>/dev/null || { SOURCE_REASON=invalid_driver_sample; return 1; }

    if [ "$spoof_known" = "1" ]; then
        # Known spoof config: thermal_zone75 is expected to be fake, so skip the
        # zone cross-check and trust the unspoofed driver node directly.
        SOURCE_TEMP=$deci
        SOURCE_PATH=$raw
        SOURCE_REASON=kernel_batt_driver_trusted
        return 0
    fi

    # No known spoof: keep the original dual-source cross-check.
    is_original_sysfs "$zone/type" && is_original_sysfs "$zone/temp" || { SOURCE_REASON=source_mounted_or_replaced; return 1; }
    [ "$(cat "$zone/type" 2>/dev/null)" = "battery" ] || { SOURCE_REASON=wrong_sensor_type; return 1; }
    millidegrees=$(cat "$zone/temp" 2>/dev/null)
    case "$millidegrees:$deci" in *[!0-9:-]*|:*|*:) SOURCE_REASON=invalid_sample; return 1 ;; esac
    [ "$millidegrees" -ge -40000 ] 2>/dev/null && [ "$millidegrees" -le 120000 ] 2>/dev/null || { SOURCE_REASON=invalid_thermal_sample; return 1; }
    [ "$deci" -ge -400 ] 2>/dev/null && [ "$deci" -le 1200 ] 2>/dev/null || { SOURCE_REASON=invalid_driver_sample; return 1; }
    temp=$((millidegrees / 100))
    delta=$((temp - deci))
    [ "$delta" -ge -10 ] && [ "$delta" -le 10 ] || { SOURCE_REASON=driver_crosscheck_mismatch; return 1; }
    SOURCE_TEMP=$temp
    SOURCE_PATH=$zone/temp
    SOURCE_REASON=kernel_battery_unmasked
    return 0
}
