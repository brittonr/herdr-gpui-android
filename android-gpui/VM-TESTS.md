# Android VM test results — v0.2.1

**Result: 13 runtime checks passed, zero skipped, on the exact final x86_64 APK.**
Date: 2026-10-07 UTC. Final run ID: `20261007T051257Z-772`.

## What actually ran

- Official AOSP Android 15 / API 35 x86_64 system image, revision 2
- Android Emulator 37.2.12, CPU software emulation (no KVM), SwiftShader Vulkan
- 2 virtual CPU cores, 2 GiB RAM, 480 × 800 display, density 160
- Actual GPUI/wgpu shared library, Android Activity, SurfaceView, JNI and input FIFO
- A separate instrumentation APK injected an in-memory `HttpURLConnection`
  fixture through the existing connection-factory constructor. Production APK
  contents, TLS validation and display-security flags were not weakened.
- No real user host, account, token or terminal was connected

Initial boot took approximately seven minutes without hardware acceleration.
The VM and its ADB server were isolated in a task-local execution session.

## Bug found and fixed

The first runtime attempt crashed on first GPUI paint: its generic `monospace`
font could not resolve on Android, and GPUI's desktop fallback families were
unavailable. Successful compilation had not caught this.

Version **0.2.1-gpui-preview, versionCode 2**, embeds unmodified Noto Sans
Regular/Bold and Noto Mono Regular, registers them before opening the window,
and references their exact family names. OFL-1.1 notices accompany the source
and APK. Four backend regression tests run with zero system fonts and verify
family/default fallback resolution, shaping/rasterization, fixed-width ASCII,
and safe unsupported-glyph handling.

The candidate rerun passed all 13 runtime checks. A second run against the exact
final x86_64 artifact independently passed the same 13 checks with no skips.

## Passed runtime checks

1. GPUI Activity starts, JNI loads, and the real client/pump parses and publishes a fixture screen
2. PixelCopy captures a non-uniform, visually inspected native GPUI frame while FLAG_SECURE remains enabled
3. Physical-device KeyEvent handling preserves `text a → Enter → Ctrl-C` through JNI, native FIFO and client
4. Actual InputConnection composition remains local; a Unicode commit precedes Enter exactly once
5. Shortcut input is blocked while IME preedit is active
6. Extra-key DOWN/CANCEL/UP sends no command
7. A valid extra-key touch sends exactly one Ctrl-C
8. Android HOME/resume closes the old client, changes generation, drops queued input and rejects stale input
9. Close/reopen reuses the process-lived GPUI renderer through a new one-shot launch grant
10. Synthetic HTTP 401 closes input without retries or subsequent commands
11. Synthetic timeout closes input without retries or subsequent commands
12. Malformed screen data closes input without retries or subsequent commands
13. Rejected mutation acknowledgment closes input after exactly one attempted command

The healthy fixture recorded exactly six POSTs:
`text a`, `Enter`, `Ctrl-C`, `text VM α中🧪`, `Enter`, `Ctrl-C`.
Each read-error fixture recorded one GET and no POST. The bad-acknowledgment case
recorded one GET and one Enter POST. No final-run native panic was logged.

## Images and evidence

The evidence ZIP includes the JSON report, synthetic request records,
instrumentation output, APK hashes, logcat, the original crash summary and
18 screenshots. `*-gpui.png` files are direct app-owned SurfaceView PixelCopy
captures. `*-app.png` combines the native surface with the Java view hierarchy;
it deliberately excludes the separate Android keyboard window.

Connected, composition, reopened and error-state screenshots were visually
inspected. Latin, Greek and CJK text render. The test-tube emoji **🧪 renders as a
missing-glyph box** on this VM; full emoji coverage is still a known limitation.
The VM's lack of dual-source blending disables subpixel antialiasing; it did not
prevent ordinary rendering.

## Exact artifacts

Tested x86_64 APK SHA-256:
`56d7a4554a12ee92ebf81e22c045b09c726b35d7f0160c55f9683c168663a67d`

Instrumentation APK SHA-256:
`a0a0ca651267261007a6e867514650e030195407b08da856b154ad65dddff556`

Corrected ARM64 phone APK SHA-256:
`d01fd8aa71cc97089ea73ca2781c2fea4171235f68f52880c828c8c61bd9a9c6`

The corrected ARM64 build uses the same production source and font assets as the
VM-tested x86_64 build. DEX, compiled manifest/resources and font notices match
between corrected ABIs. Native code is architecture-specific. ARM64 linking,
JNI symbols, source freshness, signatures and 16 KiB alignment were verified.
The ARM64 APK retains the earlier app ID and signing certificate, so it updates
the existing Herdr GPUI Preview without requiring a separate installation.

## Other verification

- 15 default Rust model tests and 19 Rust UI/model tests passed
- Host and ARM64 Android Clippy passed with warnings denied; formatting passed
- 59 Java GPUI boundary checks and 345 Java-core assertions passed previously
- 36 native/ABI/build-record/APK verifier regression tests passed
- Both APKs passed official SDK compilation/packaging, v2/v3 signatures and
  independent `zipalign -c -P 16` checks

## Not established by these tests

- Actual TLS handshakes, network behavior or a real Herdr daemon/Claude session
- ARM64 phone execution, device GPU drivers, performance or real keyboard firmware
- Third-party keyboard UI automation; tests call the app's real InputConnection
- Universal emoji coverage, full VT-cell colors/cursor/graphics/resize/scrollback
- Android versions other than API 35 at runtime; the app minimum remains API 29
- Full Gradle/Android Lint: the environment's JVM networking prevented Gradle
  distribution download; official SDK command-line builds were used

Reproduce with the opt-in x86 build described in [VM-BUILD.md](VM-BUILD.md) and
[the instrumentation harness](vm-tests/README.md). Tests require an explicitly
named disposable emulator. They never implicitly select a physical device.

## Cleanup

After the final report, images and installed APK hashes were verified, the test
emulator and its owned ADB server were stopped; task-local AVD data/logs remain.
The emulator console accepted `emu kill`, but its host process returned 139
during teardown. This was a separate post-test emulator-host shutdown failure,
not an app-process failure during runtime-03. It is preserved in the evidence.
