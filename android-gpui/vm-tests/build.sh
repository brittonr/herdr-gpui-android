#!/usr/bin/env bash
# Build a separate instrumentation APK. No production source or APK is modified.
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK:?Set ANDROID_HOME to the official installed Android SDK}"
TOOLS="$SDK/build-tools/${HERDR_BUILD_TOOLS_VERSION:-35.0.0}"
API="$SDK/platforms/android-${HERDR_COMPILE_SDK:-35}/android.jar"
BUILD="$HERE/build"
KEY="${HERDR_VM_DEBUG_KEYSTORE:-$ROOT/build/manual/debug.keystore}"
[[ -f "$KEY" ]] || { echo "Target debug signing key not found: $KEY" >&2; exit 1; }
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/signing"
find "$BUILD/classes" "$BUILD/dex" -type f -delete
python3 "$HERE/package-config.py" "$BUILD/AndroidManifest.xml"
python3 "$HERE/package-config.py" > "$BUILD/target-package.txt"
"$TOOLS/aapt2" link -I "$API" --manifest "$BUILD/AndroidManifest.xml" \
  --min-sdk-version 29 --target-sdk-version 35 --debug-mode -o "$BUILD/resources.apk"
mapfile -d '' SOURCES < <(find "$HERE/src" -name '*.java' -print0)
java com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$API:$TOOLS/core-lambda-stubs.jar" -d "$BUILD/classes" "${SOURCES[@]}"
python3 - "$BUILD" <<'PY'
from pathlib import Path
import sys, zipfile
root = Path(sys.argv[1])
with zipfile.ZipFile(root/'classes.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
    for p in sorted((root/'classes').rglob('*.class')):
        jar.write(p, p.relative_to(root/'classes').as_posix())
PY
java -cp "$TOOLS/lib/d8.jar" com.android.tools.r8.D8 --debug --min-api 29 \
  --lib "$API" --output "$BUILD/dex" "$BUILD/classes.jar"
python3 - "$BUILD" <<'PY'
from pathlib import Path
import sys, zipfile, shutil
root = Path(sys.argv[1]); shutil.copyfile(root/'resources.apk', root/'unsigned.apk')
with zipfile.ZipFile(root/'unsigned.apk', 'a', zipfile.ZIP_STORED) as apk:
    for p in sorted((root/'dex').glob('*.dex')): apk.write(p, p.name)
PY
java com.sun.tools.javac.Main -classpath "$TOOLS/lib/apksigner.jar" -d "$BUILD/signing" "$ROOT/scripts/SignDebugApk.java"
java -cp "$TOOLS/lib/apksigner.jar:$BUILD/signing" SignDebugApk "$KEY" \
  "$BUILD/unsigned.apk" "$BUILD/herdr-gpui-vm-tests.apk" 29
"$TOOLS/aapt2" dump badging "$BUILD/herdr-gpui-vm-tests.apk" > "$BUILD/badging.txt"
sha256sum "$BUILD/herdr-gpui-vm-tests.apk"
echo "Built instrumentation only; nothing was installed or executed."
