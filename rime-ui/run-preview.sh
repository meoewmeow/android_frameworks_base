#!/usr/bin/env bash
# One command for a guarded APK build + package-only install + deterministic Activity launch.
set -euo pipefail
cd "$(dirname "$0")"
preview_serial=""
preview_developer=false
for preview_arg in "$@"; do
    case "$preview_arg" in
        --developer) preview_developer=true ;;
        --*) echo "Unknown option: $preview_arg" >&2; exit 2 ;;
        *) preview_serial="$preview_arg" ;;
    esac
done
preview_adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
"$preview_adb" start-server
if [[ -z "$preview_serial" ]]; then
    preview_serial="$("$preview_adb" get-serialno)"
    if [[ "$preview_serial" == unknown || -z "$preview_serial" ]]; then echo "Specify one connected ADB serial" >&2; exit 2; fi
fi
"$preview_adb" -s "$preview_serial" get-state
./safe-build.sh preview
cat last-build-result.txt
printf 'Installing only dev.rime.preview. Recovery: Android Settings > Apps > Rime UI Preview > Uninstall, or adb uninstall dev.rime.preview.\n'
"$preview_adb" -s "$preview_serial" install -r preview-app/build/outputs/apk/debug/preview-app-debug.apk
"$preview_adb" -s "$preview_serial" shell am force-stop dev.rime.preview
"$preview_adb" -s "$preview_serial" shell am start -W -n dev.rime.preview/.MainActivity --ez developer "$preview_developer"
