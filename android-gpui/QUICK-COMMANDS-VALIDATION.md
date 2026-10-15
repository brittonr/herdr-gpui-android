# Oh My Pi quick commands validation — 0.4.0

Date: 2026-10-07 UTC. See [usage and preset sources](QUICK-COMMANDS.md).

## Artifact and upgrade

- Version `0.4.0-gpui-compact`, versionCode 4
- Application ID `dev.herdr.android.gpui.compact`; minimum API 29, target API 35
- Delivered ARM64 APK: 10,654,607 bytes
- ARM64 SHA-256: `b3fb40a337b43e4502dd50e05530c3bf5d502129f24aec2d3724beff9559fcfb`
- Tested x86_64 APK SHA-256: `cf43d74bbfb1b2f8fd187cd1a0532b187aa77f26d4ecd1f9dabe2125f4b8d81e`
- ARM64 signer SHA-256: `4269fb34130a14c46dd6af9c5e80e3479558f90ca342b03688f8c2fef0568926`

The package and ARM64 signing certificate match Compact 0.3.0. Install as an
update to preserve saved hosts. The earlier non-Compact preview remains a
separate app. Private development signing keys are excluded from the source
bundle. A rebuilt APK signed with another key cannot update this installation.

Both ABI builds have identical DEX, compiled manifest and resource table. Their
native build records contain the same source, recipe and font hashes. The actual
libraries differ by CPU architecture; running x86_64 does not verify ARM64 runtime.

## Build and deterministic checks

Passed on the delivered production source:

- Locked release cross-compilation for aarch64 and x86_64 Android, real GPUI/wgpu
- Strict undefined-symbol, API-29, 16 JNI-export, native dependency and 16 KiB
  load-segment checks; source/asset freshness for both ABIs
- Official SDK AAPT2, Java compilation, D8, APK signature and 16 KiB ZIP alignment
- 29 Rust host UI/model/font tests (including the 24 core/model tests)
- Host and Android-target Clippy with warnings denied
- 392 Java core assertions, 390 Java GPUI boundary checks, 58 storage checks

The new tests cover strict single-line and UTF-8 bounds, optional legacy storage
fields, explicit empty command lists, immutable pin state, stale-host rejection,
generation-fenced Submit queueing, JSON boundaries and edited preset warnings.

Tools: Rust 1.96.1, Android NDK r30, SDK API 35/Build Tools 35.0.0, Java 21.
Java emits obsolete Java-8-target and existing deprecated-API warnings. This is
the official SDK manual packaging path. Full Gradle/Android Lint was previously
blocked by the cloud JVM's `Network is unreachable` error fetching Gradle 8.9;
it is not reported as passed. Desktop Herdr/Windows integration was not rerun.

## Independent review

Review checked authentication, TLS, encrypted storage, modal ownership, lifecycle,
single-line bounds, ordered text acknowledgement before Enter, target generations,
and accidental-command prevention. Auth/TLS classes and manifests are unchanged
from Compact 0.3.0. Two review findings were fixed and independently rechecked:

- A retained Delete-Cancel callback cannot replace a newer dialog after resume
- Canceling an accepted background save and immediately reopening Cmds cannot
  leave a stale disabled list. The save finishes; the next opening shows it

## Android runtime checks

The complete runtime run `20261007T193858Z-896` passed **44 checks, one unavailable
keyboard-mapping check** on the exact x86_64 APK above. Evidence folder:
`quick-commands-full-01`. This repeats the Compact layout/font/scroll, one-shot
Ctrl reset/cancellation/repeat, physical key, IME composition/privacy/order,
options-modal isolation, lifecycle/reopen, bounded input and connection-error
regressions alongside all new command checks. It used 432 × 480 pixels at
logical density 160 (432 × 480 dp).

The emulator's actual `KeyCharacterMap` has no printable Alt-letter symbol,
so that one actual mapping check is explicitly skipped. A labeled synthetic
Unicode KeyEvent covers symbol forwarding and the Terminal Alt override. This
does not verify Titan keyboard firmware.

The quick-command smoke run `20261007T193006Z-744` passed **16 checks, zero skips**
on the exact x86_64 APK above. Evidence folder: `quick-commands-smoke-03`.
It used Android 16/API 36 in software emulation at 432 × 480 pixels, logical
density 160 (432 × 480 dp), with the production GPUI SurfaceView and native FIFO.

Passed behavior:

- Real AndroidKeyStore encrypted per-host Unicode label/text/pin round trip;
  profile and shortcut edits preserve commands; deleted/retargeted hosts reject
  stale saves. Original synthetic profile state is restored and checked
- Native Insert and Submit ordering, one text acknowledgement before Enter,
  no Enter after a rejected acknowledgement, no replay
- Real HOME after a valid synthetic text response suppresses Enter; resume cannot
  replay the command
- Actual Cmds dialogs add, edit, pin, unpin, save, reopen and delete entries;
  Cancel and local editing cause zero terminal requests
- Insert sends text only. Send + Enter requires an empty/idle-prompt check;
  editing resets it, and duplicate taps cannot resend
- Physical Activity input and an old GPUI IME connection are isolated by preview;
  IME Done and unconfirmed Send do not submit a command implicitly
- Delayed accepted save followed by Cancel/reopen remains usable; stale Insert
  and Delete-Cancel callbacks after HOME/resume cannot target or replace new UI

The final full-resolution run `20261007T194656Z-1067` passed **six checks, zero
skips** at **1080 × 1200 pixels, logical density 400**, again 432 × 480 dp.
Evidence folder: `quick-commands-full-resolution-01`. It captured the actual
GPUI frame, native palette, editable preview and scrolled Save/Delete actions.
The shared preview actions remained visible and reachable by scrolling. Physical
Enter and Numpad Enter were dispatched to both Name and Command editors; neither
generated a request nor dismissed the preview. Initial IME-hidden state and
system-inset geometry also passed. This final frame fixture adds those editor
key assertions; it does not repeat the complete runtime suite at full resolution.

The first two quick-command smoke attempts were not passes: SystemUI restarted
and reinstated the emulator keyguard, preventing a drawable Activity surface.
The harness now performs ordinary wake and transient keyguard-dismiss actions at
Activity transitions, as its launch script already did. No security setting or
production behavior was relaxed. The third attempt completed successfully. All
attempt evidence is retained separately.

## Scope and limits

The fixture replaces the existing `CompanionClient.ConnectionFactory` with an
in-memory synthetic HTTPS response source. It uses no actual host or network
socket and executes no real OMP commands. It verifies app request ordering and
handling, not TLS handshakes, certificate trust, live Herdr or OMP semantics.
Presets are source-verified against OMP 18.8.3; the user's installed version is
not detected. The app cannot inspect OMP's prompt buffer, so Send + Enter relies
on the explicit user confirmation and never erases prior input automatically.

Physical Titan 2 Elite, its keyboard firmware/Alt mappings, real external IME UI,
ARM64 device runtime and a real companion connection remain unverified. Density
400 at 1080 × 1200 is a representative logical-density choice, not a verified
Titan firmware value. Native dialog images are actual `View.draw` captures and
GPUI images use `PixelCopy`; FLAG_SECURE remains enabled. Dialog captures show
the dialog window, not the external keyboard or entire system display.

The existing snapshot renderer limits remain: no full VT color/cursor/graphics,
PTY resize or unlimited scrollback. This change adds no OMP RPC or auto-discovery.
