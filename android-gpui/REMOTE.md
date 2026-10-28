# Remote SSH preview

This work adds an embedded SSH client to the existing GPUI Android terminal.
Direct SSH speaks Herdr's binary `remote-client-bridge` protocol and does not
require the HTTP companion. The optional HTTPS companion workflow remains
available through **Companion hosts**. Existing Compact profiles remain in
Compact; this separately installed app does not migrate them automatically.

The remote build has application ID `dev.herdr.android.gpui.remote`. It installs
alongside Compact 0.4.0 because the old development signing key did not survive
the build workspace reset. It does not overwrite Compact or its saved profiles.
See REMOTE-VALIDATION.md for the exact build and runtime evidence. Accompanying dependency notices are in android-remote/THIRD-PARTY-NOTICES.txt.

## Host prerequisites

Use a reachable SSH server and an existing SSH account, with Herdr already
installed and its requested session running. The application does not install
or upgrade Herdr, configure SSH access, or change a firewall.
Use password authentication or provide an existing compatible OpenSSH/PEM
private key and its optional passphrase. Passwords and passphrases are used for
one connection and are not stored in the profile. A private key can be saved
explicitly as one device-wide, Android Keystore-encrypted identity outside every
profile; that copy is decrypted only for a connection that names it, and its
passphrase is never saved. The app does not generate or install SSH keys.
This build enables Ed25519 and ECDSA key algorithms; RSA and DSA support is not
enabled. SSH configuration files, agent forwarding, jump hosts and interactive
multi-factor authentication are not implemented.

Herdr must advertise endpoint generation 1 and the `surface_interest`,
`presentation_effects_fence` and `health_check` capabilities. Official Herdr
0.9.3 reports these prerequisites. The client still checks the actual host's
status rather than assuming compatibility from its version string. It preserves
candidate discovery and the conditional `--idle-timeout-v1` bridge negotiation.

The app refuses a session reported stopped by its initial probe. However,
[Herdr 0.9.3's own bridge](https://github.com/herdrdev/herdr/blob/v0.9.3/src/remote/host.rs)
starts the daemon if it is down when the bridge executes. A stop between the
probe and bridge can therefore start it again. The Connect dialog discloses
this; this preview cannot promise attach-only behavior with that upstream
bridge. It does not reconnect or restart it automatically in the background.

The Herdr session name is explicit in each profile. Leave it at `default` only
if that is the session you want. The daemon and terminal processes stay on the
host when the phone disconnects.

## Connecting

1. Open **Remote hosts · SSH**, add a direct TCP profile, and enter the host,
   SSH port, username and Herdr session
2. Inspect the server key. Compare the displayed SHA-256 fingerprint with a
   trusted copy from the host administrator before explicitly trusting it
3. Connect and enter the SSH password, choose **Use private key** for a
   one-use key, or save a key once from **Remote hosts** and choose
   **Use saved SSH key**. Passwords and pasted keys are used once; only an
   explicitly saved identity is kept
4. Select a pane, then use the GPUI terminal and **Cmds** palette

Inspection retrieves the public server key without authenticating. Credentials
are sent only after the next connection verifies the explicitly saved key.
A changed key blocks connection; it is never silently accepted. Verify the new
key separately before using the explicit forget/retrust flow. Changes to the
profile's connection identity clear its trust, and stale dialogs cannot save a
pin for a different profile revision.

Host settings, trusted fingerprints, extra keys and OMP quick commands are saved
in an AndroidKeyStore-encrypted profile file excluded from backup. An explicitly
saved SSH identity is stored separately in the same Keystore-backed encrypted
form and is removed only by its own **Remove saved SSH key** action. Password
drafts, pasted keys and passphrases are not saved in profiles or framework
state. Authentication failure does not trigger automatic retries.

## Input and lifecycle

The binary client preserves its remote health checks, bounded queues, surface
validation and daemon boot-ID fences. Only the selected pane receives input.
Focus changes are explicit and input waits for the corresponding host state.

Binary semantic input has no daemon acknowledgement. **Input queued over SSH**
means it entered the client queue, not that the shell consumed it. Send + Enter
is one ordered semantic batch containing the text and Enter. The palette still
requires confirmation that OMP is idle with an empty prompt and never clears
existing input. A failed connection can leave an action applied at the host;
inspect the terminal before retrying. Nothing is replayed automatically.

Leaving the remote terminal closes its connection and discards pending input.
Reconnect from Remote hosts and enter credentials again. A daemon restart,
deleted pane or stale launch cannot silently retarget a command.

The first remote renderer continues to show bounded plain text extracted from
the selected, validated binary surface. Exact VT colors, cursor, graphics and
unlimited scrollback are not implied by the new transport.

**Panes** in the terminal lists the session's workspaces, tabs and panes from
the daemon snapshot, each with its agent status. Choosing a pane sends one
`pane.focus` request and keeps the same connection; the surface that follows is
validated against the same boot ID and projection revision. Tab and workspace
switch requests and a sidebar of their own are not implemented yet, so those
rows are labels rather than controls.

## SSH over Iroh

Choose **Iroh + SSH** and enter the receiver's endpoint ID, SSH username and
Herdr session. The app uses Iroh's N0 address discovery and relay configuration;
an ephemeral client identity is sufficient. Credentials still belong to SSH,
and the server key is verified through the same explicit trust flow as TCP.

The wire interoperability target is
[rustonbsd/iroh-ssh 0.2.12](https://github.com/rustonbsd/iroh-ssh/tree/d0e9b6a823d25bf5b02757bbf9c17edeb39cfad5):
ALPN `/iroh/ssh`, one bidirectional stream carrying raw SSH bytes. That receiver
must run on the remote host and forward to its existing SSH server; Iroh does
not replace SSH authentication or host-key verification. The receiver chooses
the SSH destination port. It is not configured or deployed by this app. No
companion bearer token is required. The reference receiver does not add its own
peer allowlist; knowing its endpoint ID permits reaching the SSH server, whose
authentication remains essential.

Transport tests use local Iroh endpoints with discovery and relays disabled.
They verify raw SSH, exact ALPN, pinning, output bounds and closure even when the
client runtime ends. Public relay/NAT traversal, an actual deployed receiver,
and physical Android network transitions require separate verification.

## Address and authentication compatibility

Direct TCP accepts a hostname, IPv4, bare IPv6 (`2001:db8::1`), or bracketed IPv6
(`[2001:db8::1`). Enter the SSH port in its separate field; `[address]:port` and
URLs are not hostnames. Brackets are stripped only for valid IPv6 literals when
dialing; saved profile identity and SSH trust are not rewritten. Bracketed scoped
IPv6 addresses (`[fe80::1%interface]`) are not supported by this normalization.

The same SSH authentication policy applies over TCP and Iroh. A failed attempt
now distinguishes a rejected credential, a method the server does not offer at
that stage, and a credential accepted only as the first authentication step.
An unavailable method can reflect server policy or account policy, not necessarily
a bad password or key. Empty method lists remain a generic authentication failure.
The app never retries with a different method automatically. Keyboard-interactive,
interactive MFA and multi-step authentication remain unsupported; no authentication
or host-key restrictions are relaxed by these diagnostics.

See [compatibility validation](TRANSPORT-COMPAT-VALIDATION.md) for tested cases,
upstream source references, and the distinction between local synthetic Iroh
coverage and deployed receiver interoperability.
