# Herdr GPUI for Android

Experimental Android client for Herdr, with a real Rust GPUI/wgpu terminal surface, embedded SSH over TCP or Iroh, and an Oh My Pi command palette. The current Android source is **0.5.0-gpui-remote**.

This is a source preview. Real Herdr end-to-end sessions and physical ARM64/Titan devices still need validation. No APK is published with this initial source import.

## Features

- Native Android host management with encrypted profiles and explicit SSH host-key trust
- Embedded password or compatible private-key SSH authentication; credentials are used for one connection and not saved in profiles
- Direct TCP or Iroh `/iroh/ssh` transport to Herdr's binary remote-client bridge
- GPUI/wgpu terminal surface, ordered keyboard/IME input, and explicit pane selection
- Per-host OMP quick commands with editable previews and separately confirmed Send + Enter
- Compact keyboard-first display options intended for the Titan 2 Elite
- Optional HTTPS phone-companion workflow

## Current limits

The terminal displays **bounded plain text extracted from validated binary surfaces**. Full VT colors/cursor fidelity, graphics, responsive terminal sizing, unlimited scrollback and split-pane editing are not implemented.

The passing Android runtime evidence uses actual JNI, SSH and GPUI with a **synthetic generation-1 protocol peer**, not a real Herdr daemon or shell. Physical ARM64/Titan execution, real deployed Iroh receiver/public relay traversal, and device network transitions are unverified. Full Gradle/Android Lint and whole-desktop-workspace verification are incomplete. Existing validation records distinguish passed, failed and unrun checks.

The application closes remote connections on leaving/backgrounding and does not automatically reconnect or replay input. A command may already have reached the host when a connection fails. Herdr 0.9.3's bridge can start its daemon if it stops between the initial status probe and bridge execution; this preview cannot guarantee attach-only behavior.

## Build

Start with [Android GPUI setup](android-gpui/README.md), [remote connection requirements](android-gpui/REMOTE.md), and [script documentation](android-gpui/scripts/README.md).

Prerequisites: Rust 1.96.1, `aarch64-linux-android` target, Android SDK API 35/Build Tools 35.0.0, compatible Android NDK (r30 was used in recorded builds), JDK 17+, Python 3, and the SDK/NDK licenses accepted by the developer. SDK/NDK paths are supplied through `ANDROID_HOME` and `ANDROID_NDK_HOME`.

```sh
cd android-gpui
./scripts/build-native.sh
./gradlew :app:assembleDebug :app:lintDebug
```

The alternative `./scripts/build-verification-apk.sh` uses the official SDK tools and generates a development-signed APK. The recorded builds used that packager; the standard Gradle commands above have not been independently validated for this publication. Do not use development signing for a production release.

Default ABI is ARM64 (`arm64-v8a`). Set `HERDR_ANDROID_ABI=x86_64` and install the corresponding Rust target for emulator builds. The app requires Android API 29+ and a GPU supported by GPUI Mobile. Current application ID: `dev.herdr.android.gpui.remote`.

Lockfiles and the GPUI Mobile revision are pinned, but bit-for-bit reproducibility is not established. Signing keys and generated binaries are deliberately absent; a fresh build's signing identity cannot update an installation signed by a different key.

## Source map

- `android-gpui/`: current GPUI Android host, native UI, build scripts and tests
- `android-remote/`: embedded SSH/Iroh transport, synthetic fixtures and dependency notices
- `android/`: shared Java companion code and the earlier Java-only app
- `crates/`: upstream desktop/client/protocol/companion sources required by this source snapshot
- [Remote validation](android-gpui/REMOTE-VALIDATION.md): historical build/runtime evidence and limitations
- [Provenance](PROVENANCE.md): exact upstream baseline and publication changes

Inherited upstream GitHub workflows and CODEOWNERS assignments are omitted. This repository currently has no CI or release automation; historical validation is not a passing CI claim.

## Provenance and licenses

Based on [Fabien Penso's Herdr GPUI](https://github.com/penso/herdr-gpui), including the [phone companion proposal #248](https://github.com/penso/herdr-gpui/pull/248), baseline commit `273a7bfea714374f10a03445a0a679359f21117c`. That PR was open and unmerged when this source was published. This snapshot imports source files, not upstream Git history. It is an independent experimental Android continuation and does not imply upstream endorsement.

The project uses [Apache-2.0](LICENSE); preserve [NOTICE](NOTICE), per-component notices, mobile backend and font licenses. GPUI Mobile is pinned to `9075e3aa3eea812127f2c60ed66f0cd5798ff245`, using its Apache-2.0 license option. [Third-party notices](android-remote/THIRD-PARTY-NOTICES.txt) and their [inventory](android-remote/THIRD-PARTY-NOTICES.inventory.json) are included. The dependency inventory identifies missing shipped license text for some dependency archives; binary redistribution review remains open. Dependencies are fetched at build time and are not vendored here. No compliance certification or production-readiness claim is made.
