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

- Horizontal drag is not mapped to the pan offset. The GPUI fork's momentum
  module computes 2D velocity (`VelocityTracker::velocity() -> (vx, vy)`) and
  emits `ScrollWheelEvent` deltas, so a horizontal drag does carry an x delta;
  what remains is intercepting it above the vertical list without breaking
  vertical scrolling. A single-finger horizontal drag is testable with
  `adb shell input swipe x1 y1 x2 y2 <ms>`; multi-touch is not.

  The interception point in `gpui-pre` 0.3.6 is `Div::on_scroll_wheel`, whose
  listener receives `ScrollWheelEvent { delta: ScrollDelta::{Pixels(Point),
  Lines(Point)}, .. }` and is dispatched on the **bubble** phase only when
  `hitbox.should_handle_scroll(window)`. Because it takes `&mut self`, the
  render chain that currently builds the pane body inline has to be restructured
  into a binding before the listener can be attached. Two gestures must be
  checked after any change: a horizontal swipe moves the pan offset, and a
  vertical swipe still scrolls the pane.
- Frame cadence was never measured: `dumpsys SurfaceFlinger --latency` on the
  GPUI SurfaceView returns an empty buffer while idle, so sample during
  interaction.
- The cover panel is exercised by resizing the display (`wm size 1080x2152`,
  then `wm size reset`), not by folding hardware.
