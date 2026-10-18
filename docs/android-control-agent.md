# Android local diagnostics

Phase 1 is an app-local, read-only diagnostics screen and foreground service.
It is not a remote control agent: there is no listener, controller pairing,
MCP/ChatGPT bridge, shell, root request, accessibility service, or screen capture.

## Scope

The remote terminal client remains a client of Herdr. Local diagnostics are
separate from SSH, Iroh, companion authentication, and terminal connections.

## Use

1. From **Remote hosts**, open **Local device diagnostics**.
2. Check **Enable local diagnostics for this screen**. Android 13+ requires
   notification permission. Approving that OS prompt does not enable diagnostics;
   check the switch again. Blocked app notifications or a blocked diagnostics
   channel prevent enablement.
3. An ongoing **Local diagnostics enabled** notification appears. Choose
   **Read diagnostics** to collect one snapshot. Enabling alone collects nothing.
4. Uncheck the switch or use the notification's **Stop diagnostics** action to
   revoke access and clear the displayed result.

Leaving/backgrounding the screen, recreation, and last-client unbinding also
disable the service. Returning requires a fresh explicit opt-in. The service is
`START_NOT_STICKY`; no grant, result, startup receiver, or restart is persisted.
Diagnostics never stop a remote Herdr daemon or terminal.

## Local contract and privacy

`DeviceDiagnosticsService` is non-exported. Its process-local `LocalBinder`
accepts only the app's UID on the main thread. Binding alone cannot enable it;
only an explicit enable action with usable notifications does. An unknown or
missing start action fails closed. There is no arbitrary command/request body.

`readSnapshot(success, failure)` performs platform reads on a worker and invokes
one callback on the main thread. Success is a JSON object with exactly:

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
bounded to 4 KiB UTF-8, not logged or sent to a host, and excluded from saved
view state. The window retains `FLAG_SECURE`; autofill and, on API 30+, content
capture are disabled for its views.

One read may be in flight. Additional requests return `BUSY`, not queued work.
The five-second deadline returns `TIMEOUT`; unavailable reads return
`UNAVAILABLE`. Disabled/revoked reads return `DISABLED`. Late completions cannot
publish a snapshot after revocation or expiration. A single process-wide worker
slot also bounds platform calls that ignore interruption; a still-occupied slot
remains busy rather than spawning another worker. No automatic reads or retries
occur.

The foreground service uses Android 14+'s `specialUse` type for explicitly
user-enabled local diagnostics, with a declared subtype. It does not claim
network data synchronization. Store-specific foreground-service policy review
remains part of any future app-store distribution, not this source PR.

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
directory. The runner exercises actual Android Views, permissions, foreground
notifications, Binder callbacks, request bounds,
HOME, and recreation; captures use in-process `View.draw` without removing
`FLAG_SECURE`. No GPUI/JNI, remote host, or profile store is needed. See
[the runtime harness](../android-gpui/vm-tests/README.md).

The JVM tests cover unavailable battery/storage values and hostile/oversized
hardware strings. The runtime runner covers default-off state, external-UID bind
denial, binding without enablement, denied notifications, real allowlisted reads,
uncheck/Stop, concurrent reads, the real deadline, in-flight revocation, HOME,
and recreation. Build/test compilation is not a runtime pass; physical Titan
and ARM64 execution require separate validation.

The complete phase-one suite passed all 14 runtime checks on an Android 15
(API 35) x86_64 emulator at 432×768 and 320×640. Android View captures were
visually inspected with `FLAG_SECURE` retained. The host runner restored and
verified both initially denied and initially granted notification permission.
Gradle diagnostics JVM tests, debug/release assembly, and debug/release lint also
passed for x86_64. These results do not establish a clean desktop-workspace gate
or prove any future remote control capability.

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
