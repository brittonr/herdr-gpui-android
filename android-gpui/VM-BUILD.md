# x86_64 VM build evidence

Build date: 2026-10-07 UTC. This records the font-fix rebuild's compilation and
packaging. VM runtime verification is tracked separately; ARM64 device execution
has not been established.

## Artifact

- APK: `app/build/outputs/apk/manual/herdr-gpui-x86_64-test.apk`
- Size: 11,387,788 bytes
- APK SHA-256: `56d7a4554a12ee92ebf81e22c045b09c726b35d7f0160c55f9683c168663a67d`
- Native library SHA-256: `797763576650f38a7b6518c477f84894fe7a61e2183443911cbfcb3d148ccf1f`
- Application ID: `dev.herdr.android.gpui`
- Version code: 2; version name: `0.2.1-gpui-preview`
- Minimum Android API: 29; target and compile API: 35
- Only packaged native entry: `lib/x86_64/libherdr_gpui_android.so`

This uses the original application and pinned GPUI dependency graph with the
bundled-font correction: Noto Sans Regular/Bold and Noto Mono Regular are
registered before creating the GPUI window. Its DEX and compiled resource table
are byte-identical to those in the original ARM64 APK. The manifest contains the
new app version. The native library was compiled for x86_64, with no stand-in
library or replacement UI; the font license notice is present in the APK assets.

The initial version-code-1 x86 APK failed in the actual VM at first GPUI paint
because the backend could not resolve the generic `monospace` family. Its
APK SHA-256 was `09e21b779939aa6a46476214260432dd08ede43ae673cee7a19f442fb467765b`.
That APK, native library, build record, original build evidence and build logs
were preserved separately before the correction. Packaging checks alone did
not establish runtime correctness.

## Commands actually run

The build used the task-local Rust 1.96.1 toolchain, the official x86_64 Android
Rust standard library, Android NDK r30, SDK API 35 and Build Tools 35.0.0.
The standard-library archive was checked against the official Rust 1.96.1
distribution manifest's SHA-256 before installation into the task-local toolchain.

```sh
export HERDR_ANDROID_ABI=x86_64
export CARGO_BUILD_JOBS=3
./scripts/build-native.sh
./scripts/build-verification-apk.sh
python3 scripts/test-native-verifier.py
python3 scripts/test-native-build-record.py
python3 scripts/test-apk-verifier.py
bash -n scripts/build-native.sh scripts/build-verification-apk.sh
```

SDK and NDK paths were set through `ANDROID_HOME` and `ANDROID_NDK_HOME`; the
existing task-local `CARGO_HOME` was reused. No global Cargo configuration was
written. The initial release native build finished successfully in 2 minutes
37 seconds. The final incremental font-fix build finished in 3.54 seconds.

## Passed checks

- Real `cargo rustc --locked --target x86_64-linux-android --release
  --features android-ui -- -C link-arg=-Wl,--no-undefined`
- x86_64 ELF machine type, API 29 native note and four 16KB-aligned load segments
- All 15 required JNI exports; no writable/executable load segment
- Dependencies limited to `libandroid.so`, `libc.so`, `libdl.so`, `liblog.so`,
  and `libm.so`; no unpackaged C++ runtime dependency
- Native source/recipe/API/architecture/asset freshness record and SHA-256
- All bundled font files and their license are present in the freshness record
- The font license notice's packaged bytes match the source asset
- Java compilation and D8 conversion; 104 app classes in one DEX file
- Compiled Android resources, DEX checksums, ZIP CRC and single-ABI package checks
- APK v2/v3 signatures, independently checked with SDK `apksigner`
- SDK `zipalign -c -P 16 -v 4`, including native ZIP alignment
- 18 ELF parser tests, 12 build-record tests, and 6 single-ABI APK parser tests
- Asset changes, additions, removals, license changes and changes during a build
  all invalidate the native freshness record
- The default ARM64 verifier rejects the x86_64 APK
- Unsupported ABI input is rejected
- The old ARM64 freshness record is rejected after build-recipe changes
- Before replacing generated ARM64 outputs, the original ARM64 library, APK,
  badging report and build record were preserved; the saved APK and library
  still match their pre-change hashes

The compiler emitted Java 8 source/target obsolescence and existing deprecated-API
warnings. There were no native compilation or link errors.

## Isolation and limits

ARM64 remains the default ABI in both scripts and Gradle. x86_64 uses separate
Rust target, JNI staging, freshness record, package work directory, development
signing key and output APK. The ARM64 files were untouched during x86-only work.
After the VM displayed real GPUI frames and passed the core input/lifecycle
tests, a separate corrected ARM64 build regenerated its native library and
freshness record from the same source and assets. The original failed artifacts
remain preserved rather than being discarded.

## Corrected ARM64 build

- APK: `app/build/outputs/apk/manual/herdr-gpui-debug.apk`
- Size: 10,617,743 bytes
- APK SHA-256: `d01fd8aa71cc97089ea73ca2781c2fea4171235f68f52880c828c8c61bd9a9c6`
- Native library SHA-256: `27cf5926c8aa450e56fb0ac8e2a901279f7287b9b3ff7c47d095d1b975d76706`
- Version code: 2; version name: `0.2.1-gpui-preview`
- Same application ID and verified identical signing certificate to the
  original ARM64 APK, allowing an update install

The incremental ARM64 release native build finished in 3.32 seconds using
`HERDR_ANDROID_ABI=arm64-v8a`. Actual compilation, `--no-undefined` linking,
API-29 ELF/JNI validation, 16KB ELF alignment, native freshness, APK structure,
APK v2/v3 signature verification and SDK `zipalign -c -P 16 -v 4` all passed.
The ARM64 and final x86_64 native records have exactly identical source, asset,
license and build-recipe hashes. Their DEX, manifest, compiled resources and
font-license asset are byte-identical.

No ARM64 runtime testing was performed. The x86_64 VM evidence supports the
shared font setup and application behavior, not ARM64 drivers or device behavior.

The new Gradle ABI filter was reviewed but a full Gradle assemble/lint run was
not performed in this build pass. The APK was produced by the official SDK
verification packager. Runtime results belong in a separate VM test record.
Successful x86_64 execution cannot establish ARM64 device rendering, performance,
driver compatibility, lifecycle behavior or IME correctness.
