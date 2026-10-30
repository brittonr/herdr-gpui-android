# Android VM runtime tests

## Local diagnostics suite

`HERDR_VM_DIAGNOSTICS_ONLY=1` selects `DiagnosticsVmInstrumentation` in the same
separate test APK. It exercises the app's real screen-only local diagnostics
Activity. The instrumentation checks that the installed target declares no
services or foreground-service permissions, verifies the initial empty screen,
reads and validates the rendered JSON and button state, then checks that pausing
and recreating the Activity clears the output. It also verifies `FLAG_SECURE`,
except for the debuggable `Settings.Global herdr_probe=1` exception. No GPUI/JNI,
remote daemon, network fixture, or stored host profile is used.

For the Gradle-built debug target, compile with its configured development
signing key, then run only on an explicitly selected disposable emulator:

```sh
./gradlew --no-daemon :app:compileVmTests
HERDR_VM_DIAGNOSTICS_ONLY=1 ./vm-tests/run.sh emulator-5554 \
  /absolute/path/to/app-debug.apk /absolute/path/to/fresh-evidence
```

The Gradle task builds the debug target and uses its signing configuration; it
does not assume a keystore location under `$HOME`. For a standalone SDK-packaged
target, set `HERDR_VM_DEBUG_KEYSTORE` to that APK's development key and run
`./vm-tests/build.sh` directly.

The mode is mutually exclusive with remote, frame, layout, and quick-command
modes. Evidence is written to `files/diagnostics-vm-tests/<run_id>` in the target
and extracted by `run.sh`. The report identifies `diagnostics-phase-1-runtime`.
Captures draw the actual Android Views in-process with `FLAG_SECURE` retained.

Coverage includes absence of declared services and foreground-service
permissions, no output before a tap, parsed JSON with exactly the six
allowlisted keys and sane device/battery/storage values, button re-enabling,
output clearing after HOME pauses the screen and upon return, empty output after
Activity recreation, and secure-window policy including its explicit debug
probe exception. This does not prove physical Titan/ARM64 behavior, third-party
keyboards, the terminal renderer, or any remote control plane.

## Remote SSH runtime suite

`RemoteVmInstrumentation` is a separate runner in the same test APK. It uses the
actual production JNI session implementation and TCP/SSH transport to the
checked-in `android-remote/fixtures` peer. The peer performs a real SSH handshake
and password authentication, then emits a **synthetic typed Herdr generation-1
protocol**. This is not execution against a real Herdr daemon.

The runner accepts only `10.0.2.2` (the Android emulator's host-loopback alias) in
the default `host` route, or `127.0.0.1` when the `guest` route is explicitly
selected. Both require an explicit port and fingerprint, and the fixture's fixed
public test credentials. There is no automatic route fallback.
It checks missing/mismatched trust, wrong password, compatible probe, Welcome,
focus and decoded frames; creates a synthetic profile through Remote hosts
widgets; cancels and accepts host trust; signs in and selects the actual pane;
captures native GPUI rendering; sends physical keyboard, Unicode IME, palette
Insert and Send + Enter across SSH and verifies peer echo ordering. HOME must
close the native handle and reject stale input, and explicit UI reconnection
must not replay any old commands. Physical input is reported as skipped when the
emulator exposes no non-virtual alphabetic keyboard.

After HOME and the consumed terminal's self-close, the full suite finishes the
old host Activity, sends ordinary wake/dismiss-keyguard gestures, and starts a
fresh Remote hosts screen to model explicitly reopening the app. It verifies
saved trust and the absence of an automatic connection before entering the
fixture password again. Both old and new host workers are stopped before profile
restoration; no production foreground or lifecycle behavior is overridden.
If SystemUI restarts and restores an ordinary nonsecure keyguard during this
reopen, the harness observes `KeyguardManager` and retries normal wake/dismiss
only while that lock is actually present, at most once per five seconds within
the existing 120-second UI wait. Each attempt records lock/security/power state
in the report and logcat. A secure device/SIM credential lock stops the test for
manual unlock. Persistent SystemUI/keyguard failure stays a test failure with
diagnostics; no OS security, screen timeout, or application flag is changed.

Start the fixture in the same persistent host network namespace as the emulator
and ADB. Save its first stdout JSON line as `synthetic-fixture.json`. Its listener
binds only `127.0.0.1`; no external SSH host is used. After building both APKs with
the same development key:

```sh
./vm-tests/run-remote.sh emulator-5554 /absolute/path/to/remote-x86_64-debug.apk \
  /absolute/path/to/synthetic-fixture.json /absolute/path/to/fresh-evidence
```

Alternatively, start the Android-compiled fixture inside the disposable VM, save
its first stdout JSON line, and explicitly select guest loopback:

```sh
HERDR_VM_FIXTURE_ROUTE=guest ./vm-tests/run-remote.sh emulator-5554 \
  /absolute/path/to/remote-x86_64-debug.apk /absolute/path/to/guest-fixture.json \
  /absolute/path/to/fresh-evidence
```

Both fixture metadata files name the bind address `127.0.0.1`; the explicit route
controls whether Android connects through the emulator host alias or directly to
its own loopback. The report and `fixture-route.txt` record the chosen route.

`run-remote.sh` validates the fixture identity before install, then uses `run.sh`
for explicit emulator selection, APK identities, logs and evidence extraction.
The instrumentation component is
`<applicationId>.vmtests/dev.herdr.android.vm.RemoteVmInstrumentation`. If running
it directly, supply `-e run_id <unique-id> -e fixture_route host -e fixture_host 10.0.2.2`
(or the explicit pair `-e fixture_route guest -e fixture_host 127.0.0.1`),
`-e fixture_port <port> -e fixture_fingerprint <SHA256:fingerprint>`,
`-e fixture_username synthetic-fixture`, and
`-e fixture_password public-synthetic-test-password`. These are public fixture
literals, not user credentials. Evidence is stored under the target app's
`files/remote-vm-tests/<run_id>` and includes the final report and PNGs.

The suite temporarily isolates the test app's encrypted remote profiles and
restores and rereads their original values after its Activities/workers stop.
Use only a disposable VM with synthetic data: native process termination can
prevent Java cleanup. No production code is injected or transport mocked.
`FLAG_SECURE` remains enabled; screenshots use in-process SurfaceView PixelCopy
and Java View.draw. This does not verify an actual daemon, Iroh, Titan hardware,
ARM64 execution, or third-party keyboard UI. A build is not a runtime pass.

Host-only allowlist checks: `python3 vm-tests/test-remote-fixture-config.py`.

For separate final-resolution screenshots, set `HERDR_VM_FRAME_ONLY=1` together
with the selected route when invoking `run-remote.sh`. This reports the distinct
`remote-ssh-frames` suite. It keeps real SSH trust/login/pane selection, native
GPUI capture, and secure Cmds list/Context preview capture, then cancels the
preview and restores the original profiles. It sends no terminal input and
explicitly skips the direct JNI boundary and input/lifecycle/reconnect checks.
A frame-only pass does not replace a `remote-ssh-runtime` pass. The runner uses
the VM's existing display size and density; it does not change them.

## Existing HTTP fixture suite

This is a **separate, development-signed instrumentation APK**. It runs the actual
installed GPUI target APK, including the Java Activity, JNI methods, Rust input
FIFO and GPUI/wgpu SurfaceView. It does not edit production source or change the
target APK. Use a disposable VM containing only synthetic test data.

## Coverage and limits

- Launch using the normal one-shot `GpuiLaunch.launch` grant and real native load
- Feed screen JSON through the real `CompanionClient` and Activity pump into GPUI
- Capture non-uniform native frames through in-process `PixelCopy`; retain the
  target window's `FLAG_SECURE`. The `-app.png` image combines that native surface
  with a `View.draw` capture of Java controls; it excludes the external IME window
- Measure actual Activity/SurfaceView/control bounds against Android system/IME
  insets; confirm the options row has a 48dp target and the View control stays
  pinned while configured extras scroll
- Change Compact/Standard and 11sp/18sp options through the real View dialog;
  compare actual native header and glyph pixels, capture each frame, and verify
  local options persist across terminal close/reopen
- Confirm the IME is hidden initially, then click GPUI's explicit Keyboard
  control by a SurfaceView touch event
- Exercise actual app `InputConnection` composition and Unicode commit; inspect
  the actual `EditorInfo` privacy flags and reject stale connections while the
  secure options dialog is open
- Verify exact native FIFO → client request sequence, including text before Enter
- Dispatch keyboard events with an Android-enumerated, non-virtual keyboard ID;
  report a skip if absent. This checks the physical-device handler path, not
  actual hardware delivery or external keyboard firmware
- Check one-shot Ctrl touch before an immediately following physical letter,
  unsupported modifier combinations producing no POST, and Alt text obtained
  from the emulator's actual `KeyCharacterMap`, including stable meaning across
  layout changes and the explicit Terminal Alt override
- Check shortcut touch, composition blocking, and cancelled touch gestures
- Send Android HOME, resume the existing Activity, discard queued input, reject a
  stale native generation, close, and reopen with a new launch grant
- Fail closed after synthetic HTTP 401, timeout, malformed screen and rejected
  acknowledgement; assert no automatic retries or post-error commands
- Round-trip per-host quick-command labels, Unicode text and pins through the real
  Android Keystore-backed HostStore; check that profile/shortcut edits preserve
  commands and that changed-origin/deleted-host editors fail closed
- Queue quick-command insertion and a single native Submit entry; verify exact
  text/Enter ordering, rejected text acknowledgement suppressing Enter, and real
  HOME after a valid text response suppressing Enter without replay after resume
- Use native Cmds dialogs to add/edit/pin/unpin/delete and reopen saved commands;
  verify Cancel and local edits send nothing, Insert sends text only, and Send +
  Enter needs a fresh empty/idle-prompt confirmation. Old GPUI IME and physical
  Activity input are isolated, and stale preview buttons cannot use a resumed
  connection
- Hold the test HostStore lock while an accepted save is pending; Cancel and
  reopen Cmds without creating a stale disabled list, release the lock, then
  verify the saved command. Retained Delete-Cancel callbacks also cannot replace
  a fresh dialog after HOME/resume

The test fixture replaces only `CompanionClient.ConnectionFactory`, using the
existing package-private injection constructor through reflection. Instrumentation
installs the connection immediately before `onResume` and schedules the Activity's
real private pump, so the production constructor never opens a real URL. Requests
must stay at `https://synthetic.invalid/fixture/`; the fake connection never opens
sockets. No user host is discovered and the token is a public synthetic literal.
The full runtime harness temporarily writes synthetic profiles into the disposable
test app's encrypted HostStore, tests persistence/isolation, and restores and
verifies the original profile list in final cleanup. It never records profile
tokens or cleartext profile data in its report. Frame/layout smoke runs do not
change the profile store. The harness temporarily sets the three local
terminal display choices for reproducibility and restores their original values
in its final cleanup. It does not clear app data. A native process crash can
prevent Java cleanup; use a disposable VM with synthetic data only.

**This does not verify TLS handshakes, certificates, real socket behavior, a real
Herdr server, third-party IME UI, physical hardware input, Titan keyboard firmware/gestures, or ARM64 device execution.**
A successful compile or APK signature check is not a VM runtime pass. Runtime
success requires the final JSON report, exact command assertions, and actual
captured GPUI frames. Inspect those PNGs and logcat before reporting visual success.

## Build

Requires the already-installed official Android SDK API 35 / Build Tools 35.0.0
and the same development signing key used by the target APK. Nothing is downloaded.

```sh
export ANDROID_HOME=/path/to/android-sdk
export HERDR_VM_DEBUG_KEYSTORE=/path/to/the-target/debug.keystore
./vm-tests/build.sh
```

The output is `vm-tests/build/herdr-gpui-vm-tests.apk`. The script defaults to the
parent project's `build/manual/debug.keystore`; override this for an x86 test
variant that has its own signing key. The target package and the separate
`.vmtests` package are derived from `app/build.gradle`; the runner checks both
named APK package IDs and rejects stale instrumentation. It does not install or
run anything. API 35 build stubs can instrument the API 36 emulator; build API
and runtime API are recorded separately rather than conflated.

## Run against an explicitly confirmed VM

```sh
./vm-tests/run.sh emulator-5554 /absolute/path/to/herdr-gpui-x86_64-debug.apk \
  /absolute/path/to/evidence
```

The script rejects non-emulator serials and checks `ro.kernel.qemu=1`. It installs
only the two named APKs, sends ordinary wake/dismiss-keyguard gestures, then
captures the instrumentation report, screenshots,
request log (synthetic bodies only), APK hashes, logcat, and final activity/surface
state. It does not clear app data, logs, security settings, or certificate stores.
It uses a unique app-owned run directory and checks that the final report has the
same run ID. It also requires a fresh host evidence directory on every attempt,
so an interrupted attempt cannot be mistaken for a later result. Each UI wait allows up to 120 seconds for software
emulation. Keep the command in the same execution session/network namespace as
ADB and the emulator when the host isolates tool processes.

The harness repeats the same transient wake/dismiss-keyguard gestures before
Activity transitions because software-emulator SystemUI restarts can restore the
swipe keyguard. It waits for a valid SurfaceView before GPUI interaction; it does
not change lock settings, credentials, timeouts, or system security policy.

A failure retains available evidence and exits nonzero. If native startup crashes
before Java can write `report.json`, use instrumentation output and logcat; do not
call that a missing-test-harness success. The separate test APK is not a production
artifact and should not be shipped to end users.

## Compact representative VM

The compact validation target is Android 16 / API 36, 1080 × 1200 pixels, at
representative Android density 400 (432 × 480dp before system insets). Density
400 is a chosen test configuration, not a claim about the Titan's factory
logical density; the manufacturer's panel PPI does not establish Android dp.
The report records the actual decor, available content, SurfaceView and control
bounds, font scale, native header/glyph measurements, and enumerated emulator
keyboard mapping. PNGs still need visual inspection for clipping and glyph quality.

Host-only display policy checks can be run without Android or JSON dependencies:

```sh
mkdir -p vm-tests/build/host-display-tests
javac -d vm-tests/build/host-display-tests \
  app/src/main/java/dev/herdr/android/GpuiDisplayOptions.java \
  tests/dev/herdr/android/GpuiDisplayOptionsSelfTest.java
java -cp vm-tests/build/host-display-tests dev.herdr.android.GpuiDisplayOptionsSelfTest
python3 vm-tests/test-package-config.py
```

For a second explicitly configured disposable VM layout (such as short-wide or
large Android accessibility text), `HERDR_VM_LAYOUT_ONLY=1 ./vm-tests/run.sh ...`
runs a separately identified geometry/rendering smoke suite and captures the
18sp choice under that VM's actual font scale. The runner never changes display
size, density or system text scale itself. A layout-only report is not a pass of
the input/lifecycle/error suite.


## Compact-profile runs

The harness reads the current application ID from Gradle and verifies both APK
identities before installing. Evidence uses app-private storage and is retrieved
with run-as from the explicitly selected debuggable test app. UI steps use a
bounded main-thread barrier; a continuously rendering view need not become idle.

- Default: full runtime checks
- HERDR_VM_FRAME_ONLY=1: initial real frame, safe insets, hidden initial IME,
  and read-only Cmds dialog captures
- HERDR_VM_LAYOUT_ONLY=1: layout/font/extra-key checks only
- HERDR_VM_QUICK_COMMANDS_ONLY=1: encrypted command storage, initial frame,
  command UI, FIFO, ACK-failure and pause/resume checks only

Frame-only runs also capture and cancel the secure Cmds list and preview without
editing profiles or sending terminal input. They dispatch Enter and Numpad Enter
from an enumerated non-virtual emulator keyboard to both preview editors and
assert no request or implicit dismissal; this narrow modal check is still not a
full input/lifecycle pass.

Each report records its suite type. A frame-only or layout-only result is not a
full input/lifecycle pass. If the emulator's KeyCharacterMap lacks an Alt symbol
mapping, that hardware-dependent check is explicitly skipped; a separate, clearly
labeled synthetic Unicode override still tests the production forwarding path.
No Titan keycodes or symbol map are invented.
