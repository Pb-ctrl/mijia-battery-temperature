#!/system/bin/sh
MODDIR=${0%/*}
printf '\nMijia battery temperature extension, EXPERIMENTAL\n'
if [ -r "$MODDIR/status.txt" ]; then cat "$MODDIR/status.txt"; else printf 'Sampler has not started. Reboot after setup.\n'; fi
printf '\nInstall companion APK manually. Enable Xiaomi Home and Security Center in LSPosed.\n'
printf 'Cloud save and real scene dispatch have NOT been tested on this device.\n'
