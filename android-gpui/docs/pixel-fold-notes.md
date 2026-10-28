# Pixel 9 Pro Fold notes

Verified on the physical device (inner panel 2076×2152 px at 390 dpi ≈ 798×828 dp;
cover panel ≈ 494 dp wide). Everything here was observed on hardware, not
inferred from code. Update it when a behaviour changes.

## Device facts that shaped the client

- Herdr owns the pane grid. A client's `ClientShellHello` surface size and
  `client_shell.resize` do not change the row count, and `pane.resize` only
  nudges a split ratio. Panes therefore arrive with the daemon's own rows
  (24 here) and columns, whatever the screen can show.
- Because of that, a short pane is letterboxed with even margins rather than
  stretched. Line pitch is never inflated; a terminal's rhythm is information.
- Long lines are the client's problem, not the daemon's: the auto-fit picks the
  largest size whose widest line fits the surface width (0.70 em per cell,
  clamped 11–36, multiplied by Android's font scale), and horizontal pan covers
  the rest.

## Verified behaviour

| Area | Behaviour |
|---|---|
| Type | Fits the pane's wrap width; system font scale honoured; explicit 11–18 sp override in View |
| Pan | Client-local offset, bounded to 2400 px, accent bar on the left edge while offset, reset on pane/host change and on type change |
| Rail | Host → workspace → agent; 48 dp rows; state words beside the dots; focus marked on both workspace and agent; opens only at ≥600 dp and follows the fold |
| Toolbar | One horizontal scroller at every width; 48 dp targets; `Tabs` (actions) separated from `Tab` (key) |
| Sheets | View, Panes, Session, Cmds: short titles, caption/values, indented hierarchy, destructive actions tinted |
| Failure | Banner names the loss in plain language with Hosts and Reconnect; Reconnect opens a fresh session and never replays input |
| Privacy | Dialogs and the terminal are `FLAG_SECURE`; nothing is backed up on Android 12+ (`data_extraction_rules`) |
| Cost | Cold start 167–177 ms; idle CPU 0 % per thread |

## Verification receipt

Recorded at commit `5e228b0`, Pixel 9 Pro Fold, install over USB:

| Evidence | Value |
|---|---|
| `cargo test` (android-gpui) | 24 passed, 0 failed |
| `:app:testDebugUnitTest`, `:app:lintDebug` | pass; lint reports 0 warnings |
| Debug APK sha256 (first 16) | `dd490b9cfd23d172` |
| Device launch | `am start -W` → ok, 167–177 ms |
| Positions checked on screen | terminal, rail, bar, four sheets, failure banner, cover width, cover landscape |

Re-run the gates and compare the digest before trusting this file after further
changes.

## Measurement traps (each cost real time)

- **`FLAG_SECURE` masquerades as a black screen.** `screencap` returns black for
  a secure window. Debug builds lift the flag only when
  `settings put global herdr_probe 1`; clear it afterwards. Dialogs honour the
  same switch — without it, no sheet can be reviewed at all.
- **`adb shell input tap` in the gesture-pill zone does nothing.** The toolbar
  sits at y≈1977–2093 on the inner panel; taps below ~2113 are swallowed by
  navigation. Take coordinates from `uiautomator dump`, not from scaled
  screenshots — a 9 px error is a silent no-op.
- **`top`'s first sample reports a lifetime average.** A 250 % "idle CPU" reading
  was the process's start-up average. Sample twice, or use `top -H`, before
  believing a load figure.
- **Judge paint changes by pixel measurement.** Cross-correlate two captures to
  prove a shift happened (pan moved −548 px then returned to 0); a row-level
  negative margin looked plausible in code but moved nothing.
- **`build-verification-apk.sh` prints only warnings when the Java compile
  fails**, and the following `install` then succeeds with the previous APK.
  Grep the build output for `error:` before trusting a screenshot.

## Open items

- A single-finger horizontal drag pans the pane: the body's `on_scroll_wheel`
  listener takes the x delta and leaves vertical deltas to the list. Verified by
  `adb shell input swipe 1200 900 700 900 250` moving the pane 448 px, with a
  vertical swipe producing 0 px of horizontal drift. Multi-touch (two-finger
  pan or pinch zoom) is still unverified, because `adb shell input` cannot inject
  multiple pointers.
- Frame cadence was never measured: `dumpsys SurfaceFlinger --latency` on the
  GPUI SurfaceView returns an empty buffer while idle, so sample during
  interaction.
- **Cover-panel typography is settled by measurement, not preference.** At
  494 dp a 70–90 character pane cannot fit at a readable size: the fit floors at
  11 px and clips the tail, and dropping the floor to 10 px still clipped the
  widest lines because fitting them would need about 8 px. Panning is the answer;
  do not lower the floor again without re-measuring `widest_line_chars`.
- The cover panel is exercised by resizing the display (`wm size 1080x2152`,
  then `wm size reset`), not by folding hardware.

