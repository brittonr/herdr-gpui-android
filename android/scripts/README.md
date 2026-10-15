# Build verification without an installed SDK

The supported Android build is `../gradlew assembleDebug` with JDK 17+ and the
Android SDK installed. Open `android/` in Android Studio for normal development,
API checks, lint, emulator/device runs, and release packaging.

If the build machine has no Android SDK, this repository also has a deliberately
small Linux x86_64 packaging check:

```sh
cd android
./scripts/build-verification-apk.sh
```

Requirements: a Java runtime that includes `jdk.compiler` (JDK 17+ recommended),
`keytool`, Python 3, curl, unzip, and sha256sum. Dependencies are downloaded into
`android/build/manual-tools`, never installed globally. Set `HERDR_BUILD_CACHE`
to use another local cache. Every dependency is pinned and SHA-256 checked.

The output is
`app/build/outputs/apk/manual/herdr-verification-debug.apk`.
It is a real debug APK, signed with a locally generated Android debug key in
`build/manual/debug.keystore`. Keep that file to install later builds as updates;
losing it requires uninstalling the old app (and losing its local saved settings)
or rebuilding with the same key. It is not a release key and must not be published.

The script:

1. Compiles and links Android resources with Google's AAPT2
2. Compiles Java source for Java 8, using Robolectric's Android 15 framework JAR
   solely as a build-time API/resource reference
3. Converts only app classes to DEX with Google's D8
4. Signs and aligns the APK with Google's `apksig` library
5. Verifies APK signatures, binary resource headers, entry alignment, ZIP CRCs,
   DEX checksums, entry-point presence, and exclusion of framework classes
6. Prints decoded manifest/package information and the APK SHA-256

This does not manufacture an SDK installation, run Gradle/Android Lint, accept SDK
licenses, install the app, or run Android. The framework implementation JAR is not
the public SDK stubs, so this path cannot enforce the public/minimum API surface
or replace an SDK build. No framework classes or Robolectric runtime are bundled
in the APK. The script supports the current dependency-free Java app configuration
only and refuses dependencies or a different compile SDK. Changes to Gradle build
logic may require using the standard SDK build instead.

A successful result proves compilation and packaging, not device behavior. Before
release, use the standard build, Android Lint, and real emulator/device QA,
including Android 8 (minimum SDK 26) and Android 15+ (target SDK 35).

Tool sources and documentation:

- [AAPT2 and its Google Maven distribution](https://developer.android.com/tools/aapt2)
- [D8 command-line compiler](https://developer.android.com/tools/d8)
- [AOSP apksig library](https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/README.md)
- [Robolectric framework JARs](https://repo.maven.apache.org/maven2/org/robolectric/android-all/)
