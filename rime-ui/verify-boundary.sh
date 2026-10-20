#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if rg -n 'com\.android\.(systemui|internal)|BackgroundBlurDrawable|RenderEffect|SurfaceControl|liquidGlass|glassMode|refraction|androidx\.compose\.material3' shared-ui/src/main/java; then
    echo 'FAILED: platform implementation or Material visual dependency leaked into shared UI' >&2
    exit 1
fi
echo 'OK: shared UI has no SystemUI internals or renderer-specific effects'
