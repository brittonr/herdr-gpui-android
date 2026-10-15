# Validation record

Implementation baseline: PR248 commit
`273a7bfea714374f10a03445a0a679359f21117c`.
Checks below were run on the Linux cloud build machine on 2026-10-07.

## Passed

- `sh android/tests/run.sh`: **345 assertions**, no compiler warnings
  - HTTPS/host/path/ID/token validation and cross-host/path traversal rejection
  - Fixed supported key grammar, modifiers, Unicode and text/shortcut bounds
  - 128-case truth table for physical keyboard routing versus editor/IME focus
  - Exact JSON bodies, pane ID escaping, auth headers, redirect refusal,
    response/transfer bounds, redacted errors, timeouts, single-flight request
    admission, cancellation, and no application-level write retry
  - Uses fake HTTP connections; this is not live TLS/Android-network testing
- All Android app Java sources compiled for Java8 against the Android15
  framework JAR. Android lifecycle APIs produce deprecation notes, not errors.
- SDK-free verification APK build: resources compiled and linked, app bytecode
  converted to DEX, APK signed/aligned; v2/v3 signatures and package/DEX/ZIP
  structure verified. Framework classes are not bundled.
- `cargo fmt --all -- --check`
- `cargo clippy --locked -p herdr-companion --all-targets --all-features -- -D warnings`
- `cargo build --locked -p herdr-companion --release` and `herdr-companion --help`
- **70 companion tests passed**; see the eight blocked tests below
- `git diff --check`
- Independent static security/correctness review, followed by fixes and recheck:
  physical-key focus and IME isolation, unsupported modifiers, duplicate sends,
  cancellation/lifecycle state, bounded approval details, ACK validation,
  interrupted AtomicFile recovery, and stale new-agent navigation

## Blocked / not claimed as a pass

- Full `cargo test --locked -p herdr-companion` reports **70 passed, 8 failed**.
  All eight fail because this environment refuses Unix-domain socket creation
  (`Operation not permitted` / EPERM), including existing fake-Herdr tests.
  They do not reach the RPC assertions. The tests remain present and enabled;
  the focused suite excluding these socket-dependent tests passes.
- A normal Gradle `:app:assembleDebug :app:lintDebug` did not run to completion.
  This machine has no Android SDK; a wrapper invocation using task-local cache
  also failed to fetch the distribution from Java (`Network is unreachable`).
  The separate verification APK is real, but is not a claimed SDK lint pass.
- No Android device/emulator installation or runtime tests were performed.
- No connection to a real Herdr host or Claude session was made. Exact upstream
  method names/key grammar were checked in source; end-to-end integration is
  still required.
- No whole-workspace `just ci`, GPUI native UI, Windows cross-check or native
  performance run. Desktop source was not changed.
- No remote push, PR, store publication, host setup, credential provisioning,
  signing-key installation, or VPN/P2P changes were performed.

The verification build uses a framework implementation JAR rather than public
SDK stubs. All app APIs were selected for minimum API26, but Android Lint and a
standard SDK build must independently verify minimum/public API compatibility.

## Device acceptance checklist

Use an isolated test host and non-production token.

1. Install using Android's package installer or your normal adb workflow.
   Confirm startup and sensible insets on API26 and Android15+; inspect a small
   phone, landscape, font scaling, and a physical-keyboard phone/tablet.
2. Add two distinct HTTPS hosts. Edit one, switch repeatedly, rotate, background,
   lock/unlock and kill/restart. Verify profile encryption, restore after an
   interrupted save, and that no host's token/terminal output is shown for another.
3. Reject wrong tokens, untrusted certificates and redirects. Show a clear
   disconnected state after network changes. Check no token enters diagnostics.
4. Open a shell pane with a colon ID (for example `w1:p1`). Type ASCII, emoji,
   non-Latin text and multiline paste. Confirm text is exact and no Enter is
   appended. Tap Enter separately and verify correct target.
5. Turn physical keys on, type and repeat keys. Exercise Ctrl-C/Ctrl-D, Alt-letter,
   arrows, Shift-Tab, Backspace and F1–F12. Focus the composer and a dialog and
   ensure typing stays local. Software IME Enter/Delete must not hit the host.
   Reject Meta/Sym/Function and unsupported combined modifiers visibly.
6. Change shortcut labels/order and text macros per host. Verify invalid keys,
   malformed escapes and oversize settings cannot save. Verify intentional
   newline macros execute only when tapped.
7. Interrupt an in-flight send by switching host/backgrounding. Old queued
   actions must not replay. Simulate a read failure while a send is queued and
   repeated Send taps: only the intended single payload should arrive.
8. Read a hooked Claude transcript, send a reply, interrupt, allow/deny a request,
   and return an AskUserQuestion to the terminal. An oversized request must not
   offer Allow for content that was not fully displayed.
9. Start an agent; simulate timeout, cancellation and navigation away. Preserve
   the newer screen/draft, re-enable the dialog after failure, and check Agents
   before retrying any uncertain start.
10. Remember that snapshots intentionally omit VT colors/cursor fidelity,
    terminal resizing and scrollback; these are not acceptance criteria for this
    companion preview and require a future surface-protocol client.
