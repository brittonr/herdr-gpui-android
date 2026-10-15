# Synthetic SSH fixture verification

Verified on 2026-10-07 in the Linux cloud workspace. All connections used local
TCP and an ephemeral in-memory SSH host key. No real Herdr daemon, remote host,
user credentials, terminal process, or shell command was used.

After sourcing the task-local toolchain environment:

```sh
CARGO_TARGET_DIR=android-gpui/rust/target cargo test --manifest-path android-remote/fixtures/Cargo.toml --locked --offline
cargo fmt --manifest-path android-remote/fixtures/Cargo.toml --check
CARGO_TARGET_DIR=android-gpui/rust/target cargo clippy --manifest-path android-remote/fixtures/Cargo.toml --locked --offline --all-targets -- -D warnings
```

All six integration tests passed; formatting and strict Clippy passed:

- Actual TCP SSH drives the production Android Rust adapter through host-key
  inspection, exact probe/bridge negotiation, shared generation-1 handshake,
  focus acknowledgement, matching revisions, Unicode text, ordered submit and
  key batches, health ping/pong, disconnect, and reopen without replay
- Wrong pinned fingerprint prevents probe and bridge execution
- Close during a deliberately stalled bridge negotiation invalidates the
  adapter handle immediately and closes the actual accepted SSH socket
- Arbitrary SSH exec requests are rejected, never executed
- Fragmented shared-schema hello succeeds; an oversized frame prefix closes
  only that peer and leaves a new compatible probe available
- Shutdown cancels an unfinished SSH handshake and releases the fixture's port

The compiled CLI was also launched as an exact owned child. Its startup JSON
was checked for synthetic labeling, loopback binding, assigned port, SHA256
fingerprint, and public test login. SIGINT exited successfully within three
seconds. The binary is `android-gpui/rust/target/debug/herdr-synthetic-ssh-fixture`
when built using the target directory above.

These results prove synthetic wire/adapter integration, not compatibility with
a real Herdr daemon, Android-native rendering/input, or an Iroh relay. Those
checks require their own evidence.
