# GPUI Android validation

Build date: 2026-10-07. The native Java preview is preserved separately.
This record concerns the real GPUI/Rust terminal activity.

## Runtime update: v0.2.1

Android 15/API 35 x86_64 VM testing found and fixed a real first-paint crash:
the generic `monospace` family could not resolve on Android. The application
now embeds and registers Noto Sans and Noto Mono before opening its GPUI window.
Their OFL-1.1 notices are included in source and APK assets.

The VM suite passed 13 runtime checks with no skips, including real GPUI frames,
physical-key event handling, IME composition/commit ordering, shortcut gestures,
background/resume, close/reopen, and fail-closed synthetic responses.
See [VM-TESTS.md](VM-TESTS.md) for exact artifacts, environment and limitations.
The exact final-artifact rerun also passed all 13 checks with no skips.

The transport was an injected synthetic HttpURLConnection; no actual TLS or
Herdr server was exercised. x86_64 VM execution does not establish ARM64 phone
rendering or hardware-keyboard behavior. The VM showed a missing-glyph box for
the test emoji; Latin, Greek and CJK glyphs rendered.

The following original build record is retained for the earlier v0.2.0 preview;
the runtime update and VM test record supersede its untested-runtime status.

## Implemented

- GPUI core and wgpu renderer pinned together at 0.3.7; mobile backend pinned to
  Longbridge revision `9075e3aa3eea812127f2c60ed66f0cd5798ff245`
- Supported Activity+SurfaceView host with a process-lived GPUI render thread
- Virtualized GPU-rendered terminal rows, horizontal/vertical scrolling, output
  follow control, local composition display, keyboard control and Back
- Native Android extra-key bar with immediate, ordered dispatch; all terminal
  text/keys/IME commits enter one bounded generation-fenced native FIFO
- Original Java HTTPS/authenticated companion transport and encrypted host store
- Android 10/API 29 minimum for GPUI; separate native-only preview remains API 26

## Checks actually completed

- Native Rust release compilation and ARM64 Android linking with NDK r30
- `--no-undefined` final application linking; only Android system-library
  dependencies, no unbundled shared C++ runtime
- ARM64 ELF, Android API note, required JNI exports, 16 KiB load/ZIP alignment
- Java compilation against the official public Android 35 SDK and lambda stubs
- Android SDK 35 resource compilation/linking, D8 bytecode conversion, APK signing
- APK v2/v3 signatures, CRC/DEX checksums, Java entry classes, native library and
  license-asset byte identity; independent SDK apksigner/zipalign verification
- Model tests for generation/session fencing, queue bounds, Unicode, immediate
  IME→Enter ordering, preedit blocking, deletion limits and local-only dismissal
- GPUI bridge Java checks for strict JSON/schema parsing, malformed input, byte
  limits, acknowledgments, physical-key text validation and one-shot launch grants
- Existing Java core self-test: 345 assertions
- Rust GPUI UI code type-checked on Linux and aarch64-linux-android; Clippy with
  warnings denied passed for those paths
- Native verifier regression cases and stale-source/incorrect-native rejection

Final counts: 15 Rust model tests (default and android-ui feature runs),
59 GPUI bridge Java checks, 345 existing Java-core assertions, and 11 native
verifier regression cases. The APK contains 104 application classes and the
native library exposes all 15 required JNI symbols.

APK SHA-256:
`7fbbd2da162649fb8b107fda5eaccd2c6645342c032c010cac07cf1359ed360d`

Native library SHA-256:
`7321a19422cf6b1dc90916ef4614569532527bafb34fd7719c1c7ea9451cac8a`
These are build, deterministic-model and static-review checks, not device tests.

## Important issues caught during implementation

1. GPUI Mobile's NativeActivity path retains the first platform globally. The
   integration instead uses its existing SurfaceView host, designed for repeated
   Activities and surface attachment.
2. Strict linking exposed direct use of `android_get_device_api_level`, whose
   exported libc symbol begins at API 29. Raising both native and manifest minimum
   to Android 10 prevents packaging a library that cannot load on older systems.
3. Deferred IME processing could be overtaken by physical Enter. The app now
   routes commits, deletions and physical input synchronously into the same FIFO;
   local preedit never reaches the host.
4. Deferred GPUI touch macros could be overtaken by direct input. Remote-action
   shortcut controls are native Android controls with immediate touch dispatch,
   avoiding that cross-thread action-order ambiguity without patching upstream.
5. Physical character mapping now uses the originating KeyEvent's Unicode value,
   preserving the device's configured keyboard layout. Unsupported dead-key
   composition fails closed and directs the user to the software IME.

## Not verified / remaining limits

- At the original v0.2.0 build checkpoint no emulator or device had been used.
  The later x86_64 VM pass is described above. Physical ARM64 hardware, actual
  keyboard delivery and device-specific drivers remain unverified.
- No real Herdr/Claude endpoint was contacted. The included bridge/API protocol
  is source-grounded and tested as described in the earlier native preview.
- Normal Gradle assemble/lint was attempted but blocked before startup by this
  environment's JVM networking (`Network is unreachable` fetching Gradle 8.9).
  Official SDK command-line compilation/packaging succeeded; Android Lint is
  not claimed as passed.
- Upstream surface release waits at most two seconds. Stress-test rapid first
  surface creation/destruction, background/resume and repeated activity opening;
  cross-compilation alone cannot validate renderer/driver synchronization.
- This is live companion text-snapshot rendering, not the desktop binary VT-cell
  protocol: terminal colors, cursor fidelity, resize, graphics and scrollback
  are not implemented. Host dimensions remain authoritative.
- Only ARM64 is packaged. Android 10+ and appropriate Vulkan/GL capabilities are
  required. No Play Store distribution, production signing or background service.
- No Tailscale/Iroh changes or remote publication. SDK/NDK installation stayed
  task-local and followed the user's explicit acceptance of Google's agreement.

## Device acceptance pass

Use a non-production Herdr terminal. Open the native host manager, connect, open
a pane, and tap GPUI. Then check:

- First frame, repeated Back/reopen, host switching and absence of stale output
- Rotation, background/lock/resume, surface loss and low-memory process death
- ASCII/Unicode/emoji, language-layout hardware keyboards, key repeat, Ctrl/Alt,
  and Enter/Backspace/arrows; unsupported modifiers must not become bare keys
- Multi-stage software IME preedit, commit, delete and dismissal; preedit must
  remain local and committed text must precede subsequent Enter
- Rapid extra-key/macro + hardware/IME input, scrolling through the key strip,
  touch cancel, accessibility click, queue overflow, connection failure and no
  replay after switching/resuming
- Wrong tokens, redirects and untrusted certificates; no credentials in logs,
  screenshots, Intents, saved state or Android backup
- Android 10 and current Android releases, including a 16 KiB-page device
