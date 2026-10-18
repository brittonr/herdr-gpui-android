# SSH and Iroh compatibility validation

## Scope

Two bounded changes share the existing transport implementation:

- Normalize bracketed IPv6 literals before tuple-based TCP resolution. Previously
  `[::1]` was passed as a DNS hostname and failed. Bare IPv6, IPv4 and hostnames
  remain unchanged; bracketed hostnames, malformed brackets, URLs and embedded
  ports are rejected. Saved profile identity and SSH pins are unchanged.
- Preserve SSH authentication result distinctions: rejection, unavailable chosen
  method, and partial success requiring another step. Use only fixed local text
  and a typed method name; do not expose server banners or credential contents.
  Both TCP and Iroh close the failed session without retries or fallback.

No dependency versions, authentication methods, SSH algorithms, trust policies,
network configuration, discovery settings or receiver deployment changed.

## Upstream contracts inspected

- [Herdr v0.9.3 endpoint contract](https://github.com/herdrdev/herdr/blob/v0.9.3/src/protocol/endpoint.rs):
  generation 1 and the existing surface-interest, presentation-fence and health
  capabilities. The client keeps capability checks rather than a version allowlist.
- [Herdr v0.9.3 bridge](https://github.com/herdrdev/herdr/blob/v0.9.3/src/remote/host.rs):
  conditional idle-timeout option and daemon-start race remain as documented in
  REMOTE.md. This change does not promise attach-only operation.
- [iroh-ssh 0.2.12 source](https://github.com/rustonbsd/iroh-ssh/tree/d0e9b6a823d25bf5b02757bbf9c17edeb39cfad5):
  Iroh 1.0 dependency, `/iroh/ssh` ALPN, raw SSH over one bidirectional stream.
  Android remains pinned to Iroh 1.3.0 and russh 0.63.3. The local receiver source
  at `6be575af4f43f3c9f9700f7dc96dc2f34c8c252e` uses Iroh 0.94; it was inspected
  read-only, not run or upgraded. Neither older receiver interoperability nor a
  live 0.2.12 receiver is established by same-library loopback tests.

## Coverage and limits

| Case | Evidence |
| --- | --- |
| IPv4 SSH inspection, exact pin rejection, password and in-memory Ed25519 auth | Existing Rust loopback fixtures |
| Bracketed IPv6 SSH inspection and authenticated exec | New `::1` listener regression |
| Iroh raw stream, exact ALPN, pinned SSH auth and binary bridge | Existing loopback Iroh proxy; discovery and relays disabled |
| Password attempt with public-key-only or keyboard-interactive-only next methods | New TCP and Iroh wire tests; exactly one password attempt, zero key fallback |
| Partial-success auth / unavailable public-key method | Typed `AuthResult` tests; Android error-boundary test |
| Wrong password with password still offered | Explicit server fixture response, remains generic rejected authentication |
| Cancellation, output bounds, closure and runtime shutdown | Existing TCP/Iroh regressions |
| Public discovery/relay/NAT traversal, deployed receiver, phone SSH | Not tested |

Pinned russh's synthetic server resets `partial_success` when sending password
rejections. Therefore the partial-success classification is tested using its
client `AuthResult` type, not claimed as an end-to-end wire test. Tests use only
loopback fixtures and disposable in-memory synthetic keys; no user credentials,
credential files, SSH configuration or existing daemon are accessed.

Unsupported combinations remain RSA/DSA in this feature set, SSH agent/config/jump
hosts, keyboard-interactive, MFA/multi-step authentication, and receiver formats
other than the documented raw `/iroh/ssh` stream. Iroh identity does not replace
SSH host-key verification or account authentication. Scoped bracketed IPv6 is not
added. Changing any authentication/trust/network policy requires a separate decision.

## Commands and results

All Cargo
commands use the existing Rust 1.96.1 toolchain, pinned lockfiles and offline
provisioned dependencies. No toolchain or dependency upgrade was performed.

On aspen3, 2026-10-18:

- `cargo test --locked --offline --manifest-path android-remote/rust/Cargo.toml`:
  30 passed; includes direct IPv6 and TCP/Iroh method-policy regressions.
- `cargo test --locked --offline --manifest-path android-gpui/rust/Cargo.toml
  --features remote`: 35 passed, including diagnostic propagation.
- `cargo test --locked --offline --manifest-path android-remote/fixtures/Cargo.toml`:
  6 integration tests passed (adapter flow and wire bounds).
- `cargo clippy --locked --offline --manifest-path android-remote/rust/Cargo.toml
  --all-targets -- -D warnings`: passed.
- `cargo clippy --locked --offline --manifest-path android-gpui/rust/Cargo.toml
  --features remote --all-targets -- -D warnings`: passed.
- `cargo fmt --manifest-path <each changed standalone workspace>/Cargo.toml
  -- --check`: both passed. `git diff --check`: passed.
- `bash android-gpui/scripts/build-native.sh`: actual ARM64 release compilation
  with SDK/NDK 29.0.14206865, Android API 29, and pinned Rust dependencies passed.
  The first freshness check rejected test-source edits made during compilation;
  rerunning against final sources passed. No stale library was packaged.
- From `android-gpui`, `bash ./gradlew --offline --no-daemon --max-workers=3
  -Pandroid.builder.sdkDownload=false
  -Pandroid.aapt2FromMavenOverride="$ANDROID_HOME/build-tools/35.0.0/aapt2"
  :app:assembleRelease :app:lintRelease`: passed; 20 warnings, no errors.
- Native build-record verification, `verify-apk-structure.py --abi arm64-v8a`,
  SDK `zipalign -c -P 16 4`, and byte-for-byte comparison of the packaged native
  library against the new build output: passed.

This time the native library was rebuilt from the changed source, using an
isolated copy of prior compilation/dependency caches; it is not the old library
from PR8. No Mantle execution or hermetic-build claim is made.

Native SHA256:
`bb7f7b7f8dc7a87c080e52b981a7c0ee559062fda3db8230e02bfbaa31105468`

Unsigned APK SHA256:
`a78e51d416aa5e11bbbf1c7957e69743fbdb1006b5d0a533aba5b38726a2382e`

The APK is unsigned and not installable as delivered. No signing key was created
or accessed; nothing was installed, released or run on a phone/emulator.

Broader inherited desktop gates are not green: root `cargo fmt --all -- --check`
reports existing formatting differences in unchanged herdr-client files; root
`cargo clippy --locked --offline --workspace --all-targets --all-features --
-D warnings` cannot resolve uncached `qrcode` for herdr-companion. `just` is not
available in the selected toolchain, so equivalent Cargo commands were used.
Full desktop tests were not run. These unrelated files/dependencies were preserved.

Next: review the PR and CI, then separately authorize compatible signing/device
installation and a live receiver validation plan. No credentials or host-policy
changes are needed to review this patch.
