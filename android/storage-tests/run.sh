#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RUNTIME=${HERDR_ANDROID_TEST_RUNTIME:?Set HERDR_ANDROID_TEST_RUNTIME to an Android runtime JAR with runnable org.json classes}
OUT=$(mktemp -d "${TMPDIR:-/tmp}/herdr-android-storage.XXXXXX")
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
find "$ROOT/app/src/main/java/dev/herdr/android/core" "$ROOT/storage-tests" -name '*.java' -print > "$OUT/sources"
printf '%s\n' "$ROOT/app/src/main/java/dev/herdr/android/SavedHost.java" "$ROOT/app/src/main/java/dev/herdr/android/HostStore.java" >> "$OUT/sources"
if command -v javac >/dev/null 2>&1; then
    javac -encoding UTF-8 -source 8 -target 8 -Xlint:all,-options -cp "$RUNTIME" -d "$OUT" @"$OUT/sources"
else
    java com.sun.tools.javac.Main -encoding UTF-8 -source 8 -target 8 -Xlint:all,-options -cp "$RUNTIME" -d "$OUT" @"$OUT/sources"
fi
java -cp "$OUT:$RUNTIME" dev.herdr.android.HostStoreSelfTest
