# Herdr GPUI for Android

Experimental Android client for Herdr, with a real Rust GPUI/wgpu terminal surface, embedded SSH over TCP or Iroh, and an Oh My Pi command palette. The current Android source is **0.5.0-gpui-remote**.

This is a source preview. Full real-daemon input/lifecycle validation and Titan-device verification remain incomplete. Every green `main` build publishes an unsigned preview prerelease, but no validated, stably signed release exists yet.

## Features

- Native Android host management with encrypted profiles and explicit SSH host-key trust
- Embedded password or compatible private-key SSH authentication; passwords and passphrases are used once, and a private key is kept only when explicitly saved as an Android Keystore-encrypted device identity outside every profile
- Direct TCP or Iroh `/iroh/ssh` transport to Herdr's binary remote-client bridge
- GPUI/wgpu terminal surface, ordered keyboard/IME input, and explicit pane selection
- Per-host OMP quick commands with editable previews and separately confirmed Send + Enter
- Terminal type that fits the pane's own wrap width, multiplied by Android's system font scale, with an explicit 11-18 sp override in **View**; every cell is painted on one monospace grid, so box-drawing, block, braille and wide glyphs from fallback fonts stay in their columns
- Default, Nord, Mocha and light Latte themes, or **Follow system** (Latte in light mode, Mocha in dark, switching live), that also recolor the system bars, toolbar, rail and sheets, with every chrome text role held to at least 4.5:1 contrast
- Horizontal panning that stops at the pane's last column and survives opening and dismissing the keyboard; confirmed pane/session switches and changed typography/layout preferences reset the offset
- Fold-aware chrome: at 600 dp and wider the rail sits beside the terminal; on narrower windows such as the Pixel Fold's cover it opens as a modal drawer and less frequent actions move into one overflow menu. Half-open like a small laptop (tabletop), the terminal keeps the half above the fold and the host's keys become large keys below it. Toolbar actions are labelled icons with tooltips, every control is at least 48 dp, and sheets use the device's own dialog style
- A host list that keeps a readable column on wide panels, with one Connect action per host and its edit, trust and delete actions in a menu
- Raw SSH shell: a trusted host's menu offers **Open shell**, the host's own login shell on a remote PTY, rendered by modified copies of [gpui-rio](https://github.com/cachix/agentaps) and [rio-vt](https://github.com/raphamorim/rio). It is for hosts or moments without Herdr: there are no panes, rail or agent status, and keys, the on-screen keyboard, quick commands, themes and copy work as in a pane
- Metadata-only background host mirrors keep the sidebar current without continuously processing unseen terminal surfaces
- Optional HTTPS phone-companion workflow
- Opt-in, app-local read-only device diagnostics with a visible foreground
  notification; leaving the screen revokes access

## Current limits

The terminal paints the selected pane from validated binary surfaces: named, indexed and truecolor cells, bold/dim/italic/underline/reverse/strikethrough attributes and a block cursor, bounded per row. Graphics, responsive terminal sizing, unlimited scrollback and split-pane editing are not implemented.

Herdr sizes a pane itself: a client's hello surface and `client_shell.resize` do not change the row count, and `pane.resize` only nudges split ratios. A pane therefore keeps the daemon's own rows and columns on a panel that has room for more. To use the panel's height, the client spreads a short pane's rows over it, up to twice their natural pitch: the type size and every column stay, and the lines read more widely spaced. Only a pane with fewer rows still has even margins; when the program in it paints its own background, that colour fills them, so the pane does not sit as a card in a differently coloured frame. Horizontal panning exposes wide lines without reducing text below the readable size floor.

The automated terminal runtime evidence uses actual JNI, SSH and GPUI with a **synthetic generation-1 protocol peer**, not a real Herdr daemon or shell. Separate [Pixel 9 Pro Fold checks](android-gpui/docs/pixel-fold-notes.md) exercise ARM64 rendering, panning, keyboard layout and pane switching in existing remote sessions. Those checks do not prove full real-daemon input/lifecycle behavior. Titan execution, real deployed Iroh receiver/public relay traversal, and device network transitions remain unverified. Local diagnostics have a separate [contract and verification scope](docs/android-control-agent.md); they do not prove terminal or remote-control behavior. Whole-desktop-workspace verification remains incomplete. Existing validation records distinguish passed, failed and unrun checks.

The application closes remote connections on leaving/backgrounding. It does not reconnect automatically or replay input: a lost transport shows a banner naming the failure with **Hosts** and **Reconnect** actions, and Reconnect opens a fresh session for the saved host without replaying the old one. A command may already have reached the host when a connection fails. Herdr 0.9.3's bridge can start its daemon if it stops between the initial status probe and bridge execution; this preview cannot guarantee attach-only behavior.

A raw shell has no daemon behind it, so leaving the terminal ends the remote shell and Reconnect starts a new one; use Herdr, or a multiplexer on the host, for work that must survive. Its terminal is told `xterm-256color` and fills the screen: there is no header, rows use the font's own line height, and auto type is the app's 13 px default, smaller on narrow panels down to 11 px to keep close to 80 columns. The remote PTY gets every size it lays out. It paints cell by cell, keeps 10,000 lines of scrollback, and does not implement graphics or application mouse reporting; graphics that name a file or shared memory on the phone are refused.

## Build

Start with [Android GPUI setup](android-gpui/README.md), [remote connection requirements](android-gpui/REMOTE.md), [script documentation](android-gpui/scripts/README.md), and the [Pixel 9 Pro Fold notes](android-gpui/docs/pixel-fold-notes.md) for what is verified on hardware, how it is measured, and which traps cost time. The fixed pane grid a client cannot change is described, with the experiments that prove it, in the [upstream grid proposal](docs/upstream/herdr-client-grid-proposal.md).

Prerequisites: Rust 1.96.1, `aarch64-linux-android` target, Android SDK API 35/Build Tools 35.0.0, compatible Android NDK (r30 was used in recorded builds), JDK 17+, Python 3, and the SDK/NDK licenses accepted by the developer. SDK/NDK paths are supplied through `ANDROID_HOME` and `ANDROID_NDK_HOME`.

```sh
cd android-gpui
./scripts/build-native.sh
./gradlew :app:assembleDebug :app:lintDebug
```

The alternative `./scripts/build-verification-apk.sh` uses the official SDK tools and generates a development-signed APK. Historical builds used that packager. Standard Gradle debug/release builds, diagnostics JVM tests, and debug/release lint have also passed for x86_64. Physical-device execution remains a separate gate. Do not use development signing for a production release.

Default ABI is ARM64 (`arm64-v8a`). Set `HERDR_ANDROID_ABI=x86_64` and install the corresponding Rust target for emulator builds. The app requires Android API 29+ and a GPU supported by GPUI Mobile. Current application ID: `dev.herdr.android.gpui.remote`.

Lockfiles and the GPUI Mobile revision are pinned, but bit-for-bit reproducibility is not established. Signing keys and generated binaries are deliberately absent; a fresh build's signing identity cannot update an installation signed by a different key.

## Source map

- `android-gpui/`: current GPUI Android host, native UI, build scripts and tests
- `android-remote/`: embedded SSH/Iroh transport, synthetic fixtures and dependency notices
- `android/`: shared Java companion code and the earlier Java-only app
- `crates/`: upstream desktop/client/protocol/companion sources required by this source snapshot
- [Remote validation](android-gpui/REMOTE-VALIDATION.md): historical build/runtime evidence and limitations
- [Provenance](PROVENANCE.md): exact upstream baseline and publication changes

Inherited upstream GitHub workflows and CODEOWNERS assignments are omitted. The [Android CI and release workflow](android-gpui/RELEASE.md) builds and checks both ABIs, publishes an unsigned preview prerelease for every successful `main` build, and creates tag-driven draft prereleases. Historical validation is not a passing CI claim; the signed release path still requires a signing identity and runtime validation.

## Provenance and licenses

Based on [Fabien Penso's Herdr GPUI](https://github.com/penso/herdr-gpui), including the [phone companion proposal #248](https://github.com/penso/herdr-gpui/pull/248), baseline commit `273a7bfea714374f10a03445a0a679359f21117c`. That PR was open and unmerged when this source was published. This snapshot imports source files, not upstream Git history. It is an independent experimental Android continuation and does not imply upstream endorsement.

The project uses [Apache-2.0](LICENSE); preserve [NOTICE](NOTICE), per-component notices, mobile backend and font licenses. GPUI Mobile is pinned to `9075e3aa3eea812127f2c60ed66f0cd5798ff245`, using its Apache-2.0 license option. [Third-party notices](android-remote/THIRD-PARTY-NOTICES.txt) and their [inventory](android-remote/THIRD-PARTY-NOTICES.inventory.json) are included. The dependency inventory identifies missing shipped license text for some dependency archives; binary redistribution review remains open. Dependencies are fetched at build time, except modified copies of gpui-rio 0.1.0 (Apache-2.0) and rio-vt 0.5.28 (MIT) under `android-gpui/rust/vendor/`, whose READMEs list the changes. No compliance certification or production-readiness claim is made.
