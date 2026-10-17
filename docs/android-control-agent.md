# Android control agent: initial design

Status: design only. No device access, background service, root execution, or ChatGPT connection is implemented by this change.

## Scope

Build an **opt-in** Android device endpoint for Herdr, separate from the existing remote terminal client. The current app is a client of remote Herdr; it must not silently become a remotely controllable server.

### Phase 1: local diagnostics

- Explicit enable/disable switch, default off.
- Local Android service with foreground notification when active.
- Read-only structured capabilities: device model, Android version, battery, available storage, process/app metadata limited by Android permissions.
- No shell command endpoint in the first implementation.
- Redact identifiers and sensitive output; enforce response size and execution time limits.
- Unit tests for disabled-by-default state, permission denial, request validation, and redaction.

### Phase 2: authenticated control

- Pair device to a single approved controller using short-lived one-time pairing and pinned public keys.
- Use an outbound encrypted connection; do not bind an internet-facing listener.
- Per-operation capability grants, expiration, revocation, and audit history.
- Add narrowly scoped shell diagnostics before considering privileged commands. Root remains off by default, separately granted, and confirmed for each privileged operation.
- Explicit approval for installs, file mutations, configuration changes, and destructive operations.
- Prevent unattended credential capture, secure-screen bypass, and unrestricted shell exposure.

### Phase 3: GUI

- Kotlin Android service for accessibility actions and UI hierarchy inspection, with user-granted accessibility permission.
- Screen capture via Android MediaProjection with OS consent and clear active indicator.
- Scoped tap, swipe, type, launch-app operations; no attempts to bypass Android permission prompts or secure windows.
- Bounded screenshots and event streams; redact sensitive UI content where possible.

### Integration

Keep Android OS APIs in the Android host layer; keep wire validation and authorization independent of GPUI rendering. Investigate existing companion authentication and Iroh transport before adding dependencies or protocol variants. Expose an MCP-compatible gateway only after pairing, revocation, and approval semantics have tests.

## Definition of done for phase 1

1. Service can be enabled and stopped explicitly, with a persistent visible indicator.
2. A local caller can request a documented, read-only diagnostic result.
3. Unauthorized or disabled requests fail closed.
4. No privileged execution, network listener, or hidden persistence is introduced.
5. Android tests and emulator runtime checks pass; physical Titan 2 testing remains separately required.

## Validation still needed

Review existing Android manifests/services, companion transport, and authorization code before implementation. Run Gradle tests, lint, and emulator checks. Do not claim runtime or device validation based on compilation alone.
