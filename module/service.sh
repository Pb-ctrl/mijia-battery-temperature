#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/source.sh"
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done

key=mijia_bt_sample
publish() {
    /system/bin/settings put global "$key" "$1" >/dev/null 2>&1
}
clear_state() { publish invalid; }

# Invalidate the old protocol once when updating from 0.2.1.
/system/bin/settings put global mijia_bt_heartbeat 0 >/dev/null 2>&1
/system/bin/settings put global mijia_bt_source_verified 0 >/dev/null 2>&1
clear_state
trap clear_state EXIT
trap 'exit 0' INT TERM
[ "$(getprop ro.product.device)" = "zorn" ] &&
    [ "$(getprop ro.build.version.incremental)" = "OS4.0.3.0.XOKCNXM" ] || exit 1

printf 'source_check=not_charging\n' > "$MODDIR/status.txt"
"$MODDIR/power-events" "$MODDIR" | while IFS= read -r event; do
    case "$event" in
        idle)
            clear_state
            /system/bin/log -t MijiaBatterySampler "Power disconnected; sampling stopped"
            epoch=
            last_temp=
            last_source=
            last_publish=0
            last_reason=
            printf 'source_check=not_charging\n' > "$MODDIR/status.txt"
            continue ;;
        sample) ;;
        *) continue ;;
    esac
    if ! read_physical_source; then
        if [ "$SOURCE_REASON" != "$last_reason" ]; then
            clear_state
            printf 'source_check=%s\n' "$SOURCE_REASON" > "$MODDIR/status.txt"
        fi
        last_reason=$SOURCE_REASON
        epoch=
        last_temp=
        last_source=
        last_publish=0
        continue
    fi
    read -r uptime _ < /proc/uptime || continue
    seconds=${uptime%.*}
    [ -n "$epoch" ] || epoch=$uptime
    if [ "$SOURCE_TEMP" != "$last_temp" ] || [ "$SOURCE_PATH" != "$last_source" ] ||
        [ "$((seconds - last_publish))" -ge 15 ]; then
        if ! publish "$epoch|$seconds|$SOURCE_TEMP|$SOURCE_PATH"; then
            clear_state
            epoch=
            last_temp=
            last_source=
            last_publish=0
            continue
        fi
        if [ -z "$last_temp" ]; then
            /system/bin/log -t MijiaBatterySampler "First verified sample: $SOURCE_TEMP ($SOURCE_REASON)"
        fi
        last_temp=$SOURCE_TEMP
        last_source=$SOURCE_PATH
        last_publish=$seconds
        printf 'source_check=%s\nsource=%s\ntemperature_tenths=%s\nuptime_seconds=%s\n' \
            "$SOURCE_REASON" "$SOURCE_PATH" "$SOURCE_TEMP" "$seconds" > "$MODDIR/status.txt"
    fi
    last_reason=$SOURCE_REASON
done
printf 'source_check=listener_stopped\n' > "$MODDIR/status.txt"
