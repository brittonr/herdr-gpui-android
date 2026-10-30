#!/usr/bin/env bash
# Run only against an explicitly named disposable Android VM. Never selects a device implicitly.
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <confirmed-emulator-serial> <target-x86_64-debug.apk> [evidence-directory]" >&2
  exit 2
fi
SERIAL="$1"; TARGET_APK="$2"; OUT="${3:-$HERE/build/evidence}"
TEST_APK="$HERE/build/herdr-gpui-vm-tests.apk"
TARGET_PACKAGE="$(python3 "$HERE/package-config.py")"
TEST_PACKAGE="$TARGET_PACKAGE.vmtests"
if [[ "${HERDR_VM_DIAGNOSTICS_ONLY:-0}" == 1 ]]; then
  [[ -z "${HERDR_VM_REMOTE_METADATA_FILE:-}" && "${HERDR_VM_QUICK_COMMANDS_ONLY:-0}" != 1 && "${HERDR_VM_LAYOUT_ONLY:-0}" != 1 && "${HERDR_VM_FRAME_ONLY:-0}" != 1 ]] || {
    echo "Diagnostics tests cannot be combined with terminal/transport modes" >&2; exit 2;
  }
fi
FIXTURE_VALUES=()
if [[ -n "${HERDR_VM_REMOTE_METADATA_FILE:-}" ]]; then
  # Validate the explicit route before any device operation or installation.
  FIXTURE_METADATA="$(python3 "$HERE/remote-fixture-config.py" --route "${HERDR_VM_FIXTURE_ROUTE:-host}" "$HERDR_VM_REMOTE_METADATA_FILE")"
  mapfile -t FIXTURE_VALUES <<< "$FIXTURE_METADATA"
fi
[[ "$(cat "$HERE/build/target-package.txt")" == "$TARGET_PACKAGE" ]] || {
  echo "Instrumentation was built for a different target; rebuild vm-tests" >&2; exit 2;
}
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
AAPT2="$SDK/build-tools/${HERDR_BUILD_TOOLS_VERSION:-35.0.0}/aapt2"
[[ -x "$AAPT2" ]] || { echo "Official SDK aapt2 is required to verify the named APK packages" >&2; exit 2; }
[[ "$SERIAL" == emulator-* ]] || { echo "Refusing a non-emulator serial" >&2; exit 2; }
[[ -f "$TARGET_APK" && -f "$TEST_APK" ]] || { echo "Target or test APK is missing" >&2; exit 2; }
ADB="${ADB:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb}"
[[ -x "$ADB" ]] || ADB="$(command -v adb)"
[[ "$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo "The confirmed serial does not identify an Android emulator" >&2; exit 2;
}
[[ ! -e "$OUT/instrumentation.txt" && ! -e "$OUT/vm-tests" ]] || {
  echo "Evidence directory already contains a test attempt; choose a fresh directory" >&2; exit 2;
}
for spec in "$TARGET_APK:$TARGET_PACKAGE" "$TEST_APK:$TEST_PACKAGE"; do
  apk="${spec%:*}"; expected="${spec##*:}"
  actual="$("$AAPT2" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
  [[ "$actual" == "$expected" ]] || { echo "Wrong APK package: $actual (expected $expected)" >&2; exit 2; }
done
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$OUT"
printf '%s\n' "$RUN_ID" > "$OUT/run-id.txt"
"$ADB" -s "$SERIAL" shell getprop > "$OUT/device-properties.txt"
printf '%s\n' "$TARGET_PACKAGE" > "$OUT/target-package.txt"
"$ADB" -s "$SERIAL" shell wm size > "$OUT/display-size.txt"
"$ADB" -s "$SERIAL" shell wm density > "$OUT/display-density.txt"
sha256sum "$TARGET_APK" "$TEST_APK" > "$OUT/apk-sha256.txt"
"$ADB" -s "$SERIAL" install -r "$TARGET_APK" | tee "$OUT/install-app.txt"
"$ADB" -s "$SERIAL" install -r "$TEST_APK" | tee "$OUT/install-tests.txt"
"$ADB" -s "$SERIAL" shell pm list instrumentation > "$OUT/instrumentation-list.txt"
# Transient wake/unlock gestures for the fresh emulator; no device security settings change.
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard
# Preserve system logs. -T 1 tails from the current point rather than clearing the device buffer.
"$ADB" -s "$SERIAL" logcat -v threadtime -T 1 > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!
cleanup() {
  local status=$?
  trap - EXIT
  kill "$LOGCAT_PID" 2>/dev/null || true
  wait "$LOGCAT_PID" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT
EXTRA_ARGS=()
RUNNER=dev.herdr.android.vm.GpuiVmInstrumentation
EVIDENCE_ROOT=vm-tests
if [[ "${HERDR_VM_DIAGNOSTICS_ONLY:-0}" == 1 ]]; then
  RUNNER=dev.herdr.android.vm.DiagnosticsVmInstrumentation
  EVIDENCE_ROOT=diagnostics-vm-tests
fi
if [[ "${HERDR_VM_QUICK_COMMANDS_ONLY:-0}" == 1 ]]; then EXTRA_ARGS=(-e quick_commands_only true); fi
if [[ "${HERDR_VM_LAYOUT_ONLY:-0}" == 1 ]]; then EXTRA_ARGS=(-e layout_only true); fi
if [[ "${HERDR_VM_FRAME_ONLY:-0}" == 1 ]]; then EXTRA_ARGS=(-e frame_only true); fi
if [[ -n "${HERDR_VM_REMOTE_METADATA_FILE:-}" ]]; then
  [[ "${HERDR_VM_QUICK_COMMANDS_ONLY:-0}" != 1 && "${HERDR_VM_LAYOUT_ONLY:-0}" != 1 ]] || {
    echo "Remote SSH tests support the full suite or HERDR_VM_FRAME_ONLY=1" >&2; exit 2;
  }
  # This is public synthetic fixture metadata, never a user SSH configuration.
  RUNNER=dev.herdr.android.vm.RemoteVmInstrumentation
  EVIDENCE_ROOT=remote-vm-tests
  EXTRA_ARGS=(-e fixture_route "${HERDR_VM_FIXTURE_ROUTE:-host}" -e fixture_host "${FIXTURE_VALUES[0]}" -e fixture_port "${FIXTURE_VALUES[1]}"
    -e fixture_fingerprint "${FIXTURE_VALUES[2]}" -e fixture_username synthetic-fixture
    -e fixture_password public-synthetic-test-password)
  if [[ "${HERDR_VM_FRAME_ONLY:-0}" == 1 ]]; then EXTRA_ARGS+=(-e frame_only true); fi
  printf '%s\n' "${HERDR_VM_FIXTURE_ROUTE:-host}" > "$OUT/fixture-route.txt"
  cp "$HERDR_VM_REMOTE_METADATA_FILE" "$OUT/synthetic-fixture.json"
fi
set +e
"$ADB" -s "$SERIAL" shell am instrument -w -r -e run_id "$RUN_ID" "${EXTRA_ARGS[@]}" \
  "$TEST_PACKAGE/$RUNNER" \
  | tee "$OUT/instrumentation.txt"
RUN_STATUS=${PIPESTATUS[0]}
set -e
# App-private evidence works even when an emulator's emulated SD card is unavailable.
# run-as is limited to the explicitly selected, locally signed debuggable test app.
"$ADB" -s "$SERIAL" exec-out run-as "$TARGET_PACKAGE" tar -C "files/$EVIDENCE_ROOT" -cf - "$RUN_ID" \
  > "$OUT/app-evidence.tar" 2> "$OUT/pull-evidence.txt" || true
python3 - "$OUT/app-evidence.tar" "$OUT/vm-tests" "$RUN_ID" <<'PYEXTRACT'
import pathlib, sys, tarfile
archive, out = map(pathlib.Path, sys.argv[1:3])
try:
    with tarfile.open(archive) as files:
        for item in files:
            name = pathlib.PurePosixPath(item.name)
            if not name.parts or name.parts[0] != sys.argv[3] or '..' in name.parts:
                raise ValueError('Unexpected evidence member')
            if item.isdir(): continue
            if not item.isfile() or item.size > 32 * 1024 * 1024:
                raise ValueError('Invalid evidence member')
            target = out.joinpath(*name.parts[1:])
            target.parent.mkdir(parents=True, exist_ok=True)
            with files.extractfile(item) as data: target.write_bytes(data.read())
except (tarfile.TarError, OSError, ValueError) as error:
    print('Evidence extraction failed:', error, file=sys.stderr)
PYEXTRACT
"$ADB" -s "$SERIAL" shell dumpsys activity activities > "$OUT/activity-after.txt" || true
"$ADB" -s "$SERIAL" shell dumpsys SurfaceFlinger > "$OUT/surfaceflinger-after.txt" || true
if [[ "$RUN_STATUS" != 0 ]]; then echo "ADB instrumentation transport failed: $RUN_STATUS" >&2; exit "$RUN_STATUS"; fi
python3 - "$OUT/vm-tests/report.json" "$RUN_ID" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
if not path.is_file(): raise SystemExit('No final report: instrumentation did not complete')
r = json.loads(path.read_text())
if r.get('run_id') != sys.argv[2]: raise SystemExit('Report belongs to a different run')
print('Runtime result:', r['result'])
for line in r['passed']: print('PASS:', line)
for line in r['skipped']: print('SKIP:', line)
print(r['transport'])
if r['result'] != 'PASS': raise SystemExit('FAIL: ' + str(r['failure']))
PY
