# IME editor-action submission guard

The previous `performEditorAction` implementation committed a newline for every
value except `IME_ACTION_DONE`. Android Next/Previous navigation and custom or
unknown IDs could therefore submit terminal text, including replacing an active
composition with a newline.

The input proxy now accepts only the standard Enter-like actions (Unspecified,
None, Go, Search, Send) for newline submission. Done retains its existing
finish-composition behavior without a newline, and now propagates failure to
finish. Next, Previous, custom IDs and flagged values return false without
changing composition or sending terminal input. Physical keys are unchanged.

## Verification on aspen3, 2026-10-18

Using the existing JDK 17, Android SDK 35 / Build Tools 35.0.0, and isolated copy
of the previously provisioned Gradle cache:

- `sh android-gpui/tests/run-ime.sh`: 2,052 host policy assertions passed.
  Requires `ANDROID_HOME`; uses SDK constants without an Android runtime.
- `sh android/tests/run.sh`: 392 existing core assertions passed.
- `python3 android-gpui/scripts/test-native-verifier.py`: 18 tests passed.
- `python3 android-gpui/scripts/test-native-build-record.py`: 13 tests passed.
- `python3 android-gpui/scripts/test-apk-verifier.py`: 6 tests passed.
- All `android-gpui/vm-tests/src` Java sources compiled with `javac -source 8
  -target 8`, using SDK `android.jar` and `core-lambda-stubs.jar` as bootclasspath.
  The new instrumentation checks rejected actions during preedit, unchanged
  composition, no transport posts, and a still-ready connection. **Not run.**
- From `android-gpui`: `bash ./gradlew --offline --no-daemon --max-workers=3
  -Pandroid.builder.sdkDownload=false
  -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/35.0.0/aapt2"
  :app:assembleRelease :app:lintRelease`: passed; lint has 20 warnings, no errors.
- `python3 scripts/verify-apk-structure.py --abi arm64-v8a
  app/build/outputs/apk/release/app-release-unsigned.apk`: passed.
- SDK `zipalign -c -P 16 4` on that APK: passed.
- `git diff --check`: passed.

The unsigned APK SHA256 is
`d8c8fbfa2472a3ca05c79812bbb4e3a55f444dfc2116ce2125f659e9a87fe64f`.
It is not installable until separately signed. No signing credential was created
or accessed. The build reused the existing ARM64 native library from source
`a889d52cf7b8f0e6c2a55293670fd3f823739354`; Rust is unchanged. The repository's
native freshness verifier passed, and APK library bytes match the input exactly.
This is a new Java/APK build, not a new Rust compilation or a new Mantle run.

Read-only `adb devices -l` found a connected Titan 2. No emulator execution,
physical-device input validation, installation, device mutation, or live SSH
validation was performed. Runtime instrumentation remains the next check,
requiring separate approval for installation/device operations and a compatible
signing setup. PR7's diagnostics work and earlier local checkouts were preserved.
