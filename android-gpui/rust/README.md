# Rust GPUI terminal layer

This standalone workspace pins GPUI/wgpu 0.3.7 and GPUI Mobile revision
`9075e3aa3eea812127f2c60ed66f0cd5798ff245`. The desktop workspace stays on 0.3.6.
The Android app packages the resulting `libherdr_gpui_android.so`.

## Responsibilities

- `model.rs`: strict shortcut and input validation, bounded latest-snapshot state,
  a FIFO of at most 64 commands, and generation fencing
- `ui.rs`: real GPUI elements, virtualized monospace rows, horizontal and vertical
  scrolling, follow-output control, and local IME composition presentation
- `android.rs`: JNI and the upstream process-lived SurfaceView host
- `remote.rs`: background SSH session ownership, binary generation-1 client,
  focus/boot fences and bounded plain-text surface extraction
- `remote_jni.rs`: bounded Java remote-session calls and Android context setup

The HTTP companion path keeps profiles, bearer tokens, TLS/HTTP ownership and
response validation in Java. The remote path passes transient SSH credentials
through bounded JNI arguments into zeroizing Rust-owned buffers, verifies the
saved host-key pin before authentication, and runs TCP or Iroh transport on a
background session worker. Java stores encrypted connection settings and trust;
SSH credentials are not saved in profiles or Android framework state.

GPUI renders bounded plain text from either companion snapshots or the remote
client's validated generation-1 binary surfaces. Remote transport reuses the
shared binary client/protocol, but the renderer does **not** provide exact VT-cell
colors, cursor painting, graphics or unlimited scrollback. See
[`REMOTE.md`](../REMOTE.md) for prerequisites, trust, Iroh scope and the upstream
bridge's daemon-start race.

The mobile dependency is used under its Apache-2.0 license option. Its other
license options are not selected. Upstream copyright/license files remain in the
pinned dependency; the Android module's third-party notices accompany the package.
The host integration follows the upstream public Android host API.

## Lifecycle and input

The Java Activity registers itself with `nativeStart`. GPUI Mobile's `host::start`
keeps one render thread and `ApplicationHandle` alive for the process; recreated
Activities reattach their SurfaceView. The NativeActivity initializer is not used.
A compatibility `android_main` symbol exists solely for the transitive
`android-activity` library and reports a configuration error if invoked.

Every new connection has a monotonically increasing positive generation. Java
first calls `nativeSetActive(generation, false)`, publishes connecting state, and
enables input only after receiving a valid initial screen response, or a matching
focused pane surface on the remote path. Inactivation
or a newer generation drops pending commands and invalidates the mobile IME
session. Snapshots may update an inactive generation to show connection failures.
Stale snapshots, commands, and physical keys cannot advance the generation.

Outgoing commands use the strict `text`, `keys`, or single-line `submit` schema.
Text never appends Enter. Submit occupies one FIFO slot: the HTTP path requires
acknowledged text before sending Enter, while the binary SSH path queues text
and Enter as one ordered semantic batch without a daemon acknowledgement.
A single IME
newline commit produces the explicit Enter key. Java InputConnection callbacks
enter the same FIFO synchronously through `nativeImeEvent`, fenced by connection
generation and the registered keyboard session. Composing text stays local until
commit and blocks terminal keys until it is committed or canceled. The GPUI
EntityInputHandler is local-only and cannot duplicate remote text commits. Named keys follow the existing companion vocabulary; unsupported modifier
combinations are not reduced to unmodified keys. JNI physical keys carry their
connection generation and are decoded before entering the same bounded FIFO.
Back calls `gpuiClose(generation)` directly, so it also works while disconnected.
Neither closing this view nor pausing it terminates the remote terminal.

Input ordering is FIFO at the Rust command boundary. Configured extra keys and
macros deliberately live in an Android View strip below the GPUI SurfaceView.
The Java UI thread synchronously sends every remote-mutating event (physical
keys, IME commits/deletion, and extra-key clicks) into the same Rust FIFO. This
avoids mixing deferred GPUI touch callbacks with immediate text commits. GPUI's
own controls only change local display, keyboard visibility, or navigation.

The Android input proxy manages local composition. Surrounding-text deletion on
an empty proxy maps to at most 16 explicit Backspace keys; forward deletion is
rejected by the shared input grammar. Remote output is not
represented as a locally editable document. New snapshots and preedit changes
wake the GPUI view through a bounded coalesced channel; rendering also refreshes
the current generation before building elements, including after surface reuse.

Bounds: text commands 65,536 UTF-8 bytes; at most 16 keys per command and 24
shortcuts; serialized shortcut data 2 MiB; Rust snapshots 512 KiB, with the Java
host imposing a smaller 256 KiB limit. Display work is bounded to 4,000 virtual
rows and 4,096 characters per row. A visible note reports display truncation.
Terminal escapes and control sequences are stripped, never executed. No OSC
clipboard, hyperlink or graphics operations are interpreted.

## Verification

From this directory with the repository's Rust 1.96 toolchain:

```
cargo test --locked
cargo clippy --locked --all-targets --features android-ui -- -D warnings
cargo check --locked --features android-ui --target aarch64-linux-android
```

The default build tests only the platform-independent model. `android-ui` also
compiles the GPUI view on Linux; on Android it enables the real mobile backend and
JNI. Use the parent module's build script for NDK linking and APK packaging.
A successful cross-build does not verify device rendering, font shaping, IME
behavior, touch scrolling or Android lifecycle timing; these need device QA.
