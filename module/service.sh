#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/source.sh"
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done

# Global settings contain only a temperature reading and a short-lived heartbeat.
# App hooks refuse to dispatch when the heartbeat is stale or missing.
clear_state() {
    /system/bin/settings put global mijia_bt_heartbeat 0 >/dev/null 2>&1
    /system/bin/settings put global mijia_bt_source_verified 0 >/dev/null 2>&1
}
trap clear_state EXIT INT TERM
clear_state

# Charging scene: only then do we need a live, unspoofed battery temperature.
is_charging() {
    case "$(cat /sys/class/power_supply/battery/status 2>/dev/null)" in
        Charging|Full) return 0 ;;
    esac
    [ "$(cat /sys/class/power_supply/usb/online 2>/dev/null)" = "1" ] && return 0
    [ "$(cat /sys/class/power_supply/ac/online 2>/dev/null)" = "1" ] && return 0
    [ "$(cat /sys/class/power_supply/wireless/online 2>/dev/null)" = "1" ] && return 0
    return 1
}

last_temp=""
tick=0
while [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
    if ! is_charging; then
        # Idle: stop publishing and sleep long to save power.
        # Clear state only once when leaving the charging state.
        if [ -n "$last_temp" ]; then
            last_temp=""
            tick=0
            clear_state
            printf 'experimental=true\nsource_check=not_charging\nheartbeat=0\n' > "$MODDIR/status.txt"
        fi
        sleep 30
        continue
    fi

    valid=0
    read_physical_source && valid=1
    temp=$SOURCE_TEMP
    if [ "$valid" = "1" ]; then
        if [ "$temp" != "$last_temp" ]; then
            if /system/bin/settings put global mijia_bt_temp "$temp" >/dev/null 2>&1; then
                last_temp=$temp
            else
                clear_state
                sleep 5
                continue
            fi
        fi
        if [ "$tick" = "0" ]; then
            stamp=$(date +%s)
            /system/bin/settings put global mijia_bt_source_verified 1 >/dev/null 2>&1
            /system/bin/settings put global mijia_bt_source "$SOURCE_PATH" >/dev/null 2>&1
            /system/bin/settings put global mijia_bt_heartbeat "$stamp" >/dev/null 2>&1
            printf 'experimental=true\nsource_check=%s\nsource=%s\ntemperature_tenths=%s\nheartbeat=%s\n' "$SOURCE_REASON" "$SOURCE_PATH" "$temp" "$stamp" > "$MODDIR/status.txt"
        fi
    else
        clear_state
        printf 'experimental=true\nsource_check=%s\nheartbeat=0\n' "$SOURCE_REASON" > "$MODDIR/status.txt"
        last_temp=""
        tick=2
    fi
    tick=$(( (tick + 1) % 3 ))
    sleep 5
done
clear_state
