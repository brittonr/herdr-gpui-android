#!/usr/bin/env bash
# Isolated Linux verification build. Prefer ../gradlew assembleDebug with a real SDK.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${HERDR_BUILD_CACHE:-$ROOT/build/manual-tools}"
BUILD="$ROOT/build/manual"
OUT="$ROOT/app/build/outputs/apk/manual"
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
fetch aapt2.jar 028916a040b58f34c9c89984e1bc48f8da6581662014f2b66391860def8b9c36 \
    https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.7.3-12006047/aapt2-8.7.3-12006047-linux.jar
fetch r8.jar 58366f77067207c39a17d469de7b05701d2877212a9c55201bcb0af43e59e903 \
    https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.7.18/r8-8.7.18.jar
fetch apksig.jar c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89 \
    https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/8.7.3/apksig-8.7.3.jar
fetch android-all.jar 6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b \
    https://repo.maven.apache.org/maven2/org/robolectric/android-all/15-robolectric-12650502/android-all-15-robolectric-12650502.jar
WORK="$(mktemp -d "$BUILD/work.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/generated" "$WORK/dex" "$WORK/signing"
unzip -q "$CACHE/aapt2.jar" aapt2 -d "$WORK"
chmod +x "$WORK/aapt2"
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
if namespace != package:
    raise SystemExit('This manual build requires applicationId == namespace; use Gradle.')
settings = [package, value('minSdk', r'(\d+)'), value('targetSdk', r'(\d+)'),
            value('versionCode', r'(\d+)'), value('versionName', r"['\"]([^'\"]+)['\"]"),
            value('compileSdk', r'(\d+)')]
if settings[5] != '35':
    raise SystemExit('The pinned framework is Android API 35; use Gradle for another compileSdk.')
(work / 'settings').write_text('\n'.join(settings) + '\n')
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml')
manifest.getroot().set('package', package)
manifest.write(work / 'AndroidManifest.xml', encoding='utf-8', xml_declaration=True)
PY
mapfile -t SETTINGS < "$WORK/settings"
"$WORK/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$WORK/resources.zip"
ASSETS=()
if [[ -d "$ROOT/app/src/main/assets" ]]; then ASSETS=(-A "$ROOT/app/src/main/assets"); fi
"$WORK/aapt2" link -o "$WORK/resources.apk" -I "$CACHE/android-all.jar" \
    --manifest "$WORK/AndroidManifest.xml" --java "$WORK/generated" \
    --min-sdk-version "${SETTINGS[1]}" --target-sdk-version "${SETTINGS[2]}" \
    --version-code "${SETTINGS[3]}" --version-name "${SETTINGS[4]}" \
    --compile-sdk-version-code "${SETTINGS[5]}" --debug-mode \
    "${ASSETS[@]}" "$WORK/resources.zip"
mapfile -d '' SOURCES < <(find "$ROOT/app/src/main/java" "$WORK/generated" -name '*.java' -print0)
java com.sun.tools.javac.Main -source 8 -target 8 -encoding UTF-8 -classpath "$CACHE/android-all.jar" \
    -d "$WORK/classes" "${SOURCES[@]}"
python3 - "$WORK" <<'PY'
import pathlib, sys, zipfile
work = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(work / 'classes.jar', 'w', zipfile.ZIP_DEFLATED) as jar:
    for path in sorted((work / 'classes').rglob('*.class')):
        jar.write(path, path.relative_to(work / 'classes').as_posix())
PY
java -cp "$CACHE/r8.jar" com.android.tools.r8.D8 --debug --min-api "${SETTINGS[1]}" \
    --lib "$CACHE/android-all.jar" --output "$WORK/dex" "$WORK/classes.jar"
python3 - "$WORK" <<'PY'
import pathlib, shutil, sys, zipfile
work = pathlib.Path(sys.argv[1])
shutil.copyfile(work / 'resources.apk', work / 'unsigned.apk')
with zipfile.ZipFile(work / 'unsigned.apk', 'a', zipfile.ZIP_STORED) as apk:
    for path in sorted((work / 'dex').glob('*.dex')):
        apk.write(path, path.name)
PY
# Conventional development-only key, generated locally; never use for release/publication.
if [[ ! -f "$BUILD/debug.keystore" ]]; then
    keytool -genkeypair -keystore "$BUILD/debug.keystore" -storetype JKS \
        -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=Android Debug,O=Android,C=US' >/dev/null
fi
java com.sun.tools.javac.Main -classpath "$CACHE/apksig.jar" -d "$WORK/signing" \
    "$ROOT/scripts/SignDebugApk.java"
java -classpath "$CACHE/apksig.jar:$WORK/signing" SignDebugApk \
    "$BUILD/debug.keystore" "$WORK/unsigned.apk" "$WORK/herdr-verification-debug.apk" "${SETTINGS[1]}"
python3 "$ROOT/scripts/verify-apk-structure.py" "$WORK/herdr-verification-debug.apk"
"$WORK/aapt2" dump badging "$WORK/herdr-verification-debug.apk" > "$WORK/badging.txt"
cat "$WORK/badging.txt"
cp "$WORK/herdr-verification-debug.apk" "$OUT/herdr-verification-debug.apk"
cp "$WORK/badging.txt" "$OUT/herdr-verification-debug.badging.txt"
sha256sum "$OUT/herdr-verification-debug.apk"
printf '\nBuilt %s\n' "$OUT/herdr-verification-debug.apk"
printf 'Verified packaging/signatures only; no emulator or device execution is implied.\n'
