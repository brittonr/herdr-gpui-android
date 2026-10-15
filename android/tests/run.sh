#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT=$(mktemp -d "${TMPDIR:-/tmp}/herdr-android-core.XXXXXX")
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
find "$ROOT/app/src/main/java/dev/herdr/android/core" "$ROOT/tests" -name '*.java' -print > "$OUT/sources"
if command -v javac >/dev/null 2>&1; then
    javac -encoding UTF-8 -source 8 -target 8 -Xlint:all,-options -d "$OUT" @"$OUT/sources"
else
    # Some JDK-derived images omit the javac launcher but retain its compiler module.
    java com.sun.tools.javac.Main -encoding UTF-8 -source 8 -target 8 -Xlint:all,-options -d "$OUT" @"$OUT/sources"
fi
java -cp "$OUT" dev.herdr.android.core.CoreSelfTest
