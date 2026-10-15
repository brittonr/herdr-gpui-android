# Herdr for Android — native preview

A native Android phone client for Herdr hosts, based on the HTTP companion in
[penso/herdr-gpui PR #248](https://github.com/penso/herdr-gpui/pull/248), baseline
`273a7bfea714374f10a03445a0a679359f21117c`. The UI uses Android Views and Java,
not a WebView or installed PWA. The desktop GPUI application remains unchanged.

## What works in this implementation

- Save, edit, forget, and switch between up to 32 independent HTTPS hosts
- Encrypt saved profiles and bearer tokens with AES-256-GCM and an Android
  Keystore key; exclude profiles from Android backup and screenshots
- List agents and all terminal panes, including ordinary shells
- Read live terminal screen snapshots; type exact Unicode text without
  implicitly pressing Enter; send a separate Enter, navigation key, or chord
- Physical-keyboard direct mode, including Ctrl+A–Z, Alt+A–Z, Shift+Tab,
  Esc, arrows, Backspace, Enter, and F1–F12
- A per-host configurable horizontal extra-key strip with up to 24 buttons,
  sequences of up to 16 supported keys, and literal text macros
- Read Claude hook transcripts, reply through `agent.prompt`, interrupt, and
  start an agent in a new tab
- Review and allow/deny pending permission requests, or return them to the
  terminal; `AskUserQuestion` is deliberately handled in the terminal for now
- Suspend polling in the background; recreate a fresh connection on resume;
  drop queued input on disconnect/host switch, with no automatic write replay

## Scope and honest limits

This is an Android companion client, **not a build of GPUI on Android**. It reuses
PR #248's authenticated bridge instead of the desktop binary surface protocol.
The bridge continues to use Herdr's existing JSON API; no daemon is modified.

Terminal content is plain text from `pane.read`, refreshed approximately every
1.5 seconds while the app is visible. Host terminal dimensions are retained.
There is no VT-cell rendering, cursor/color fidelity, resize, mouse reporting,
scrollback API, split-pane editing, SSH client, QR scanner, push notification,
offline command queue, or background service. Selecting text copies displayed
text using Android's normal selection behavior; it does not select terminal
cells on the host. Software keyboards compose text in the text field and send
it explicitly. Direct physical-key mode is opt-in each session. Dead keys,
combined Ctrl+Shift/Alt+Shift chords, and unsupported named keys are rejected
rather than silently converted. Multi-byte/IME behavior still needs device QA.

The existing PR's PWA and networking-discovery code are preserved. This Android
work adds no VPN or peer-to-peer transport integration.

## Host prerequisites

1. Install/run Herdr on the host separately.
2. Build the **companion from this branch**, which adds `/v1/panes`, `/text`,
   extended keys and correct URL-decoding of pane IDs. The original PR248
   companion supports agent views but lacks these additions.
3. Provide an HTTPS endpoint with a certificate trusted by Android, forwarding
   to the companion's loopback listener. Keep the bearer token intact in the
   `Authorization` header. This app refuses HTTP and all redirects; use the
   final HTTPS URL, optionally with a plain reverse-proxy base path.
4. Enter the endpoint and its companion token in Add host. Treat the token as
   full remote-terminal authority, not a read-only credential.

Example host-side companion commands, to run only on a host you administer:

```sh
# Use an existing securely supplied HERDR_COMPANION_TOKEN.
cargo build --locked -p herdr-companion
./target/debug/herdr-companion serve --listen 127.0.0.1:8787 --no-qr
```

TLS termination, token provisioning, Herdr installation, firewall settings and
Claude hook setup are separate host configuration. No host configuration is
performed by the app or this source delivery. See
[companion setup](../crates/herdr-companion/README.md) for hook integration. Do
not expose a plain HTTP companion to an untrusted network. This app never skips
certificate or hostname verification, installs certificates, or sends the
bearer token to a redirected address.

## Build with Android Studio / the standard SDK

Requires JDK 17 or newer compatible with Gradle 8.9, Android SDK platform 35,
and Android build tools. The project pins AGP 8.7.3 and Gradle 8.9; Android 8.0
(API26) is the minimum runtime. There are no app runtime dependencies beyond
Android's framework and Java standard library.

```sh
cd android
./gradlew :app:assembleDebug :app:lintDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Open `android/` in Android Studio. Configure the SDK using normal Android Studio
settings or a local, uncommitted `local.properties`. Only accept SDK agreements
if you are authorized to do so. Debug signing is for evaluation; production
signing, distribution and Play Store publication are not configured.

A separate, explicitly labeled [SDK-free verification build](scripts/README.md)
uses pinned Google Maven AAPT2/D8/apksig and a Maven Android framework artifact.
It builds a real signed APK, but does not substitute for a normal SDK lint/build
or for emulator/device testing. Its output is
`app/build/outputs/apk/manual/herdr-verification-debug.apk`.

## Configure extra keys

Open a terminal, then **Edit keys**. One line creates one button:

```text
Esc = key:esc
Stop = key:ctrl+c
Next choice = keys:down,enter
Status = text:git status
```

Text macros do not implicitly submit. `\n`, `\r`, and `\t` are explicit control
characters, and `\\` inserts a backslash. A macro with a newline can execute a
command immediately: review it before saving or tapping it. Key sequences are
ordered within one request. They are not atomic with separate text requests.

Supported named keys match the daemon's parser: `enter`, `esc`, `tab`, `space`,
`backspace`, `up`, `down`, `left`, `right`, `shift+tab`, `ctrl+a` through `ctrl+z`,
`alt+a` through `alt+z`, `f1` through `f12`, `y`, `n`, and `1` through `9`.
The daemon does not parse `home`, `end`, `delete`, `pageup`, or `pagedown` in this
API, so the app does not advertise them. Ordinary characters are sent as text.

## Verify

```sh
sh android/tests/run.sh
cargo fmt --all -- --check
cargo test --locked -p herdr-companion
cargo clippy --locked -p herdr-companion --all-targets --all-features -- -D warnings
```

See [VALIDATION.md](VALIDATION.md) for actual checks, failures and device checklist.

## Security and lifecycle notes

- HTTPS is mandatory. Default Android trust and hostname checks are unchanged.
- Credentials are never written to logs, URL queries/fragments, shared
  preferences, saved-instance state, or APK assets. Remote errors are replaced
  with locally authored messages. Screenshots/recents previews are protected
  with `FLAG_SECURE`; no analytics or background push provider is included.
- Host storage uses atomic authenticated-encrypted writes. A decryption error
  blocks changes rather than overwriting unreadable data. Keys remain in the
  Android Keystore; app uninstall/reset forgets profiles. Rooted-device and
  malicious-keyboard threats are outside this prototype's protection.
- Requests run on a bounded, single-threaded queue; at most 64 are waiting.
  Each callback is fenced to its connection generation. Old host clients are
  cancelled when switching or backgrounding. Reads resume; writes are never
  replayed. A timeout/cancel after dispatch cannot prove a remote command did
  not execute. Refresh before manually retrying uncertain actions.
- Replies are bounded to 1 MiB; raw terminal text to 64 KiB UTF-8; requests to
  512 KiB. All terminal, agent and transcript content is rendered as text.
- Permission approvals are explicit app actions. They can execute remote
  commands and modify files. This preview shows the original input and asks
  again before allowing; question forms are returned to the terminal.

Apache-2.0; retain the repository LICENSE/NOTICE. The launcher icon adapts the
companion's icon, and the Gradle wrapper retains its Apache-2.0 notices. License
and notice copies are included in APK assets.
