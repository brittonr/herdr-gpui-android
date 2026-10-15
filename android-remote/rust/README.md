# Embedded SSH transport

This standalone crate carries pinned SSH over direct TCP or an Iroh stream. The
caller supplies a Tokio runtime. It does not install a receiver, change a server,
persist credentials, reconnect, or replay commands.

## Iroh wire contract

The intended receiver is
[`rustonbsd/iroh-ssh` 0.2.12](https://github.com/rustonbsd/iroh-ssh/tree/d0e9b6a823d25bf5b02757bbf9c17edeb39cfad5).
The client parses an Iroh endpoint ID, connects with ALPN `/iroh/ssh`, opens one
bidirectional QUIC stream, and immediately writes the SSH banner. Every stream
byte is SSH; there is no application header, destination field, or capability
negotiation. The receiver forwards that stream to its configured local SSH port.

Production connections use Iroh 1.3's `N0` preset. Selecting and connecting an
Iroh profile can contact Number 0 discovery and relay services. No endpoint is
created merely by constructing a profile or loading this library. The QUIC
handshake completes before SSH begins; 0-RTT is not used.

The Android app integrates this transport through its **Iroh + SSH** profile
option. Host and Android VM loopback tests exercise the stream contract with a
synthetic local receiver. Interoperability with a deployed `iroh-ssh` receiver,
public discovery/relays and physical Android network transitions remains
unverified; see [`REMOTE.md`](../../android-gpui/REMOTE.md) and the separate
[`validation report`](../../android-gpui/REMOTE-VALIDATION.md).

## Security and lifetime

- Both transports verify the SSH SHA256 host-key pin before sending passwords
  or private-key authentication
- Inspection records the offered fingerprint and rejects the key without
  authentication; the caller must independently approve a pin before saving it
- Passwords and encoded private keys remain in zeroizing owned buffers; private
  keys are decoded in memory
- Connection, authentication, channel-open, and command operations are bounded
- Stdout and stderr have separate limits; an error cancels the owning session
- Session or bridge cancellation closes Iroh even if the `Session` is retained
- The Iroh lifetime task owns the endpoint and connection until cancellation,
  then drains close packets asynchronously for at most four seconds; dropping a
  UI-owned guard never joins that task

Before destroying a runtime, the background owner must await
`Session::shutdown()` or `BridgeGuard::shutdown()`. This drains QUIC close
packets before runtime shutdown can cancel network tasks. Inspection and failed
connection/bridge setup perform that cleanup before returning. Ordinary Drop
remains nonblocking for callers that keep their runtime alive.

On Android, the embedding app must initialize `ndk-context` with its VM and
application context before Iroh reads the system DNS/network configuration.

## Verification

From the repository root:

```sh
cargo test --manifest-path android-remote/rust/Cargo.toml --locked --offline
cargo clippy --manifest-path android-remote/rust/Cargo.toml --locked --offline --all-targets -- -D warnings
cargo fmt --manifest-path android-remote/rust/Cargo.toml --check
```

The loopback Iroh fixture binds only `127.0.0.1`, disables discovery, relays,
port mapping, and optional network probes, and proxies to its own ephemeral SSH
fixture. It checks the exact ALPN, leading raw SSH bytes, single-stream ownership,
password and ephemeral-key authentication, inspection, changed-pin rejection,
binary bridging, output limits, timeout, cancellation, and remote disconnect.
The tests use no existing hosts, user credentials, public relays, or receiver
deployment. Passing them establishes the stream contract with a local fixture;
it does not establish Android device behavior or live receiver interoperability.
Separate client-runtime tests also destroy the client runtime immediately after
inspection and explicit session/bridge shutdown, while the server continues on
its independent runtime, and require prompt server disconnect.
