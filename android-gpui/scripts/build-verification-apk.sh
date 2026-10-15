#!/usr/bin/env bash
# Package a real single-ABI GPUI cdylib with the Android Java host.
# Build scripts never download the Android SDK/NDK or accept its license.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${HERDR_BUILD_CACHE:-$ROOT/build/manual-tools}"
ABI="${HERDR_ANDROID_ABI:-arm64-v8a}"
case "$ABI" in
    arm64-v8a) BUILD_SUFFIX=; APK_NAME=herdr-gpui-debug ;;
    x86_64) BUILD_SUFFIX=-x86_64; APK_NAME=herdr-gpui-x86_64-test ;;
    *) echo "Unsupported Android ABI: $ABI" >&2; exit 1 ;;
esac
BUILD="$ROOT/build/manual$BUILD_SUFFIX"
OUT="$ROOT/app/build/outputs/apk/manual"
NATIVE="$ROOT/app/src/main/jniLibs/$ABI/libherdr_gpui_android.so"
[[ -f "$NATIVE" ]] || { echo "Run scripts/build-native.sh first; actual GPUI library missing." >&2; exit 1; }
python3 "$ROOT/scripts/verify-native-library.py" --abi "$ABI" "$NATIVE"
python3 "$ROOT/scripts/native-build-record.py" verify "$ROOT" "$ROOT/build/native-build$BUILD_SUFFIX.json" "$NATIVE" --abi "$ABI"
mkdir -p "$CACHE" "$BUILD" "$OUT"
for program in java keytool python3 curl unzip sha256sum; do
    command -v "$program" >/dev/null || { echo "Missing required command: $program" >&2; exit 1; }
done
if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
    echo "This verification script uses Linux x86_64 AAPT2. Use the normal Gradle build on other hosts." >&2
    exit 1
fi
java com.sun.tools.javac.Main -version
fetch() {
    local name="$1" sha="$2" url="$3"
    if [[ ! -f "$CACHE/$name" ]]; then
        local part
        part="$(mktemp "$CACHE/.download.XXXXXX")"
        if ! curl --fail --location --silent --show-error --retry 2 "$url" -o "$part"; then
            rm -f "$part"; return 1
        fi
        if ! echo "$sha  $part" | sha256sum --check --status; then
            rm -f "$part"; echo "Downloaded checksum mismatch: $name" >&2; return 1
        fi
        mv "$part" "$CACHE/$name"
    fi
    echo "$sha  $CACHE/$name" | sha256sum --check
}
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "$SDK" ]]; then
    TOOLS="$SDK/build-tools/35.0.0"
    FRAMEWORK="$SDK/platforms/android-35/android.jar"
    AAPT2="$TOOLS/aapt2"
    D8_JAR="$TOOLS/lib/d8.jar"
    SIGN_JAR="$TOOLS/lib/apksigner.jar"
    BOOT_CLASSES="$FRAMEWORK:$TOOLS/core-lambda-stubs.jar"
    for path in "$FRAMEWORK" "$AAPT2" "$D8_JAR" "$SIGN_JAR" "$TOOLS/core-lambda-stubs.jar"; do
        [[ -f "$path" ]] || { echo "Missing official SDK component: $path" >&2; exit 1; }
    done
    printf 'Using Android SDK API 35 and Build Tools 35.0.0 from %s\n' "$SDK"
else
    fetch aapt2.jar 028916a040b58f34c9c89984e1bc48f8da6581662014f2b66391860def8b9c36 \
        https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.7.3-12006047/aapt2-8.7.3-12006047-linux.jar
    fetch r8.jar 58366f77067207c39a17d469de7b05701d2877212a9c55201bcb0af43e59e903 \
        https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar
    fetch apksig.jar c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89 \
        https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.7.3/apksig-8.7.3.jar
    fetch android-all.jar 6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b \
        https://repo.maven.apache.org/maven2/org/robolectric/android-all/15-robolectric-12650502/android-all-15-robolectric-12650502.jar
    FRAMEWORK="$CACHE/android-all.jar"
    D8_JAR="$CACHE/r8.jar"
    SIGN_JAR="$CACHE/apksig.jar"
    printf 'No Android SDK configured; using pinned Maven tools and Robolectric API reference\n'
fi
WORK="$(mktemp -d "$BUILD/work.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/generated" "$WORK/dex" "$WORK/signing"
if [[ -z "$SDK" ]]; then
    unzip -q "$CACHE/aapt2.jar" aapt2 -d "$WORK"
    chmod +x "$WORK/aapt2"
    AAPT2="$WORK/aapt2"
fi
# Read the deliberately small Gradle configuration rather than silently using stale app metadata.
python3 - "$ROOT" "$WORK" <<'PY'
import pathlib, re, sys, xml.etree.ElementTree as ET
root, work = map(pathlib.Path, sys.argv[1:])
gradle = (root / 'app/build.gradle').read_text()
if re.search(r'\b(?:implementation|api|compileOnly|runtimeOnly|kapt|annotationProcessor)\s*[ (]', gradle):
    raise SystemExit('Manual build does not resolve app dependencies; use Gradle.')
def value(key, pattern):
    found = re.findall(r'\b' + key + r'\s+' + pattern, gradle)
    if len(found) != 1:
        raise SystemExit('Cannot read unambiguous ' + key + '; use Gradle.')
    return found[0]
package = value('applicationId', r"['\"]([A-Za-z0-9_.]+)['\"]")
namespace = value('namespace', r"['\"]([A-Za-z0-9_.]+)['\"]")
settings = [package, value('minSdk', r'(\d+)'), value('targetSdk', r'(\d+)'),
            value('versionCode', r'(\d+)'), value('versionName', r"['\"]([^'\"]+)['\"]"),
            value('compileSdk', r'(\d+)'), namespace]
if settings[5] != '35':
    raise SystemExit('The pinned framework is Android API 35; use Gradle for another compileSdk.')
(work / 'settings').write_text('\n'.join(settings) + '\n')
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml')
manifest.getroot().set('package', package)
manifest.write(work / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
PY
mapfile -t SETTINGS < "$WORK/settings"
"$AAPT2" compile --dir "$ROOT/../android/app/src/main/res" -o "$WORK/resources.zip"
ASSETS=()
for directory in "$ROOT/../android/app/src/main/assets" "$ROOT/app/src/main/assets"; do
    if [[ -d "$directory" ]]; then ASSETS+=(-A "$directory"); fi
done
"$AAPT2" link -o "$WORK/resources.apk" -I "$FRAMEWORK" \
    --manifest "$WORK/AndroidManifest.xml" --java "$WORK/generated" --custom-package "${SETTINGS[6]}" \
    --min-sdk-version "${SETTINGS[1]}" --target-sdk-version "${SETTINGS[2]}" \
    --version-code "${SETTINGS[3]}" --version-name "${SETTINGS[4]}" \
    --compile-sdk-version-code "${SETTINGS[5]}" --debug-mode \
    "${ASSETS[@]}" "$WORK/resources.zip"
mapfile -d '' SOURCES < <(find "$ROOT/../android/app/src/main/java" "$ROOT/app/src/main/java" "$WORK/generated" -name '*.java' -print0)
JAVAC_API=(-classpath "$FRAMEWORK")
if [[ -n "$SDK" ]]; then JAVAC_API=(-bootclasspath "$BOOT_CLASSES"); fi
java com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 "${JAVAC_API[@]}" \
    -d "$WORK/classes" "${SOURCES[@]}"
python3 - "$WORK" <<'PY'
import pathlib, sys, zipfile
work = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(work / 'classes.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
    for path in sorted((work / 'classes').rglob('*.class')):
        jar.write(path, path.relative_to(work / 'classes').as_posix())
PY
java -cp "$D8_JAR" com.android.tools.r8.D8 --debug --min-api "${SETTINGS[1]}" \
    --lib "$FRAMEWORK" --output "$WORK/dex" "$WORK/classes.jar"
python3 - "$WORK" "$ROOT/app/src/main/jniLibs" "$ABI" <<'PY'
import pathlib, shutil, sys, zipfile
work = pathlib.Path(sys.argv[1])
shutil.copyfile(work / 'resources.apk', work / 'unsigned.apk')
with zipfile.ZipFile(work / 'unsigned.apk', 'a', zipfile.ZIP_STORED) as apk:
    for path in sorted((work / 'dex').glob('*.dex')):
        apk.write(path, path.name)
    libraries = pathlib.Path(sys.argv[2])
    for path in sorted((libraries / sys.argv[3]).glob('*.so')):
        apk.write(path, 'lib/' + path.relative_to(libraries).as_posix())
PY
# Conventional development-only key, generated locally; never use for release/publication.
if [[ ! -f "$BUILD/debug.keystore" ]]; then
    keytool -genkeypair -keystore "$BUILD/debug.keystore" -storetype JKS \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=Android Debug,O=Android,C=US' >/dev/null
fi
java com.sun.tools.javac.Main -classpath "$SIGN_JAR" -d "$WORK/signing" \
    "$ROOT/scripts/SignDebugApk.java"
java -classpath "$SIGN_JAR:$WORK/signing" SignDebugApk \
    "$BUILD/debug.keystore" "$WORK/unsigned.apk" "$WORK/$APK_NAME.apk" "${SETTINGS[1]}"
python3 "$ROOT/scripts/verify-apk-structure.py" --abi "$ABI" "$WORK/$APK_NAME.apk"
"$AAPT2" dump badging "$WORK/$APK_NAME.apk" > "$WORK/badging.txt"
cat "$WORK/badging.txt"
cp "$WORK/$APK_NAME.apk" "$OUT/$APK_NAME.apk"
cp "$WORK/badging.txt" "$OUT/$APK_NAME.badging.txt"
sha256sum "$OUT/$APK_NAME.apk"
printf '\nBuilt %s\n' "$OUT/$APK_NAME.apk"
printf 'Verified actual %s GPUI library, packaging and signatures; no emulator or device execution is implied.\n' "$ABI"
