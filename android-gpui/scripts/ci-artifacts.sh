#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ABI="${HERDR_ANDROID_ABI:-arm64-v8a}"
case "$ABI" in
    arm64-v8a) SUFFIX= ;;
    x86_64) SUFFIX=-x86_64 ;;
    *) echo 'Unsupported ABI' >&2; exit 1 ;;
esac
TOOLS="${ANDROID_HOME:?}/build-tools/35.0.0"
LIB="$ROOT/app/src/main/jniLibs/$ABI/libherdr_gpui_android.so"
RECORD="$ROOT/build/native-build$SUFFIX.json"
python3 "$ROOT/scripts/native-build-record.py" verify "$ROOT" "$RECORD" "$LIB" --abi "$ABI"
OUT="$ROOT/build/ci"
mkdir -p "$OUT"
cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$OUT/herdr-$ABI-debug.apk"
cp "$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk" "$OUT/herdr-$ABI-release-unsigned.apk"
for APK in "$OUT/herdr-$ABI-debug.apk" "$OUT/herdr-$ABI-release-unsigned.apk"; do
    python3 "$ROOT/scripts/verify-apk-structure.py" --abi "$ABI" "$APK"
    "$TOOLS/zipalign" -c -P 16 4 "$APK"
    "$TOOLS/aapt2" dump badging "$APK" > "$APK.badging.txt"
    python3 - "$APK" "$LIB" "$ABI" <<'PY'
import pathlib, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    assert apk.read(f'lib/{sys.argv[3]}/libherdr_gpui_android.so') == pathlib.Path(sys.argv[2]).read_bytes(), 'Packaged native binary differs from verified build'
PY
done
"$TOOLS/apksigner" verify --verbose "$OUT/herdr-$ABI-debug.apk"
if "$TOOLS/apksigner" verify "$OUT/herdr-$ABI-release-unsigned.apk"; then
    echo 'Release unexpectedly signed; refusing ambiguous artifact' >&2
    exit 1
fi
cp "$RECORD" "$OUT/native-build.json"
git -C "$ROOT" rev-parse HEAD > "$OUT/SOURCE_COMMIT"
(cd "$OUT" && sha256sum ./*.apk ./*.txt native-build.json SOURCE_COMMIT > SHA256SUMS)
