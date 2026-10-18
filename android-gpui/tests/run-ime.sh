#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${SDK:?Set ANDROID_HOME to an installed Android SDK}"
OUT=$(mktemp -d "${TMPDIR:-/tmp}/herdr-ime-policy.XXXXXX")
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
javac -encoding UTF-8 -source 8 -target 8 -Xlint:all,-options \
    -classpath "$SDK/platforms/android-35/android.jar" -d "$OUT" \
    "$ROOT/app/src/main/java/dev/gpui/mobile/ImeEditorAction.java" \
    "$ROOT/tests/dev/gpui/mobile/ImeEditorActionSelfTest.java"
java -cp "$OUT" dev.gpui.mobile.ImeEditorActionSelfTest
