# Compact preview validation — 0.3.0

Date: 2026-10-07 UTC. Target: Unihertz Titan 2 Elite.
See [device facts, installation and limitations](TITAN-2-ELITE.md).

## Artifact identity

- Version `0.3.0-gpui-compact`, versionCode 3
- Application ID `dev.herdr.android.gpui.compact`; label Herdr GPUI Compact
- Minimum API 29; compile/target API 35
- Phone APK: ARM64, 10,634,127 bytes
- ARM64 SHA-256: `ceeb7c363e874694ce742190ca7db4d900e66efd11e79fb4e604d8de2d745e48`
- Tested x86_64 APK SHA-256: `51b8f76bde26261764a170572a31c069c5cbceb045a0fe39cb5028311b47527c`

The new app installs alongside the earlier preview. Its private development
signing key differs because the previous cloud workspace was recycled; it does
not replace or erase the earlier application's saved hosts. Enter hosts again
in the new app. Private signing keys are excluded from the source bundle.

Both ABI builds have identical DEX, AndroidManifest.xml, resource table and font
license bytes. Their native freshness records contain identical source, recipe
and asset hashes. The actual native libraries differ by architecture.

## Build and deterministic checks

Passed on the final production source:

- Actual locked release cross-compilation for x86_64 and aarch64 Android
- Strict `--no-undefined` linking, API-29 note, 16 required JNI exports,
  16 KiB ELF load alignment, and bounded native dependency checks
- Native source/asset freshness validation for both ABIs
- Official SDK AAPT2, Java 8 source/target compilation, D8 and APK packaging
- Official SDK APK signature verification and `zipalign -c -P 16 4`
- 25 Rust host UI/model/font tests, including 320 dp control geometry through
  36 dp effective text, bundled font widths and unchanged-state deduplication
- Host Clippy and Android-target Clippy with warnings denied; Rust formatting
- 345 Java core assertions and 59 Java GPUI boundary checks
- 49 display policy checks and 5 dynamic test-package configuration checks
- 36 ELF/APK/freshness verifier regression tests; shell syntax checks

Tools: Rust 1.96.1, Android NDK r30, SDK API 35/Build Tools 35.0.0, Java 21.
Java emitted obsolete Java-8-target and existing deprecated-API warnings.

A full Gradle/Android Lint invocation was attempted but could not download
Gradle 8.9: the cloud JVM reported `Network is unreachable`. Official SDK
compilation and packaging succeeded. Desktop workspace/Windows CI and real
Herdr integration tests were not rerun for these Android-only changes.

## Independent review and fixes

Java review covered authentication, TLS, encrypted storage, modal isolation,
input ordering, one-shot Ctrl and physical Alt behavior. Findings were fixed:

- Alt+named terminal keys reject rather than stripping the modifier
- Device-symbol Alt sends validated Unicode text rather than being reinterpreted
  as an Alt command by Rust
- Ctrl and View touch callbacks run synchronously with physical input
- One-shot Ctrl resets on input, focus loss, settings and lifecycle changes;
  held-key repeats cannot turn into ordinary text
- Old IME connections are fenced across resets and settings; modern mutation
  overloads cannot bypass those checks; unsupported document-editing operations
  are rejected
- Modified IME Enter/Backspace are rejected rather than losing modifiers
- Activity and options dialog retain FLAG_SECURE; host/auth/TLS/storage and
  launch authority remain unchanged from the preceding preview

VM testing also exposed an upstream focus/restoration request that could show
the soft keyboard without a tap. The final build permits one show only after an
explicit GPUI Keyboard action, validates its rendered connection generation, and
rejects unrequested or stale callbacks. Initial window focus also explicitly hides
a lingering IME. The independent review verified the final gate and generation
capture.

Rust review found a Follow regression when the composition bar changed terminal
height. The final build also treats composition and truncation-bar visibility as
geometry changes, keeping the last row anchored. Follow does not jump merely
because a status or unchanged snapshot is published.

## Full-resolution visual evidence

The final delivery APK passed four frame/geometry/initial-IME checks, zero skipped,
in run `20261007T183050Z-1330`. Actual Android 16 / API 36 x86_64 VM frames were
captured at **1080 × 1200,
logical density 400**. Density 400 is a representative assumption, not verified
Titan firmware density. The resulting logical screen is 432 × 480 dp.

The actual GPUI SurfaceView occupied 1080 × 900 pixels after native controls and
system insets. Full-resolution PixelCopy images retain FLAG_SECURE and show:

- Compact default: header bottom 63 px, footer top 759 px, first glyph height 21 px
- Standard: header bottom 88 px, footer top 739 px, first glyph height 24 px
- Compact 18-point selection: first glyph height 32 px
- Compact 11-point selection: first glyph height 20 px

The default frame is also verified on the final delivery APK. The standard and
font-size comparison images were captured during iteration; the complete final
suite rechecked those choices at the equivalent logical geometry. Images were
decoded, measured and visually inspected. The compact header,
scrollable extra keys and controls fit. Emoji coverage remains incomplete; the
test-tube emoji is a missing-glyph box on this VM.

An earlier exploratory full-resolution suite was deliberately stopped after these visual checks
because CPU software emulation was too slow for the full sequence. It is **not**
a completed full runtime-suite pass. The final frame-only run above completed
successfully and is recorded separately. Earlier setup runs were interrupted or failed
before app checks because a session disappeared, the control client timed out,
or emulated external storage could not create an evidence directory. A harness
close helper also needed to handle an already-paused Activity during a SystemUI
restart; that helper was corrected before the final pass. Test
outputs now use private app storage; production app behavior was not relaxed.

## Complete runtime suite

**32 checks passed; one hardware-mapping check is unavailable in the VM.**
Final full run: `20261007T182628Z-1224`. The APK hash above is the exact installed
x86_64 build used for this run.

Passed behavior includes profile/font changes, physical text and control-key
ordering, one-shot Ctrl cancellation/repeats, unsupported modifier rejection,
explicit keyboard gating, Unicode IME ordering, stale IME isolation, oversized
input rejection, pause/resume without replay, saved preferences/reopen, long-output
composition, and fail-closed 401/timeout/malformed-response/bad-acknowledgment cases.

The emulator has no printable Alt-letter symbol mapping. That actual keyboard-map
case is recorded as skipped. A clearly labeled synthetic Unicode KeyEvent verifies
that symbol input is forwarded as text and remains stable across layout changes;
the explicit Terminal Alt override sends the matching chord. This is not a Titan
firmware test.


The full suite uses the same final x86_64 APK at **432 × 480 pixels, density 160**,
preserving the same 432 × 480 dp geometry. Android 16, software CPU emulation and
SwiftShader remain in use. Runtime result and checked paths are recorded in the
accompanying evidence report.

## Limits

- This is an x86_64 AOSP VM, not Titan hardware, its physical keyboard firmware,
  ARM64 GPU, 120 Hz performance, battery behavior or vendor IME
- The test transport is an in-process synthetic companion; it does not test
  live HTTPS handshakes or a real Herdr/Claude host
- The app still uses bounded text snapshots, not full VT-cell colors, cursor,
  graphics, resize or scrollback
- Android versions below 16 were not executed in this pass; API29 is a declared
  minimum supported by compilation/linking, not a new runtime verification
- System phone/Bluetooth services produced ANRs in the slow emulator; this is
  another reason these runs are not performance benchmarks

## Shutdown

The final installed x86_64 APK was retrieved from the VM and its bytes matched
the recorded delivery hash. Both reports and all 36 final-run PNG files were
preserved and decoded successfully. The owned emulator and its helper processes
were then stopped. Its host process returned 139 during requested teardown,
after all tests/evidence completed; this is a separate emulator shutdown failure,
not an app failure in either passing run. The test shell was closed.
