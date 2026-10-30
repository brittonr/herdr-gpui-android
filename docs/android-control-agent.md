# Android local diagnostics

Phase 1 is an app-local, read-only diagnostics screen. It reads only when you
ask and only while the screen is open; there is no service, notification or
background work.
It is not a remote control agent: there is no listener, controller pairing,
MCP/ChatGPT bridge, shell, root request, accessibility service, or screen capture.

## Scope

The remote terminal client remains a client of Herdr. Local diagnostics are
separate from SSH, Iroh, companion authentication, and terminal connections.

## Use

1. From **Remote hosts**, open **Local device diagnostics**.
2. Choose **Read diagnostics** to read one snapshot. Opening the screen alone
   reads nothing.
3. Leave the screen to discard the result.

Leaving or backgrounding the screen and recreating it clear the shown result
and discard a read still in progress; returning shows nothing until you read
again. No grant, result or schedule is persisted, and no permission is needed.
Diagnostics never stop a remote Herdr daemon or terminal.

An earlier version ran these reads in a `specialUse` foreground service with a
notification and a Stop action. Google Play requires a written justification and
a video for that service type, and a read that only happens on request while the
screen is open needs no service, so the service and its permissions were removed.

## Local contract and privacy

`DeviceDiagnosticsActivity` is not exported. Choosing **Read diagnostics** runs
`DeviceDiagnostics.snapshot()` on a worker and shows the result on the main
thread. There is no other entry point and no command or request body. A
successful read is a JSON object with exactly:

| Field | Value |
| --- | --- |
| `schema_version` | Integer `1` |
| `device_model` | Android model, at most 128 Unicode code points |
| `manufacturer` | Android manufacturer, at most 128 Unicode code points |
| `android_sdk` | Running Android API level |
| `battery_percent` | Integer 0–100, or JSON `null` when unavailable |
| `app_storage_available_bytes` | Nonnegative available bytes on the app-private filesystem, or JSON `null` when unavailable |

Hardware strings omit controls, format/bidi controls, and unpaired surrogates.
The fixed allowlist excludes serials, Android IDs, accounts, installed-app or
process inventories, paths, profiles, credentials, and terminal content. Storage
is filesystem availability, not an app quota or a scan of files. Results are
not logged or sent to a host, and are excluded from saved view state. The window
retains `FLAG_SECURE`; autofill and, on API 30+, content capture are disabled
for its views.

One read may be in flight: the button is disabled until it finishes. After five
seconds the read is reported as timed out, and an unavailable read says so.
A late result cannot appear after a timeout or after the screen was left. A
single process-wide worker slot also bounds platform calls that ignore
interruption: if a read from an earlier visit still holds it, a new request is
refused rather than starting another worker. No automatic reads or retries occur.

## Verification

```sh
cd android-gpui
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleRelease :app:lintDebug :app:lintRelease
./gradlew --no-daemon :app:compileVmTests
HERDR_VM_DIAGNOSTICS_ONLY=1 ./vm-tests/run.sh emulator-5554 \
  /absolute/path/to/app-debug.apk /absolute/path/to/fresh-evidence
```

Use only an explicitly selected disposable emulator. The separate test APK must
be signed with the target's development key. `compileVmTests` builds the debug
target first and selects Gradle's configured debug keystore, not a guessed home
directory. The runner exercises actual Android Views, the installed package's
declared services and permissions, a real allowlisted read, HOME and
recreation; captures use in-process `View.draw` without removing `FLAG_SECURE`.
No GPUI/JNI, remote host, or profile store is needed. See
[the runtime harness](../android-gpui/vm-tests/README.md).

The JVM tests cover unavailable battery/storage values and hostile/oversized
hardware strings. The runtime runner checks that the app declares no service or
foreground-service permission, that nothing shows before a read, that a read
shows exactly the allowlisted fields with sane values, and that HOME and
recreation clear it. Build/test compilation is not a runtime pass; physical Titan
and ARM64 execution require separate validation.

## Future design, not part of phase 1

### Authenticated control

- Pair device to a single approved controller using short-lived one-time pairing and pinned public keys.
- Use an outbound encrypted connection; do not bind an internet-facing listener.
- Per-operation capability grants, expiration, revocation, and audit history.
- Add narrowly scoped shell diagnostics before considering privileged commands. Root remains off by default, separately granted, and confirmed for each privileged operation.
- Explicit approval for installs, file mutations, configuration changes, and destructive operations.
- Prevent unattended credential capture, secure-screen bypass, and unrestricted shell exposure.

### GUI control

- Kotlin Android service for accessibility actions and UI hierarchy inspection, with user-granted accessibility permission.
- Screen capture via Android MediaProjection with OS consent and clear active indicator.
- Scoped tap, swipe, type, launch-app operations; no attempts to bypass Android permission prompts or secure windows.
- Bounded screenshots and event streams; redact sensitive UI content where possible.

### Integration constraints

Keep Android OS APIs in the Android host layer; keep wire validation and authorization independent of GPUI rendering. Investigate existing companion authentication and Iroh transport before adding dependencies or protocol variants. Expose an MCP-compatible gateway only after pairing, revocation, and approval semantics have tests.
