# Building the actual GPUI Android preview

This defaults to an ARM64 Android build of the Rust GPUI UI, hosted by an Android SurfaceView and GPUI Mobile’s process-lived render thread.
The separate `../../android` client remains the Java-native preview.
No script creates a replacement `.so` when Rust compilation fails. The packager
checks Rust source, build recipe, minSdk, and native library hashes against the
last successful build record, and rejects stale native binaries.

## Native build

Requirements: Rust 1.96 or newer, `aarch64-linux-android` Rust standard library,
an installed Android NDK with its license already accepted, Python 3, and a
Linux x86_64 or macOS build host. The GPUI APK requires Android 10/API 29+
because its upstream backend directly calls an API-29 native function; the
separate Java-native preview retains its older-device support. Install the Rust target with
`rustup target add aarch64-linux-android` if using rustup. The scripts neither
download the Android SDK/NDK nor accept Google's license.

From `android-gpui/`:

```sh
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk
./scripts/build-native.sh
```

This builds the pinned standalone Rust workspace with `--locked`,
`--features android-ui`, Android API 29 (Android 10+), release optimization, 16KB ELF segment alignment,
and `--no-undefined` linker validation. It copies the verified library into
`app/src/main/jniLibs/arm64-v8a/libherdr_gpui_android.so`. A dependent NDK shared
C++ runtime is included only if the built ELF declares it. Rust tools and
`CARGO_HOME` may be task-local; no global Cargo configuration is written.
`CARGO_TARGET_DIR` optionally overrides the default `build/rust` output. The application
link rejects unresolved symbols; GPUI Mobile’s intermediate dependency cdylib is
not packaged, since its activity entry is supplied by the final application.

Before a full native build, this check can run without an NDK linker:

```sh
cargo check --manifest-path rust/Cargo.toml --locked \
  --features android-ui --target aarch64-linux-android
```

## Normal APK build

With an Android SDK installed and `ANDROID_HOME`/`local.properties` configured,
run `./gradlew assembleDebug` after the native build. Never use a debug key for
release distribution. The GPUI preview has a distinct application ID so it can
coexist with the Java-native preview.

## Isolated Linux verification APK

The verification packager prefers an installed SDK when `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`) is set. It uses that SDK's API 35 `android.jar` and Build
Tools 35.0.0 AAPT2, D8, and apksig. This validates against the public Android
API surface without Gradle's dependency resolution:

```sh
export ANDROID_HOME=/absolute/path/to/android-sdk
./scripts/build-verification-apk.sh
```

If a full Android SDK is unavailable and those variables are unset, the verification packager uses official
Google Maven AAPT2, D8 and apksig plus Robolectric's API 35 framework JAR as a
build-time reference. Downloads are pinned by SHA-256 and cached locally:

```sh
./scripts/build-verification-apk.sh
```

`HERDR_BUILD_CACHE=/absolute/cache/path` can reuse those Maven artifacts. It
compiles the shared Java sources and the GPUI host, packages the real Rust
library, signs with a locally generated development-only key, verifies APK
v2/v3 signatures, and checks:

- ARM64 ELF type, SurfaceView lifecycle/input JNI exports, known system dependencies
- 16KB ELF load segments and 16KB ZIP alignment for native libraries
- Compiled resources, ZIP CRC, DEX checksums, required Java host classes

Output: `app/build/outputs/apk/manual/herdr-gpui-debug.apk`, with a sibling
AAPT2 badging report. This is a build-and-packaging check, not Android Lint,
Gradle verification, emulator execution, or proof of native rendering/IME.
No script installs on a device, connects to a user's daemon, or publishes.

Parser regression tests use in-memory non-executable fixtures:

```sh
python3 scripts/test-native-verifier.py
python3 scripts/test-native-build-record.py
python3 scripts/test-apk-verifier.py
```

Release automation names its per-commit draft preview from the source version:

```sh
python3 scripts/preview-tag.py <40-character-commit-sha>
```

`scripts/test-preview-tag.py` covers that derivation, and
[RELEASE.md](../RELEASE.md) describes the drafts it names. The tag is validated
as a git ref before any release is created, and an automatic run leaves it
untagged until someone chooses to publish the draft.

The native verifier intentionally rejects missing entry points, foreign CPU
architectures, 4KB-only load segments, and writable/executable segments. The
APK packager refuses to proceed without the real compiled Rust library.

## x86_64 VM test build

To test the same application in an x86_64 Android VM, install the official
`x86_64-linux-android` Rust standard library into your chosen toolchain, then run:

```sh
export HERDR_ANDROID_ABI=x86_64
export CARGO_BUILD_JOBS=3
./scripts/build-native.sh
./scripts/build-verification-apk.sh
```

The scripts use a separate `build/rust-x86_64` target directory,
`app/src/main/jniLibs/x86_64` staging directory, `build/native-build-x86_64.json`
build record, `build/manual-x86_64` packaging directory and
`app/build/outputs/apk/manual/herdr-gpui-x86_64-test.apk` output. Each APK contains
only the explicitly selected ABI. Gradle also defaults to ARM64 and honors the
same variable. Architecture, API 29, JNI exports and 16KB alignment checks remain
mandatory for both architectures. This test build has the same application ID
but its own development signing key, and cannot update an app signed by the
ARM64 packager's key without uninstalling it first. It should be installed only
on the selected disposable VM. x86_64 execution is not ARM64 device validation.

Build records include architecture and source/recipe hashes. Changing the
contents, names or presence of files under `rust/assets/` (including font
licenses) also invalidates the native freshness record. Changing the
build scripts invalidates an older native freshness record; rebuild its selected
architecture before repackaging. Existing output APKs and ARM64 libraries are
not replaced when building x86_64.

References: [Android 16KB pages](https://developer.android.com/guide/practices/page-sizes),
[Android NDK downloads and license](https://developer.android.com/ndk/downloads).
