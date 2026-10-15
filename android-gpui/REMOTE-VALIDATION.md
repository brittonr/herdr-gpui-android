# Remote SSH preview validation — 0.5.0

Validation: 2026-10-07 UTC. Delivery finalized: 2026-10-08 UTC.
See [connection instructions and limitations](REMOTE.md). Historical Compact
validation reports describe earlier APKs; their hashes and pass counts must not
be used as evidence for this remote build.

## Artifact identity

- Application ID: `dev.herdr.android.gpui.remote`
- Version: `0.5.0-gpui-remote`; versionCode 5
- Minimum API 29; compile/target API 35
- ARM64 build `herdr-gpui-debug.apk`: 18,338,703 bytes; SHA-256
  `1427ed2f75fa30fc90ff00f2cb18ecc71530f72a4b4df613b0c135e225d22096`
- x86_64 build `herdr-gpui-x86_64-test.apk`: 19,923,852 bytes; SHA-256
  `7335c95d0e5f28cf5f83d1f765be12563cb88f7b3401ebfb122ecb8e4a71551c`
- ARM64 development signing-certificate SHA-256:
  `e2e65cfe85c68d82809922452a3aa9a3916a0b3d780b59f82bf8b9d81c9823de`
- The complete passing VM run used the exact x86_64 APK hash above; both ABIs have identical DEX, manifest and resources
- Source archive identity is recorded in the separate bundle summary after
  final documentation is saved; a ZIP cannot contain its own final checksum

This is a separate app from `dev.herdr.android.gpui.compact` 0.4.0. The old
development signing key was unavailable after the build workspace reset, so
this APK does not update Compact or migrate its saved profiles. Existing
Compact remains unchanged. Signing keys and machine-local configuration are
excluded from the source archive. Rebuilding with a different key cannot update
an installation signed with this preview's key.

## Build and deterministic verification

Completed evidence available at draft time:

- 24 native SSH/Iroh transport tests passed on the host
- The same 24 transport tests passed as an Android x86_64 test executable in
  the Android 16 / API 36 VM; all use isolated local test peers
- 34 final GPUI model/remote adapter tests passed, including credential/config
  bounds, queue/generation fences, focus acknowledgement and matching projection,
  boot/pane rejection, ordered semantic submit, pane text extraction, no phantom
  trailing row and bounded maximum JNI state
- Six synthetic SSH fixture integration tests passed, including actual TCP/SSH
  adapter setup, pin rejection, focus, input ordering, close/reopen without
  replay, stalled negotiation cancellation and wire bounds
- Seven socket-free real-Herdr fixture isolation/refusal tests passed
- 392 Java core assertions, 31 remote profile-policy checks and 49 display-policy checks passed
- 18 ELF-verifier, 13 native-freshness, six remote-fixture-configuration and five
  dynamic instrumentation-package-configuration regression tests passed

The shared `herdr-client` suite recorded 169 passing tests and three environment failures: two AF_UNIX listener tests were denied by the cloud sandbox, and an external `ssh -G` test rejected the host image's system SSH configuration ownership/permissions. System permissions were not changed. These failures remain documented rather than counted as passes.

The transport tests cover exact Iroh `/iroh/ssh` ALPN, a single stream of raw
SSH bytes, password and ephemeral private-key authentication, host-key inspection
without authentication, changed-key rejection before credentials, output bounds,
timeouts, cancellation and explicit draining before destroying client runtimes.
The Iroh peers bind to loopback and disable discovery, relays and port mapping.
These are transport results, not a complete production-app Iroh session.

Final delivery builds and package checks passed:

- Locked release ARM64 and x86_64 GPUI/wgpu/SSH/Iroh native builds
- Both native freshness records match current source, recipe, assets and library
- Strict undefined-symbol linking, API 29, 23 required JNI exports, bounded
  system-library dependencies and 16 KiB ELF load-segment alignment
- Official SDK AAPT2, Java 8 source/target compilation, D8 and APK packaging
- APK signature verification and 16 KiB ZIP alignment; the official ARM64 SDK
  signature check verifies APK signature scheme v3
- Identical DEX, compiled manifest and resource table across both ABIs; native
  libraries differ by architecture

Tools reported by final packaging: Java 21.0.12.1, Android SDK API 35 and Build
Tools 35.0.0. Java reports obsolete Java-8-target and existing deprecated-API
warnings. Transport and session-adapter formatting and strict Clippy passed during implementation; after the final row-separator change, the 34 adapter/model tests and both release builds were rerun. Historical HTTP bridge/storage suites were not rerun in this remote task.

Do not infer an aggregate pass from a build log that also contains earlier
attempts. Record each final command's exit status and the exact artifact it
checks. Full Gradle/Android Lint and desktop/Windows workspace checks are not
claimed by the manual SDK packaging path.

## Production Android app and GPUI testing

The separate instrumentation APK exercises the installed production app's Java
Activities, JNI, Rust session ownership, GPUI/wgpu SurfaceView and real TCP/SSH.
Its peer is the checked-in synthetic generation-1 fixture, compiled for Android
and run on guest loopback (`127.0.0.1`). It performs a real SSH handshake and
password login, then emits shared typed protocol messages without starting a
shell, terminal process or real Herdr daemon. Screens say
`SYNTHETIC SSH FIXTURE`.

The preliminary run `20261007T230213Z-1342` recorded 14 passed checks and then
**failed** at `Timed out: remote profile after lifecycle close`. Its observed
passes include direct JNI trust/authentication rejection, compatible probe,
Welcome, pane focus and decode, encrypted profile creation, explicit trust
dialog cancellation/acceptance, real login/pane-selection widgets, secure native
GPUI rendering, emulator physical-key ordering, Unicode app-IME input and OMP
palette Insert/Send + Enter round trips. The 14 include profile cleanup; they
are not 14 independent end-to-end scenarios. That run is preliminary evidence,
not a successful complete lifecycle suite or final delivery-APK pass.

An earlier guest run `20261007T225339Z-1194` failed at a missing `FLAG_SECURE`
assertion. Secure dialogs now set the flag before showing. Earlier host-route
attempts did not establish a working emulator-to-host fixture connection; the
guest-loopback route is explicitly selected and recorded. The first software
emulator exited with status 139 and CPU-thread/watchdog logs. The second exited
with status 137 while native rebuilds were active; the exact termination cause
is not established. These process exits are not recorded as app exceptions.
The third VM subsequently completed the passing runtime run documented below.
None of these attempts
is counted as a passing final run.

Final runtime evidence:

- `remote-ssh-runtime`, run `20261007T234327Z-2209`: **PASS**, 17 passed checks, zero skips; runner exit 0
- Exact tested x86_64 APK SHA-256: `7335c95d0e5f28cf5f83d1f765be12563cb88f7b3401ebfb122ecb8e4a71551c`
- Test APK SHA-256: `2891f3b1f374b7de66a2605ced272a0789ed49014e429d6d14f8d84cfbf00942`
- Actual guest loopback TCP/SSH with the synthetic typed Herdr peer, Android 16/API 36, 432×480 pixels at density 160
- All trust/authentication, GPUI rendering, physical-key dispatch, Unicode app-IME, OMP Insert/Submit ordering, HOME close, stale-input rejection, explicit reopen, credential re-entry, no replay and profile restoration checks passed in this single run
- Reopen observations reported an unlocked, nonsecure keyguard and visible host Activity; no recovery retry was needed in the passing run
- GPUI SurfaceView: 432×360 pixels; composed app captures: 432×480 pixels. Terminal, quick-command palette, edited synthetic command preview and reconnect captures were decoded and visually inspected
- The display was subsequently changed to 1080×1200 at density 400, but the executor interruption removed the VM control session before a frame run. That resolution is **not runtime-verified for this build**; no new full-resolution capture is claimed
- Captures and logs were saved before that interruption. The owned interactive VM/fixture session is no longer accessible; a further shutdown command could not be issued or verified

The preceding run `20261007T232726Z-1913` failed after 15 checks (including cleanup) because repeated SystemUI restarts restored the ordinary keyguard over the reopened host screen. The harness now observes keyguard/security state and permits only bounded normal wake/dismiss handling for a nonsecure lock; a secure lock remains a blocker. A later attempt `20261007T233749Z-2087` crashed in Android AlertController during dialog creation. Its harness accessed dialog decor off the UI thread before `show()` completed. Dialog readiness now runs on the main thread and uses existing decor. The complete passing rerun used unchanged production APK bytes. The crashed attempt did not produce a final report; only its synthetic test-app data was reset before retrying. Historical attempts are not added to the passing suite's count.

No new frame-only suite was completed in this task. A frame-only pass would not replace the complete runtime suite. PixelCopy/View.draw
captures retain `FLAG_SECURE` and do not include a third-party IME's own window.
Physical-key events from the emulator do not establish Titan keyboard mappings.

## Herdr and Iroh interoperability scope

Official Herdr 0.9.3 status capability output was checked: endpoint generation 1,
`surface_interest`, `presentation_effects_fence` and `health_check` are present.
The client still probes the actual host and preserves conditional
`--idle-timeout-v1` negotiation. A version/capability check is not an end-to-end
daemon compatibility result.

The isolated real-Herdr runtime fixture was attempted but could not start the
daemon: the cloud host denied `socket(AF_UNIX)` with EPERM, including a separate
normally approved probe. Real Herdr daemon interaction remains **unverified**.
No production host or user account was contacted or changed.

The app includes **Iroh + SSH**, using Iroh 1.3's N0 discovery/relay preset with
an ephemeral client identity. The intended receiver is
[`rustonbsd/iroh-ssh` 0.2.12](https://github.com/rustonbsd/iroh-ssh/tree/d0e9b6a823d25bf5b02757bbf9c17edeb39cfad5),
with ALPN `/iroh/ssh` and one raw SSH stream. A deployed receiver, public relay
or NAT traversal, full app Iroh session and physical-device network changes
remain **unverified**. The app does not install/configure that receiver.

The initial probe rejects a stopped session. However,
[Herdr 0.9.3's bridge](https://github.com/herdrdev/herdr/blob/v0.9.3/src/remote/host.rs)
can start a daemon if it stops between the probe and bridge execution. The
Connect dialog discloses this upstream race; attach-only behavior is not
guaranteed. The app does not reconnect or replay commands automatically.

## Security, input and remaining limits

- Explicit saved SHA-256 host-key trust precedes password/private-key
  authentication; changed keys fail closed and require independent verification
- Host settings and trust use AndroidKeyStore-encrypted storage excluded from
  backup; one-connection SSH credentials are not stored in profiles
- Authentication supports password and compatible existing Ed25519/ECDSA keys;
  RSA/DSA, SSH config/jump hosts, agent forwarding and interactive MFA are absent
- The selected pane and matching focus/projection/boot fences control input;
  disconnect discards pending client input
- `Input queued over SSH` means client queue admission. Binary semantic input
  has no daemon acknowledgement; a failed connection may leave an action applied
- Send + Enter is one ordered semantic batch. OMP confirmation still requires
  an idle, empty prompt and never clears existing input
- Remote display is bounded extracted plain text, not exact VT colors/cursor,
  graphics or unlimited scrollback
- x86_64 Android 16 software emulation does not verify ARM64 execution, Titan 2
  Elite hardware/keyboard, vendor IME, GPU performance, battery or mobile roaming
- API 29 is the declared compilation/linking minimum; earlier Android runtime
  versions were not executed in this verification
- The retained optional HTTP companion source/setup is a separate connection
  route; its historical validation is not a new live HTTPS test for this build

## Source and evidence bundle

The source archive preserves the Compact baseline's source/assets/license and
notice paths, overlays current files, and adds the new Android remote transport,
adapter, test fixture, profile UI and verification documentation. Original
optional companion sources and `setup.sh` remain included. The archive manifest
records baseline identity and per-file SHA-256 values.

Generated APKs/native libraries/object files, build and dependency caches,
signing keys, secrets and machine-local settings are excluded. Required baseline
source assets, binary wire fixtures and the Gradle wrapper remain included.
Evidence must be a separately reviewed selection of final reports, build checks
and synthetic screenshots; unfiltered task directories and fixture credentials
are not bundled. Historical failed attempts remain labeled if retained.

## Accompanying dependency notices

`android-remote/THIRD-PARTY-NOTICES.txt` accompanies the APK and source archive. It preserves 967 shipped notice/license files across the 560-package union of the two Android normal/build dependency trees, including Iroh's BSD attribution and ring's bundled notices. The shared Apache-2.0 text is provided where that license is offered. The inventory explicitly identifies 38 crate archives without a matching shipped notice file; declared license/authors/repository metadata is retained. This is a source-based notice inventory, not a certification of release compliance. Existing Herdr, GPUI Mobile and font license assets remain in the unchanged APK.
