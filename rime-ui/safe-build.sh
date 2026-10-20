#!/usr/bin/env bash
# Standalone APK equivalent of the ROM safe-build guard; never invokes Soong or flashes images.
set -euo pipefail
cd "$(dirname "$0")"
preview_target="${1:-preview}"
preview_artifact="${2:-preview-app/build/outputs/apk/debug/preview-app-debug.apk}"
exec 9>.preview-build.lock
if ! flock -n 9; then echo "REFUSED: another preview build is running" | tee last-build-result.txt; exit 3; fi
if pgrep -f 'soong_ui --build-mode' >/dev/null; then echo "REFUSED: a ROM build is running" | tee last-build-result.txt; exit 3; fi
./verify-boundary.sh
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [[ "$preview_target" == preview ]]; then
    preview_tasks=(:preview-app:assembleDebug :shared-ui:testDebugUnitTest)
else
    preview_tasks=("$preview_target")
fi
if ./gradlew "${preview_tasks[@]}" >last-build.log 2>&1; then
    if [[ "$preview_target" == preview || "$preview_target" == :preview-app:assembleDebug ]]; then
        if [[ ! -s "$preview_artifact" ]]; then echo "FAILED: artifact missing" | tee last-build-result.txt; exit 2; fi
        printf 'OK: %s; SHA256 %s\n' "$preview_target" "$(sha256sum "$preview_artifact" | cut -d' ' -f1)" | tee last-build-result.txt
    else
        printf 'OK: %s\n' "$preview_target" | tee last-build-result.txt
    fi
else
    preview_exit=$?
    printf 'FAILED: %s (exit %s); any APK on disk is from an older successful build\n' "$preview_target" "$preview_exit" | tee last-build-result.txt
    tail -45 last-build.log
    exit "$preview_exit"
fi
