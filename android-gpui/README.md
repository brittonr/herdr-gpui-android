# Herdr Android with a GPUI terminal

## Remote connection update

Version 0.5.0 adds embedded SSH over direct TCP or Iroh. Open **Remote hosts**,
verify the SSH server fingerprint, authenticate with a one-use password, a
one-use private key, or a device-wide key saved encrypted on the phone, and
select a pane. It uses Herdr's binary bridge and does not require an HTTP
companion. See [remote setup, trust and limits](REMOTE.md).

The Remote preview installs as `dev.herdr.android.gpui.remote`, alongside the
preserved Compact app. The prior development signing key was lost with the
temporary build workspace. **Companion hosts** still opens the HTTPS workflow.

**Local device diagnostics** opens a separate, default-off, read-only screen.
Enabling requires usable notifications; **Read diagnostics** collects one bounded
device/battery/storage snapshot. Uncheck, notification **Stop diagnostics**, or
leaving the screen revokes access and clears its result. There is no remote
endpoint, shell/root access, or background monitoring. See the
[local diagnostics contract](../docs/android-control-agent.md).

This is the GPUI-backed continuation of the native Android companion preview.
The saved-host manager, authentication and agent screens remain Android Views;
the terminal activity is rendered by **Rust GPUI and its wgpu Android backend**.
It is not a WebView. The original `android/` application can still be built
without the GPUI native library.

## Quick commands

Version 0.4.0 adds a per-host Oh My Pi command palette with editable previews,
pinning, Insert, and separately confirmed Send + Enter. Tap **Cmds** in a GPUI
terminal. See [usage and verified presets](QUICK-COMMANDS.md) and
[0.4.0 validation](QUICK-COMMANDS-VALIDATION.md). That Compact release updated
0.3.0 in place. Its command palette is also available per host in Remote 0.5.0.

## Compact phone update

Version 0.3.0 adds a selectable compact, keyboard-first profile for the Titan 2
Elite. See [device setup and differences](TITAN-2-ELITE.md) and
[0.3.0 validation](COMPACT-VALIDATION.md). The earlier Compact package is
`dev.herdr.android.gpui.compact` (Herdr GPUI Compact), installed alongside the
earlier preview because its old development signing key did not survive the
cloud workspace reset. Saved hosts in the earlier app remain untouched.

## Architecture

```text
Native Android host manager (encrypted profiles)
  -> non-exported GpuiTerminalActivity
     -> GpuiInputActivity / Activity + SurfaceView / Android lifecycle
        -> herdr_gpui_android.so
           -> gpui-pre 0.3.7 + gpui-pre-wgpu 0.3.7
           -> Longbridge gpui-mobile Android platform
     -> RemoteNative (Rust background workers)
        -> embedded russh over TCP or Iroh /iroh/ssh
        -> Herdr remote-client-bridge -> generation-1 client/protocol
     -> existing HTTPS CompanionClient (Java worker)
        -> authenticated herdr-companion from this source
           -> Herdr pane.read / pane.send_text / pane.send_keys
```

The Java host owns encrypted profile storage and the credential/trust dialogs.
SSH credentials cross JNI once into zeroizing native buffers. Rust owns the
embedded SSH/Iroh connection and binary client. Companion bearer tokens remain
within the Java HTTPS transport. Display state and input use bounded,
generation-fenced queues. A private process-local one-shot launch slot transfers
the selected host or connection to the non-exported terminal activity; credentials
never enter Intents or framework saved state.

GPUI Mobile is pinned to
`9075e3aa3eea812127f2c60ed66f0cd5798ff245` from
[longbridge/gpui-mobile](https://github.com/longbridge/gpui-mobile).
Its Apache-2.0 license option is selected. The Rust application has its own Cargo
workspace and lockfile so the desktop application's pinned GPUI 0.3.6 is unchanged.
GPUI core, mobile backend and wgpu renderer are aligned at 0.3.7.
The upstream SurfaceView host keeps one render thread alive across Activity
instances, avoiding the separate NativeActivity path’s single-initialization
restriction. The copied IME helper uses Activity instead of NativeActivity and a protected
callback hook. Android composition remains local; committed IME input and
physical keys enter one generation- and IME-session-fenced FIFO. This prevents
a later Enter from overtaking text. Commands are rejected during preedit.

## What this proves, and what it does not

This is a real Android GPUI application integration: the native platform,
renderer, terminal layout and input handling are GPUI/Rust. Remote SSH uses the
existing binary client, while the optional HTTPS workflow uses companion snapshots.
It does not move the full desktop application, sidebar or browser to Android.

The SSH workflow paints the daemon's whole surface frame from its own cells: 16
named colors, indexed and truecolor cells, bold, dim, italic, underline, reverse,
strikethrough, hidden text and a block cursor, with every run bounded and
validated before it reaches the renderer. Split panes and their borders are
painted together and the panes' focused border carries the accent ring.
Terminal hyperlinks in the daemon's table become tappable runs; only `http(s)`
targets are forwarded, to the platform browser, never to files or custom
schemes. **Tab** controls the focused session through allowlisted daemon methods: new
tab, split right or down, zoom toggle, close pane and close tab (both confirmed).
The client follows whichever pane the daemon focuses afterwards.

Agent alerts the daemon broadcasts appear as Android notifications on one
bounded channel (newest per pane, at most eight pending), and a terminal bell
gives a short vibration instead of overriding the ring mode. Notifications need
the system notification permission; without it the terminal still runs.

An OSC 52 clipboard write from a pane reaches the Android clipboard as
plain text only: the payload must be base64 of valid UTF-8 within 256 KiB, and
anything else is dropped rather than guessed at. A
surface whose runs do not tile the extracted text exactly falls back to bounded
**plain text**, which is also what the HTTPS companion workflow shows. Graphics,
images, responsive daemon resize and full scrollback are not rendered yet. No
terminal escape sequence is executed just because it appears in output. GPUI
backend lifecycle and mobile IME support are experimental upstream; device
verification is essential.

The configurable extra-key strip uses native Android controls so its actions
share the immediate ordered input path with hardware keys and IME commits.
GPUI owns the terminal surface, scrolling, composition display and view controls.

SSH and Iroh are embedded libraries. No system SSH executable or VPN is required.

### VM-tested update

Version 0.2.1 fixes a font-resolution crash discovered on the first real Android
VM run. Bundled Noto Sans and Noto Mono make baseline rendering independent of
OEM font aliases. Android 15 x86_64 VM tests exercise actual GPUI frames and
input/lifecycle paths; see [VM-TESTS.md](VM-TESTS.md). Transport in those tests is
synthetic, and ARM64 phone execution still needs verification. Emoji coverage
remains incomplete on the tested VM.

## Build and run

You need Rust 1.96.1, the `aarch64-linux-android` target, a compatible Android NDK,
and Android SDK 35 with JDK 17+ for the standard Gradle build. NDK/SDK usage is
subject to Google's Android SDK License Agreement. Build scripts must not accept
agreements on your behalf; install those tools only after you accept their terms.

The scripts in `scripts/` cross-build the real ARM64 Rust shared library and
package the Java host with it. They validate native ELF architecture and 16 KiB
page alignment, JNI host/surface entry points, APK contents and signatures.
See the scripts' usage/help and the validation record for the exact commands
actually exercised in the build environment.

For normal Android Studio development, open this `android-gpui/` directory.
After producing `app/src/main/jniLibs/arm64-v8a/libherdr_gpui_android.so`:

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

The current app ID is `dev.herdr.android.gpui.remote`, installed alongside the
older previews. Profiles from those separate applications must be entered again.
The GPUI build requires Android 10+ because this mobile backend links an Android
API 29 function; the original Java-only preview still supports Android 8+.
It requires an ARM64 Android device, API 29+ (Android 10+), with the GPU capabilities required
by GPUI Mobile. Remote sessions use the Internet permission. The optional local
diagnostics screen additionally declares notification and foreground-service
permissions; no accessibility, storage, root, or screen-capture permission is
requested.

1. Use an existing SSH account and compatible running Herdr session on the host.
   Iroh additionally needs its SSH receiver. See [remote setup](REMOTE.md).
2. Install the Remote preview and add a TCP or Iroh profile.
3. Independently verify its SSH fingerprint, authenticate, and select a pane.
4. Use **List** for the workspace/agent rail (it opens by default where width
   allows) or **Panes** for the same session tree as a sheet. Workspace, tab and
   pane rows carry the agent status dot (idle, working, blocked, done); a tap
   switches workspace or tab, or focuses that pane, without leaving the
   terminal. **Cmds** opens editable OMP commands and **View** the compact
   display options.

For the optional HTTPS workflow, open **Companion hosts** and use the
[companion setup](../android/README.md#host-prerequisites), then tap **GPUI** from
an existing terminal. That workflow still requires trusted HTTPS and a token.

Closing or backgrounding this activity does not stop remote terminals. Pending
input is discarded and never automatically replayed. A dispatched action may
already have reached the host when the connection fails. SSH semantic input is
reported as queued and has no daemon receipt; see the remote guide.

See [../android/VALIDATION.md](../android/VALIDATION.md) for the previous native
preview's checks. GPUI-specific build and runtime evidence belongs in
`VALIDATION.md` in this directory; the earlier Java APK's validation is not proof
that the native GPUI activity runs correctly.
