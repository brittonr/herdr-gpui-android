# Native core self-tests

Run `sh android/tests/run.sh` with a Java 8+ JDK. No Android SDK, Gradle,
network, external dependencies, credentials, or real host is used. If an image
omits the `javac` launcher but retains the JDK compiler module, the script uses
that module. It checks Java 8 language/bytecode compatibility; a newer runtime
without `ct.sym` cannot additionally verify the Java 8 API surface using
`--release 8`. The core uses only Java 8-or-earlier APIs.

The in-memory HTTP peer tests HTTPS/base-path validation, traversal and
cross-origin rejection, token/header restrictions, no redirect following,
bounded requests/responses, strict UTF-8, redacted errors, fixed-length POST
streaming, no application retries, single-flight requests and cancellation.
Shortcut tests cover the actual daemon key vocabulary, modifier combinations,
sequences, configuration limits, explicit escapes and Unicode preservation.
The input-routing gate is tested against all 128 combinations of its seven
booleans; only the enabled terminal-screen/terminal-focus/physical-keyboard
combination with no editor focus, software keyboard or dialog may send input.

These are deterministic JVM core tests. They do not demonstrate Android TLS
behavior, AndroidKeyStore, a real device's hardware keyboard/IME, lifecycle,
layout, or a live Herdr connection. Those need Android and integration QA.

## UI integration contract

- `HostProfile(name, baseUrl, token)` validates once. Use HTTPS, without URL
  credentials, query or fragment; a plain URL-safe base path is supported.
  `getBaseUrl()` returns a canonical trailing-slash URL. Tokens must contain
  1–4096 visible ASCII characters, with no whitespace. Store them encrypted.
- `CompanionClient` is blocking, single-flight and **must run off the UI thread**.
  Each selected host gets a new instance. `cancel()` immediately sets a permanent
  closed flag without doing I/O; call this on navigation/stop before shutting down
  queued work, then call `close()` off-main to disconnect the active socket.
  UI callbacks must additionally check their own host/selection generation so
  a stale response cannot change a newer screen. Never retry POSTs automatically.
- The transport returns raw JSON. Android's `org.json` can parse it. Convenience
  methods include `getPanes`, `getAgents`, `getScreen`, `getTranscript`, `sendText`, `sendKeys`,
  `sendPrompt`, and `sendShortcut`. Generic routes are relative, e.g. `v1/panes`.
  Pane IDs such as `w1:p1` are safely encoded by the convenience methods.
- `ClientException` includes a `Kind`, HTTP status or zero, and
  `actionMayHaveApplied()`. Its diagnostic is safe to show without appending
  platform exception messages or a request/response body. An interrupted POST
  may have reached the host. Refresh before deciding to resend it.
- Named keys are `enter`, `esc`, `tab`, `space`, `backspace`, arrows, `y`, `n`,
  `1`–`9`, `ctrl+a`–`ctrl+z`, `alt+a`–`alt+z`, `shift+tab`, and `f1`–`f12`.
  No Home/End/Delete/PageUp/PageDown or combined Ctrl+Alt chord is fabricated.
  `KeyChord.fromPhysical` accepts a logical key plus Ctrl/Alt/Shift booleans.
  Unsupported combinations throw; the UI must not silently drop modifiers.
- `InputRouting.shouldSendToTerminal(enabled, screen, terminalFocused,
  editorFocused, physicalKeyboard, softKeyboard, dialogOpen)` must guard live
  key routing. Android's event/device inspection supplies these booleans.
- `Shortcut.parseConfiguration` accepts up to 24 lines such as:

  ```text
  Stop = key:ctrl+c
  Pick = keys:esc,down,enter
  Greeting = text:Hello, 世界\n
  ```

  A single `key:` requires one chord; `keys:` permits 1–16 comma-separated or
  whitespace-separated chords. Text preserves whitespace and Unicode; escapes
  are `\\`, `\n`, `\r`, and `\t`. Text macros never add Enter. Nonempty text is
  bounded to 65,536 UTF-8 bytes. Text is raw terminal input, so an explicit
  newline/control character can execute a command or change terminal state.
  Labels must be unique and 1–40 characters. Configuration is capped at
  262,144 characters. `formatConfiguration` round-trips supported values.
