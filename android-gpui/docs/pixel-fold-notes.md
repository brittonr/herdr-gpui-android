# Pixel 9 Pro Fold notes

Verified on the physical device (inner panel 2076×2152 px at 390 dpi;
`am get-config` reports 852×883 dp). Narrow-layout checks use a temporary
1080×2152 px override, reported as 443×883 dp; they do not prove a physical fold
or cover-panel handoff. Behaviour below was observed on hardware unless
explicitly marked unverified. Update it when a behaviour changes.

## Device facts that shaped the client

- Herdr owns the pane grid. A client's `ClientShellHello` surface size and
  `client_shell.resize` do not change the row count, and `pane.resize` only
  nudges a split ratio. Panes therefore arrive with the daemon's own rows
  (24 here) and columns, whatever the screen can show.
- Because of that, a short pane's rows are spread over the body's height, up to
  twice their natural pitch, so the type size and every column stay; the lines
  read more widely spaced. Only a pane with fewer rows keeps even margins, and
  when the program paints its own background on most of the pane's edge, that
  colour fills them instead of the palette's.
- Long lines are the client's problem, not the daemon's: the auto-fit picks the
  largest size whose widest line fits the surface width (the bundled Noto Mono
  advance, 1229/2048 em per cell, clamped 11–36, multiplied by Android's font
  scale), and horizontal pan covers the rest.
- The bundled Noto Mono has no box-drawing, block, braille, arrow or geometric
  glyphs; gpui-mobile draws those from Android's proportional system fonts. Rows
  are therefore painted with a forced per-cell advance, which is also what lets
  the fit use the font's true advance instead of the old 0.70 em allowance.
  Box-drawing and block characters no longer use those fonts: the renderer
  draws them as shapes filling the cell (see Drawn box characters).

## Verified behaviour

| Area | Behaviour |
|---|---|
| Type | Fits the pane's wrap width; system font scale honoured; explicit 11–18 sp override in View |
| Pan | Client-local offset, bounded by the painted row overflow (at most 2400 logical px), so it stops with the last column at the screen edge and reverses on the next tap; accent bar on the left edge while offset; survives keyboard open/close and applying unchanged View settings; resets on confirmed pane/session switches and changed display preferences |
| Rail | Host → workspace → agent; 48 dp rows; state words beside the dots; live host and focused pane on a tonal selected container and reported as selected; persistent at ≥600 dp, a modal drawer over a scrim below it (Back, scrim tap or a choice closes it); re-sized when the window width changes |
| Toolbar | Icons with tooltips and spoken labels: Hosts, Panes, Keyboard and Commands, then View, Session and Close at ≥600 dp or one More menu below it; pan arrows, Ctrl and the shortcut keycaps follow a divider and scroll; 48 dp targets throughout |
| Sheets | View, Panes, Session, Commands: DeviceDefault light or dark dialogs matching the palette, sentence-case buttons, indented hierarchy, current pane marked, destructive actions in the theme's error colour; Panes lists tabs only for multi-tab workspaces; command titles and captions share one edge, pins trailing |
| Letterbox | Palette background, or the commonest painted edge colour once three quarters of the pane's outer cells are visibly unlike the palette (held until under half); the daemon's default-background borders and scrollbar show the same surround |
| Tabletop | A half-open fold across the window ends the terminal at the fold, with the host's keys as a deck of 56 dp keys below it over the toolbar; an open keyboard restores the normal layout; a fold down the window (book) is not split |
| Host list | Painted in the terminal's theme; a titled header with Add host; a card per host (initial, address, pinned fingerprint or untrusted warning, one Connect, ⋮ for edit, keys, trust and delete) in a 720 dp column; device key, reopen switch, companion and diagnostics as one group under This device; host-key check, sign-in and pane choice show a back arrow that Back also follows |
| Failure | Banner names the loss in plain language with Hosts and Reconnect; Reconnect opens a fresh session and never replays input |
| Privacy | Dialogs and the terminal are `FLAG_SECURE`; nothing is backed up on Android 12+ (`data_extraction_rules`) |
| Cost | See the sidebar and foreground CPU measurements below; a quiet single session does not represent live multi-host cost |

## Local diagnostics, verified end to end

Exercised on the device with the signed release bundle rather than read from
code: the screen opens with "Nothing is read until you choose Read diagnostics."
and an enabled **Read diagnostics**; reading reports "Read on this device just
now. Nothing was stored or sent." and fills the output; leaving with Home and
returning shows the first message again with the output cleared. The window is
`FLAG_SECURE`, so a screenshot is black, and the installed APK requests only the
Internet, vibration and notification permissions.

The output's text is not exposed to `uiautomator`, so its contents are checked
in-process instead, by `HERDR_VM_DIAGNOSTICS_ONLY=1 vm-tests/run.sh` on an
Android 15 emulator. All seven checks pass: the app declares no service or
foreground-service permission, nothing is shown before a read, a read shows
exactly `schema_version`, `device_model`, `manufacturer`, `android_sdk`,
`battery_percent` and `app_storage_available_bytes`, and pausing or recreating
the screen clears it.

## Themes

Four palettes ship: Default, Nord, Mocha and Latte (light), plus a **Follow
system** choice that paints Latte in light mode and Mocha in dark and repaints
on the system switch without a restart. The GPUI surface is fully
palette-driven, and so is the Android chrome around it: `ChromeRoles`
derives surface, text, outline, accent, tonal-container and status roles from
the palette, and both the host list and the terminal read them through
`GpuiChrome.load` (`nativeThemeColors`) for the system bars, toolbar, rail,
banner, cards and sheets. A light palette gets dark status-bar icons and the
DeviceDefault light dialog theme.

The terminal connection also reports the palette to Herdr as its host terminal
theme (`ClientShellHostTheme`), which Herdr applies while the phone is its
foreground client. Before this, the phone reported nothing, and Herdr reset its
theme to unknown each time the phone attached.

Several palette colours failed text contrast on their own chrome surface
before the roles existed - Latte muted 4.06:1 and accent 4.04:1, Latte yellow
2.15:1, Nord red 2.46:1 - so text roles are blended toward the primary text
colour until they reach 4.5:1. Dots and fills keep the palette's own colours
because a state word always sits beside them.

## Sidebar CPU verification

Background mirrors previously requested active terminal surfaces, continuously
decoding and projecting panes the rail never displayed. They now negotiate
inactive surface interest in the initial hello. Snapshot metadata and health
checks remain active; selecting another host opens a surface-active connection.
The existing negotiated-capability checks and presentation-effect fence remain
in force. No Herdr daemon or protocol changes are required.

Measured on the physical Pixel with the rail visible, four saved hosts (one
terminal plus three mirrors), and the same remembered live pane. Each build has
three approximately 15-second samples. CPU is derived from process utime+stime
deltas with the device's 100 Hz clock; 100% means one fully occupied CPU core.
An A/B/A/B reinstall check retained profiles and restored the old load when the
old APK was reinstalled.

| Build | Weighted mean process CPU | Sample range | APK SHA-256 prefix |
|---|---|---|---|
| Before metadata-only mirrors | 340.5% | 333.3–346.3% | `1e70eee72faf35da` |
| With metadata-only mirrors | 93.6% | 91.3–95.0% | `0b27387243d0270b` |

This is 72.5% less measured process CPU for that workload, not a battery-life,
frame-rate or universal idle-cost claim. Thread samples still show all four
session managers; the background mirror workers become nearly idle instead of
processing continuous surfaces. Sidebar pane descriptions were observed changing
while a background host stayed inactive, and selecting that host produced its
real GPUI terminal surface. Hiding the rail reduces session-manager threads from
four to one; showing it restores four and repopulates the tree. The original host,
pane and display preferences were restored, and the keyboard/pan interaction was
re-exercised on the new APK.

The new ARM64 APK, native-source freshness, signatures, JVM tests and both Android
lint variants pass. The standalone Rust suites pass with 28 default and 50 remote
tests. The existing `herdr-client` inactive-hello integration test also passes,
including delivery of snapshots and acknowledged surface-interest activation.
It can be selected through this standalone dependency graph without modifying
the root workspace lockfile:

```sh
cd android-gpui/rust
cargo test --locked --features remote -p herdr-gpui-android -p herdr-client \
  inactive_hello_and_surface_interest_use_upstream_contract
```

## Foreground navigation CPU verification

Terminal output previously invalidated navigation along with the terminal frame,
causing repeated pane-title lookup and unnecessary UI-thread sidebar work. Navigation
now has its own content token covering boot/pane identity, daemon focus and the
pane/workspace/tab metadata. Terminal paint, connection status and scrolling still
update independently without invalidating that token. Confirmed selection and
metadata changes continue to refresh the header and rail; input cadence is unchanged.

Measured on the same physical Pixel with one live terminal and three background
mirrors, the rail visible, and the same host, pane and display preferences. Each
build has three approximately 15-second samples plus one repeat sample in an
A/B/A/B reinstall sequence. Process and main-thread CPU use utime+stime deltas
with the device's 100 Hz clock; 100% means one fully occupied CPU core.

| Build | Weighted process CPU | Weighted UI-thread CPU | APK SHA-256 prefix |
|---|---|---|---|
| Metadata-only mirrors, before navigation cache | 111.6% | 17.51% | `0b27387243d0270b` |
| Separate navigation token | 72.5% | 0.87% | `31164c3c80eae021` |

That is 35.0% less process CPU and 95.0% less UI-thread CPU for this sampled
workload. Reinstalling the old APK restored UI-thread CPU to 17.25%; reinstalling
the optimized APK reduced it to 2.55% in the repeat interval. These are workload
measurements, not battery-life, frame-rate or universal idle-cost guarantees.

The optimized ARM64 APK was installed and exercised on the phone. Selecting an
idle pane changed the actual terminal and header, then returning restored the
original pane. The cached Panes sheet, pan controls and keyboard open/close were
exercised at inner-panel and simulated 443 dp widths. The narrow check does not
prove a physical fold. Four native session managers remained present. Original
host/pane, display preferences, display size and rotation policy were restored;
the capture override was removed and the activity restarted, with `dumpsys window`
confirming the visible terminal window has `SECURE` set.

The standalone Rust suites pass with 28 default and 52 remote tests, including
regressions separating terminal invalidation from navigation and retaining
identity, focus, label and status changes. All 10 JVM tests pass, and both debug
and release Android lint reports say “No issues found”. This does not renew the
whole-desktop-workspace gate or the separate real-daemon input/lifecycle claims.
Raw CPU samples, APK identities and device captures are retained privately, not
included as terminal output in the repository.

## Typed surface and sidebar ownership verification

Native surface projection, history decoding and renderer parsing now share typed
rows with six-integer runs instead of constructing a generic JSON tree per span.
The JSON shape, plain-text fallback and existing byte/row/span, UTF-8 boundary,
attribute and hyperlink checks remain in place. A temporary optimized host probe
parsed the same 200,109-byte styled surface 100 times: 1.327 ms per parse before,
0.606 ms after. This host result is not an Android timing claim; the probe was
removed before the native build.

The phone comparison isolates one live remote terminal with the sidebar hidden,
the same host/pane/theme and the same inner-panel configuration. The baseline
needed an explicit show/hide transition to close its startup mirrors. Three valid
baseline samples and two valid optimized samples, each approximately 15 seconds,
were collected across repeated old/new APK reinstalls. All counted windows kept
the terminal foreground and exactly one native session manager. CPU uses process
utime+stime deltas and the device's 100 Hz clock, weighted by elapsed time; 100%
means one fully occupied CPU core.

| Build | Weighted process CPU | Sample range | APK SHA-256 prefix |
|---|---|---|---|
| Navigation cache, generic surface trees | 70.9% | 70.75–71.18% | `31164c3c80eae021` |
| Typed rows and visibility-owned mirrors | 60.0% | 59.88–60.13% | `6887ec0a7098283a` |

This is 15.4% less measured process CPU in that controlled workload. One window
was rejected because another app took the foreground. Earlier multi-host windows
were excluded from this comparison because their background host count changed.
This is not a battery-life, frame-rate or universal idle-cost claim.

Device checks also exposed mirrors starting despite a hidden sidebar. Sampling
now follows both activity lifecycle and actual sidebar visibility. Each scheduled
tick retains its own host-sample owner, and a connection result is admitted only
while that same owner remains current. Connection handoff and closure share a
short lock; transport work stays off the UI thread.

| Physical Pixel scenario | Native session managers |
|---|---|
| Cold start with sidebar hidden | 1 |
| Show the sidebar | 4 |
| Automatically collapse at simulated 443 dp width | 1 |
| Restore the original wide window | 4 |
| Rapid wide/narrow transitions, ending narrow | 1 |
| Restore wide after rapid transitions | 4 |
| Background the activity | 0 |

The final ARM64 APK was installed with its existing development signature and
app data retained. Colored terminal rendering, horizontal pan and keyboard
open/close were exercised at inner and simulated narrow widths; a vertical
history drag and **Jump to live output** were also exercised. The narrow override
does not prove a physical fold or cover-panel handoff. No terminal text or
execution keys were sent. Original host/pane, sidebar preference, theme, display
size, rotation and wake settings were restored. The capture override was removed
and the activity restarted; its visible terminal window was confirmed `SECURE`.

Verification: 30 default and 54 remote-feature Rust tests pass, including escaped
Unicode/style/link preservation and malformed span, numeric, geometry and size
limits. All 10 JVM tests pass; debug and release Android lint report “No issues
found”. The native build and packager verify current-source freshness, ARM64
API 29 ELF, 16 KB native alignment and APK v2/v3 signatures. Whole-desktop-workspace
verification and full real-daemon input/lifecycle conformance are not renewed by
these scoped checks. Raw samples and screenshots remain private.

## Flat styled-row verification

Rows without hyperlinks now use one GPUI `StyledText` with multiple `TextRun`s
instead of a flex element, allocated ID and separate one-run text layout per
span. Linked rows retain the interactive span path. The native surface format,
terminal validation, input scheduling and palette attributes are unchanged.
Matched same-viewport captures show the old vertical strokes at span boundaries
and their absence on the flat-row path; the frame's actual borders remain.

Measured on the physical Pixel inner panel, one active terminal with the sidebar
hidden, the same host/pane and display preferences. Each APK has three valid
approximately 15-second windows across A/B/A/B reinstalls. All counted windows
keep the terminal foreground and exactly one native session manager. CPU uses
process utime+stime deltas at the device's 100 Hz clock, weighted by elapsed time;
100% means one fully occupied CPU core.

| Build | Weighted process CPU | Sample range | APK SHA-256 prefix |
|---|---|---|---|
| Per-span row elements | 60.5% | 60.25–60.86% | `6887ec0a7098283a` |
| Flat unlinked rows | 56.5% | 53.08–58.58% | `f8d61d010389a217` |

That is 6.6% less process CPU in this sampled workload. Reinstalling the old APK
restored 60.86%; the optimized repeat measured 58.58%. This does not measure
battery life or frame cadence, and does not predict savings on linked rows.

The actual new ARM64 renderer was installed and visually checked at inner and
simulated 443 dp narrow widths. Horizontal pan and keyboard open/close were
exercised at both sizes, including revealing the narrow toolbar's pan control by
scrolling its key strip. The narrow window kept one session manager; restoring
the sidebar repopulated four. Original host/pane, sidebar preference, theme,
display size and rotation were restored. The capture override was removed and
the activity restarted; the visible terminal window was confirmed `SECURE`.
No terminal text or execution keys were sent and no browser link was opened.

Verification: 30 default, 54 remote-feature and 60 host `android-ui` Rust tests
pass. The new bundled-font shaping regression preserves Unicode byte indices and
column positions across color/decoration boundaries at 11, 14, 18 and 36 px.
The 10 JVM tests and debug/release Android lint gates pass. The native build and
packager verify current-source freshness, ARM64 API 29 ELF, 16 KB alignment and
APK v2/v3 signatures. Physical folding, full real-daemon input/lifecycle behavior,
and Windows execution remain outside this receipt. The separate desktop
`just ci` gate stops at pre-existing formatting differences in
`crates/herdr-client/src/{lib,ssh}.rs`; both `just lint-windows` and the
Ubuntu-container `just lint-linux` stop because the root `Cargo.lock` needs an
update under `--locked`. Neither unrelated source nor the root lockfile was
changed to bypass those failures.
Raw profiles, samples and terminal captures remain private.

## Viewport-continuity verification

Viewport-continuity change, local uncommitted source, Pixel 9 Pro Fold,
development-signed ARM64 APK installed over USB:

| Evidence | Value |
|---|---|
| `cargo test --locked` (android-gpui/rust) | 28 passed, 0 failed |
| `cargo test --locked --features remote` | 50 passed, 0 failed |
| `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:lintRelease` | pass; both lint reports say “No issues found” |
| Native build and verification APK | pass; current-source freshness, ARM64 ELF, 16KB alignment and v2/v3 signatures verified |
| Debug APK sha256 (first 16) | `1e70eee72faf35da` |
| Device launch | `am start -W` → ok, 182 ms |
| Interaction checks | pan through keyboard open/close at inner and simulated narrow widths; unchanged View Apply preserves pan; 16sp selection and confirmed pane switch reset it |
| Control bounds | both pan arrows grew from 107 to 117 px wide (44 to 48 dp); Esc and Tab measure 117 px when fully visible; pinned controls stay fixed while the key strip scrolls |
| Desktop workspace gate | not green: pre-existing formatting differences in `herdr-client` and root `Cargo.lock` drift; locked Clippy stops before checking code; workspace tests not reached, unrelated source and lockfile left untouched |

The regression test checks native pan retention for unchanged/invalid preferences,
reset on font or system-scale changes and new generations, and stale-generation
rejection. Android inset/keyboard behaviour is separately exercised on the phone;
the unit test is not a substitute for that UI check. Display overrides, saved
display preferences and the original pane were restored. The capture probe was
removed and the activity restarted; `dumpsys window` confirms the visible
terminal window has `SECURE` set again.

Re-run the gates and compare the digest before trusting this receipt after
further changes. The other visual/theme/failure observations above predate this
receipt and were not all re-exercised by the viewport check.

## Native terminal presentation verification

The active SSH terminal now prepares its GPUI snapshot directly in Rust instead
of sending the styled surface through Java JSON decoding and back over JNI.
At that stage Java still received bounded plain text for Copy pane text and the
navigation, scroll, clipboard and notification metadata it owns. The later
on-demand Copy change removes that last body transfer. Local chrome updates keep
the held styled body; initial, empty and HTTPS snapshots use the plain-text
ingress. Both new JNI entry points are required by the native ELF verifier.

A phone profile attributed 34.8% of sampled process cycles to the Java
`JSONObject` constructor path. Projection identity/equality caches were also
tried, but removed because neither materially reduced this phone workload's CPU.

The final production-source comparison uses the same foreground host/pane,
hidden sidebar, palette, font settings and 852 dp inner-panel width. Four
approximately 15-second windows per build were collected across A/B/A/B APK
reinstalls. Every counted window retained exactly one native session manager and
kept the terminal foreground. CPU is weighted by elapsed time from process
utime+stime deltas at the device's measured 100 Hz clock; 100% means one fully
occupied core.

| Build | APK SHA-256, first 16 | Weighted process CPU |
|---|---|---:|
| Java styled-surface round trip | `f8d61d010389a217` | 58.024% |
| Native terminal presentation | `b13b4609db6da735` | 49.946% |

This is **13.9% less process CPU** for that workload, not a battery-life, FPS or
latency claim. Input and rendering cadence were not throttled.

On-device checks exercised colored rendering, an actual pane switch and return,
horizontal pan and keyboard open/dismiss at the inner-panel and simulated 443 dp
widths, and sidebar ownership. A visible sidebar retained four native sessions,
hiding it retained one, and backgrounding released all sessions; explicit
reopening restored the original session. The history drag and Jump to live
actions were exercised, but the selected pane did not show a distinct older
viewport, so non-empty-history motion is not newly proven by those captures.
An isolated producer regression separately verifies anchored styled history and
the return to the live frame.

Verification passes: 34 default, 60 remote-feature and 66 host `android-ui` Rust
tests; all 10 JVM tests; debug/release Android lint; and 19 native ELF verifier
tests. The actual ARM64 build and APK packager verify source freshness, API 29,
16 KB alignment, required JNI exports and v2/v3 signatures. The final native
library SHA-256 starts `c94edb4480f86ca2`. Original host/pane, sidebar and display
preferences, display size and rotation policy were restored. The capture probe
was removed and the activity restarted; the fresh visible terminal window is
`SECURE`. Exact staged phone captures were removed; raw evidence remains private.
Physical folding, Windows execution and full desktop-workspace or real-daemon
input/lifecycle conformance are not renewed by this scoped receipt.

## Navigation payload reuse verification

Active terminal polls now acknowledge the full-frame and navigation tokens
separately. The Java connection retains its existing pane/workspace/tab arrays
while their token matches; Rust avoids serializing those arrays and the cached
header title for terminal-only updates. No additional cache or runtime is added.
Fresh views and zero/stale navigation tokens receive complete navigation, even
when the full-frame token is current. Pane/focus, label and status changes retain
their normal invalidation, and pending clipboard writes, notices and bells are
still delivered once. Native styled-frame preparation is unchanged.

A phone profile identified navigation JSON decoding as a remaining cost. A
temporary token probe observed unchanged navigation on all 148 changed-frame
transitions in its sample. The probe was removed before building the candidate.

The production-source A/B/A/B comparison retains the same foreground host/pane,
hidden sidebar, theme, type settings, secure-window policy and 852 dp inner-panel
width. Each build has four approximately 15-second windows. Every counted window
kept exactly one native session manager and the terminal foreground. CPU is
weighted by elapsed time from process utime+stime deltas at the measured 100 Hz
device clock; 100% means one occupied CPU core.

| Build | APK SHA-256, first 16 | Weighted process CPU |
|---|---|---:|
| Full navigation on changed terminal frames | `b13b4609db6da735` | 50.031% |
| Navigation-token reuse | `c5aea56ddb99b1b3` | 46.276% |

This is **7.5% less process CPU** for this workload. Input and rendering cadence
were not reduced. This does not establish a battery-life, FPS or latency gain.

Physical checks exercised the cached Panes sheet, a pane switch and return,
colored terminal output, and pan/keyboard at inner-panel and simulated 443 dp
widths. The sidebar retained four sessions when visible and one when hidden;
backgrounding released all sessions. A fresh launch restored the original
host/pane and complete navigation. Saved display/sidebar preferences, display
size/density, rotation, timeout and wake policy match their recorded originals.
The capture override was removed and the restarted visible terminal is `SECURE`.

Verification passes: 34 default, 62 remote-feature and 68 host `android-ui` Rust
tests; 10 JVM tests; debug/release Android lint; and 19 native-verifier tests.
New regressions cover missing/stale navigation recovery without another frame,
fresh-view bootstrap, status-only navigation changes, native header/body
retention and once-only effects while navigation is reused. The actual ARM64
library and APK pass source freshness, API 29, 16 KB alignment, required JNI
export and v2/v3 signature checks. The native library SHA-256 starts
`c6677473244b34ec`. Raw profiles, samples and captures remain private; exact
staged device files are removed. Physical folding, Windows execution and full
desktop-workspace or real-daemon input/lifecycle conformance are not renewed.

## On-demand native Copy verification

Active native terminal polls now keep both the styled and plain terminal bodies
in Rust. Java receives metadata only. Copy pane text reads the bounded held
snapshot on the existing worker when requested, without a daemon round trip.
Capture rejects stale generations without activating input or consuming queued
commands. Clipboard completion must still own the connection and pane in a
resumed activity. The HTTPS snapshot and immediate Copy paths are unchanged.

The final production-source A/B/A/B comparison uses two approximately 15-second
windows per leg, four per APK. Both builds keep the same host/pane, hidden
sidebar, original theme/type settings, 852 dp inner-panel width and `SECURE`
capture policy. Before and after every interval the terminal is top-resumed,
its surface is visible and unobscured, and exactly one native session manager is
present. CPU is weighted by elapsed time from process utime+stime deltas at the
measured 100 Hz clock; 100% means one occupied CPU core.

| Build | APK SHA-256, first 16 | Weighted process CPU |
|---|---|---:|
| Plain text returned on every changed terminal poll | `c5aea56ddb99b1b3` | 45.842% |
| Native snapshot text read only for Copy | `98e12ec5159a00e3` | 43.497% |

This is **5.1% less process CPU** for this workload. Input, rendering and poll
cadence are unchanged; no battery-life, FPS or latency gain is established.
Row-layout caches and direct-paint trials were removed rather than retaining
renderer complexity without a worthwhile measured gain.

Temporary signed instrumentation ran the production Java Copy handler and real
JNI entry point with a controlled Unicode/blank-line fixture. It verified exact
clipboard text, stale-generation rejection, cancellation across a pane change,
read-only access to an inactive held pane and isolation of a new generation.
The original clipboard was restored and the probe package uninstalled. No
terminal input was sent. Physical checks also exercised colored output, theme
and type changes, pan/keyboard at inner-panel and simulated 443 dp widths, and a
pane switch and return. The sidebar held four managers when visible, zero while
paused and four after a fresh launch. Original host/pane, display/sidebar
preferences, size/density, rotation, timeout and wake policy were restored. The
fresh visible terminal window is `SECURE`.

Verification passes: 35 default, 63 remote-feature and 69 host `android-ui` Rust
tests; `android-ui` all-target Clippy with `-D warnings`; 10 JVM tests;
debug/release Android lint; and 19 native-verifier tests. The actual ARM64
library and APK pass source freshness, API 29, 16 KB alignment, required JNI
exports including `nativeCopyText`, and v2/v3 signature checks. The native
library SHA-256 starts `60061b67f3b35a4b`. Raw evidence remains private and exact
staged device files were removed. Physical folding, browser-link launching,
live HTTPS Copy and full desktop/Windows gates were not renewed.

## Fold-aware chrome and cell-grid verification

This pass follows Android's window-size-class, navigation-drawer, 48 dp target
and edge-to-edge guidance, and Apple's iPhone Duo guidance: layouts resize
rather than switch, controls stay in the same order in both poses, toolbar
items carry a symbol and a title, and extra actions go to the system overflow
menu.

- Rows are painted on a forced cell grid, and wide-character continuation cells
  are kept as blank cells, so fallback glyphs no longer drift. The auto-fit
  therefore uses Noto Mono's true advance: the inner panel picks about
  17.25 px instead of about 15.2 px.
- Pan stops at the painted overflow. At 443 dp, six right taps stopped with the
  pane's last column at the screen edge and one left tap moved back immediately.
- Chrome follows the palette. Latte gives a light status bar with dark icons and
  light DeviceDefault sheets; switching to Default at runtime recoloured the
  bars, header, rail, toolbar, keycaps and sheets, and switching back restored
  Latte.
- Below 600 dp (`wm size 1080x2424`, reported as 443×994 dp) the toolbar keeps
  Hosts, Panes, Keyboard and Commands and moves View, Session and Close into
  **More**. The rail becomes a 320 dp drawer over a scrim; Back and a scrim tap
  each closed it, mirror sessions went 1 → 4 → 1, and the wide-panel preference
  was untouched. Returning to 852 dp rebuilt the wide toolbar and, after a fix
  found here, re-sized the rail from the new window width (240 dp, titles
  wrapping in full) instead of the stale compact width.
- The live host and focused pane report `selected=true` to UI Automator. The
  View, Panes, Session and Commands sheets were opened and dismissed with their
  own buttons only.

CPU uses the same process and main-thread utime+stime method as above
(100 Hz clock, weighted by elapsed time, 100% = one core), in alternating
reinstalls of the previous APK (`98e12ec5159a00e3`) and the candidate
(`250a63e6ebfe4811`; the final APK differs only in activity theme resources).

| Workload | Previous | Candidate |
|---|---:|---:|
| Rail visible, four sessions, 2 × 20 s each | 25.06% | 25.98% |
| Rail hidden, one quiet pane, 3 × 20 s each | 10.98% | 10.66% |
| Rail hidden, one pan step per tap at 18 sp, 2 × 18.5 s each | 67.39% | 61.75% |

The paint-heavy row is the render-path comparison: every tap repainted the
grid in both builds (a capture diff changed 4.46% of the terminal area after
one step and 0% after stepping back), and the candidate used 8.4% less process
CPU. With the rail visible, main-thread profiles put 81–87% of the UI thread in
`refreshSidebar` in both builds. That refresh is triggered by daemon title
changes and mirror revisions, and its code differs only in change-guarded
styling. The candidate's window spent more time in mirror-triggered refreshes
(534 against 222 samples), so the 0.9-point difference tracks agent activity
during each window and cannot be attributed to the new chrome from this
evidence.

Verification passes: 37 default, 66 remote-feature and 73 host `android-ui` Rust
tests; host Clippy with `-D warnings`; 13 JVM tests; `assembleDebug`,
`assembleRelease` and `compileVmTests`; debug and release Android lint with “No
issues found”; and 19 native-verifier tests, now requiring `nativeThemeColors`.
The ARM64 library (`ce626a40cc1a91a9`) and the APK pass source freshness, API 29,
16 KB alignment and v2/v3 signature checks. The capture override was cleared and
the relaunched terminal window is `SECURE`. Display and startup preferences,
display size, density, rotation, timeout and wake policy match their recorded
values, and exact staged device files were removed. A physical fold or unfold,
tabletop and book postures, and the separate `android/` companion app (whose
theme is unchanged) were not exercised.

## Tabletop, letterbox and host-list verification

A second pass on the same guidance: Android's fold-aware layouts (content above
a horizontal fold, controls below it) and the iPhone Duo rule to fill letterbox
padding rather than leave it empty, plus large-screen list widths for the host
list.

- **Letterbox.** The live agent pane paints its edges in two close dark shades,
  tool boxes and its base, so an exact-colour vote found no majority and kept
  the light Latte frame. The decision now counts every edge colour visibly
  unlike the palette, and the commonest of them (here the tool-box shade) fills
  from header to toolbar on both the 852 dp panel and the 443 dp width. The first
  device build also showed light slivers down the right edge. Those were Herdr's
  default-background scrollbar column, which the projection now marks as frame
  so it takes the fill as well.
- **Tabletop**, simulated with `cmd device_state state 1` and a landscape
  `user_rotation`. The terminal surface measured `[585,136][2152,1038]`, ending
  exactly at the fold at y=1038, with 11 keycaps on the deck from y=1058 and the
  toolbar's strip left with only the pan arrows. Opening the keyboard moved the
  surface to `[585,136][2152,848]` with the keys back in the strip above the
  IME; Back restored the split. In portrait (book posture) the surface kept the
  full `[585,136][2076,1976]`. The override and rotation were then reset, and
  the flat layout returned.
- **Host list.** The cards measure `[199,…][1876,…]` inside a centred 720 dp
  column. Four hosts fit on the first screen; each ⋮ menu listed Edit profile,
  Edit extra keys, Forget SSH trust and Delete profile, and Back closed it
  without choosing anything. Returning from the list to a terminal paused by it
  still lands on the list. That is existing behaviour: pausing closes the
  session, and nothing is reopened automatically.

Verification passes: 39 default, 69 remote-feature and 77 host `android-ui` Rust
tests, including letterbox thresholds and hysteresis, frame cells not voting,
per-cell backgrounds over a fill and frame marking in the projection; `cargo
fmt --check`; host Clippy with `-D warnings`; 15 JVM tests, including fold
classification and the split's room check; `assembleDebug`, `assembleRelease`
and `compileVmTests`; debug and release Android lint with “No issues found”.
The ARM64 library and APK pass source freshness, API 29, 16 KB alignment and
v2/v3 signature checks. Simulated postures are not a physical fold: hinge-angle
feel, real fold transitions and other devices' window extensions are
unverified. No terminal keys were sent; the deck's keys use the same handlers
as the strip's.

## Follow system, sheet cleanup and render-cost check

Both platforms' guidance asks apps to follow the system light/dark appearance.
**View > Theme > Follow system** stores theme 4 and was applied from the sheet.
`cmd uimode night yes` then repainted the open terminal in Mocha, with dark
bars, light status icons, rail, toolbar and header, and `night no` returned it
to Latte with its letterbox fill; neither recreated the activity. The Panes
sheet now lists tab rows only for workspaces with more than one tab: on the live
host only `mc` kept its two tab rows, and the other workspaces show their panes
directly. Quick command titles and captions share one left edge, with pins
after the title.

The CPU check compares the first pass's final APK with this build, which adds
the letterbox decision and frame marking. Both run the Latte theme with the
rail hidden and one session on an active agent pane. Windows alternate between
reinstalls; the method is the same as above.

| Workload | First-pass APK | This build |
|---|---:|---:|
| No interaction, 2 × 20 s each | 45.46% (45.43–45.49) | 45.42% (45.17–45.67) |
| A pan step per tap at 18 sp, 4 and 3 × 18.5 s | 92.80% (90.32–95.38) | 94.98% (94.06–95.78) |

The idle path is unchanged. The paint-heavy difference (+2.3%) sits inside the
older build's own 5-point spread, and its paired windows differ by -0.3, +5.5
and +0.5 points. A small cost is neither established nor ruled out. The last
window was lost when the host disk filled; no further windows were run.

Verification passes: 16 JVM tests, including the Follow system mapping;
`assembleDebug`, `assembleRelease` and `compileVmTests`; debug and release
Android lint with “No issues found”. The Rust suites are unchanged from the
receipt above. Dark mode, theme, rail and size were restored, and the relaunched
terminal window is `SECURE`.

## Raw SSH shell verification

**Open shell** on the test host's ⋮ menu, development-signed ARM64 build, inner
panel, Latte theme. Every check below ran in a new disposable login shell, after
confirming the shell activity was in the foreground; no Herdr pane received input.

| Check | Result |
|---|---|
| Open | Login banner and the zsh/starship prompt drawn by the vendored Rio terminal; no header, rail, Panes or pan buttons |
| Keyboard typing | `printf ok` typed on the on-screen keyboard, syntax-highlighted by zsh as it echoed; ↵ printed `ok` |
| PTY size | `stty size` = `19 109` with the keyboard open and `47 109` with it hidden; each keyboard change redrew the prompt (SIGWINCH) |
| Toolbar keys | ↑ opened the host's Atuin history search, whose Enter placed the command and a second Enter ran it; Ctrl-D ended the shell |
| Scrollback | `seq 60`, then a 1000 px drag down moved the view from rows 37–60 to 12–39; Enter returned it to live output |
| Copy | **Session > Copy screen text** copied the 28 displayed rows (`Copied 83 characters`) |
| Exit and Reconnect | Ctrl-D left the last screen with `Shell exited` and the banner "The shell exited"; **Reconnect** opened a new login shell |
| Cover width | `wm size 1080x2152`: compact toolbar, `stty size` = `60 67` at the 11 px floor |
| Security | With `herdr_probe` cleared, the shell's terminal window is `SECURE` |

Two defects were found and fixed on the way, both invisible to the host tests:

- **On-screen keyboard commits were dropped, in every mode.** Opening the
  keyboard creates two InputConnections at the same epoch (focus, then
  `restartInput`) and closes the first only afterwards. That close reported a
  dismissal, whose hide reset the proxy, so every later commit on the live
  connection was refused. A logged trace showed `commit cs=3 s=4 ce=1 e=3`.
  Only the newest connection's close now counts as a dismissal. The VM suite
  gained a check that replays this order; it has been compiled, not yet run.
- **A shell attached before the GPUI window existed stayed undrawn.** On a cold
  start the attach and its status could land before the view read its first
  state, and nothing woke it again. The view now attaches the shell named in its
  initial state.

The prompt's leading `❯` draws as a missing-glyph box: neither the bundled Noto
Mono nor the fallback the shell's terminal reached on this phone has it.

## Full-screen verification

Both terminals now use the panel's height. Herdr fixes a pane's rows, so a
short pane spreads its rows over the body, at most to twice their natural pitch;
the bars under the body have one fixed height, so the body's height is exact
before layout. The raw shell, whose grid follows the surface, drops its header,
sizes cells by the font's own line height (Noto Mono: 1900 + 500 of 2048 units,
1.17 em, where Rio added 1.35) and uses the 13 px default on the inner panel
instead of growing the type to fit 80 columns.

| Check | Before | After |
|---|---:|---:|
| Herdr pane (80 columns, 24 rows), empty height above / below the text | 127 / 118 px | 23 / 10 px |
| Herdr pane, text block height | 1 490 px | 1 702 px |
| Raw shell, keyboard hidden (`stty size`) | 28 × 82 | 47 × 109 |
| Raw shell, keyboard open | 10 × 82 | 19 × 109 |
| Raw shell, cover width | (not measured) | 60 × 67 |

Spreading leaves the type size and columns alone, and cell backgrounds fill
each taller row, so an agent's panels stay solid. Vertical box-drawing lines
showed gaps between rows until those characters were drawn as shapes (see
Drawn box characters), and on the cover the 24 rows need nearly the full 2×.
Measured on the inner panel and at `wm size 1080x2152` with the same live agent
pane, viewed only; the text extent is the first and last row with bright pixels
between the header and the toolbar.

## Drawn box characters

With rows spread, a border drawn from a font's `│` stopped short of the next
row, so every pane frame and scrollbar showed as dashes. Box-drawing
characters (U+2500–U+257F, except the diagonals ╱╲╳) and block elements
(U+2580–U+259F) are now drawn by the renderer: their glyphs are hidden, and
each cell gets lines, arcs, blocks or shades spanning its full width and
height in whole device pixels. A light stroke is an eighth of the cell wide,
a heavy one twice that, and a double line is two light strokes a stroke apart.
A cell's column comes from where the shaper placed its hidden glyph, so wide
glyphs and combining marks earlier in the row do not shift it.

Checked on Android 15 emulators (1080 × 1920 phone and 2560 × 1600 tablet)
with the release bundle and a disposable Herdr 0.9.3 session: pane frames, the
scrollbar's track and thumb, and a card of light, heavy, double, rounded,
dashed, half, mixed, block, shade and quadrant characters all join across rows.

## Host theme verification

Same live omp pane on the test host, inner panel, viewed only,
theme **Follow system** with the phone in light mode (Latte):

| Build and system appearance | What the pane showed |
|---|---|
| Previous build (no theme reported), light | omp's dark theme inside the light chrome, with the letterbox filled dark |
| This build, light | omp repainted its light theme without restarting: light tool box and status line, Latte-legible syntax colours, no letterbox fill |
| This build, `cmd uimode night yes` | Mocha chrome, and omp repainted its dark theme live |
| This build, `cmd uimode night no` | Latte again, and omp back to its light theme |

A block omp printed before the first change kept its dark background through
both switches: a program repaints only what it redraws, and a terminal never
recolours cells already drawn. Night mode, the capture switch and the open pane
were restored afterwards.

## Host list redesign verification

The host list (`RemoteHostsActivity`) was redrawn in the terminal's theme: a
titled header with **Add host**, one card per host, a grouped **This device**
card with a switch, a status line that is a spinner, a note or an error banner,
and back-arrow steps for the key check, sign-in and pane choice. The phone was
locked and in use during development, so the checks below ran on an emulator
with the
`pixel_9_pro_fold` device profile (Android 15, inner 852 x 883 dp, cover
443 x 994 dp through `adb emu fold`), with three saved hosts, one of them pinned
through the synthetic SSH fixture. The committed build was then installed on
the phone, captured and timed there (end of this section).

| Check | Result |
|---|---|
| Themes | Default, Nord, Latte and Follow system (Mocha in dark mode) all paint the page, cards, bars and switch from `GpuiChrome`; Latte has dark status-bar icons |
| Text contrast on the page background | Every text role is at least 4.5:1 in every palette; the lowest are Latte's secondary text (4.91) and accent (4.89). Only the primary text colour is used on tonal fills, where the others drop below 4.5 |
| Cover width | **Connect** moves under each host's details at full width; the device rows put their buttons under the text |
| Fingerprint | Shown in full in the text, middle-ellipsized on screen (`SHA256:i67ECjoJGqm…1PTWjt4` on the cover) |
| Steps | An unreachable host shows a spinner, then the error in a banner; the arrow and system Back both return to the list without leaving the app |
| Sign-in | The status reads "Host key verified. Sign in to continue." while the dialog waits |
| Dialogs | Light under Latte, dark under Nord; `screencap` still shows them black, so `FLAG_SECURE` is intact |
| Empty state and saved key | Both **Add host** buttons open the editor; a non-key is refused in the error banner; a saved key shows **Replace** and **Remove** |

Cold start to first frame (`am start -W`, debuggable build, SwiftShader, host
load around 14, baseline and new build interleaved, first launch of each round
dropped):

| Round | Baseline median | New median |
|---|---:|---:|
| 1 | 585 ms | 806 ms |
| 2 | 595 ms | 1176 ms (load spike) |

`dumpsys gfxinfo framestats` puts the difference in the first frame's render
phase (sync to swap: 21–36 ms before, 178–202 ms after), not in measure and
layout (122–183 ms before, 97–134 ms after). That phase compiles a GPU program
the first time each kind of primitive is drawn: stroked rounded cards, circles,
more text styles. SwiftShader compiles on the CPU, and neither the emulator nor
the phone kept a persistent shader cache for this debuggable app, so every
cold start pays it there. On the CPU side the
renderer library loads in 9–35 ms, and building the platform button and switch
styles only to replace them cost more than the rest of the list (the switch
alone 33–45 ms), so the list now constructs those widgets without a default
style. A launch that reopens the last session draws no host cards at all.

On the Pixel itself the cost is small. Same method, launched with
`--ez skip_reopen true` so no session opened, Nord theme, the user's four saved
hosts and saved key, the previous build measured first and then the
installed `b620d68` build:

| Pixel 9 Pro Fold, inner panel | Previous list | Redesign |
|---|---:|---:|
| Cold start to first frame, median of 6 | 173 ms | 197 ms |
| First frame: measure and layout | 10–11 ms | 11–13 ms |
| First frame: render (sync to swap) | 9–12 ms | 22–24 ms |

The capture on the phone matched the emulator, and every control measured at
least 48 dp tall.

## Measurement traps (each cost real time)

- **`FLAG_SECURE` masquerades as a black screen.** `screencap` returns black for
  a secure window. Debug builds lift the flag only when
  `settings put global herdr_probe 1`; clear it and restart the activity afterwards
  so the existing window regains `FLAG_SECURE`. Dialogs honour the
  same switch — without it, no sheet can be reviewed at all.
  The host list's dialogs stay secure even then; on an emulator,
  `adb emu screenrecord screenshot <file>` records the emulator's own display
  output, secure layers included.
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
- **The debuggable APK runs Java in ART's switch interpreter.** simpleperf shows
  `ExecuteSwitchImplCpp` under every Java frame, so Java costs are inflated
  relative to a release build. Compare builds with each other, never against a
  release figure.
- **Rail-visible CPU follows the agents, not only the build.** Working agents
  change pane titles several times a second, and each change refreshes the rail.
  Judge render-path changes with the rail hidden, using a workload that repaints
  on every step.
- **Postures can be simulated without folding.** `cmd device_state state 1`
  makes the window extensions report a half-open fold, and
  `settings put system user_rotation 1`, with `accelerometer_rotation 0`, turns
  it across the window. Undo both with `cmd device_state state reset` and the
  original rotation settings.
- **`adb shell input text` cannot type into the terminal.** It injects key events
  from a virtual device, which the activity drops by design; only physical keys
  and keyboard commits are input. Injecting them while the keyboard is open also
  restarts it against a `TYPE_NULL` editor (raw keys, cursor `-1,-1`), so later
  taps type nothing either. Hide and reopen the keyboard, then tap its keys.
- **↑ on a host with Atuin opens its search, and its Enter edits.** The first
  Enter after ↑ puts the chosen command on the prompt instead of running it,
  which looks exactly like a lost key. A second Enter runs it; Ctrl-C closes the
  search.

## Open items

- Both drag axes work and are measured: a horizontal swipe
  (`1200 900 → 700 900`) pans the pane 448 px with 0 px of vertical drift, and a
  vertical swipe (`900 800 → 900 1500`) scrolls it 370 px with no pan. The
  horizontal delta is handled by the body's `on_scroll_wheel`; the vertical one
  goes to the list exactly as before. A zero result means the pane was already at
  that end of its scrollback, not that the gesture failed — check the end before
  concluding a regression.
- Multi-touch (two-finger pan or pinch zoom) is still unverified: `adb shell
  input` cannot inject multiple pointers.
- Frame cadence is still unmeasured, and `dumpsys SurfaceFlinger --latency` is not
  the tool: on the GPUI `SurfaceView (BLAST)` layer it returns only the refresh
  period (8 333 333 ns, i.e. the panel is at **120 Hz**) and no frame timestamps,
  idle or while scrolling. A counter inside the view's `render` does not work
  either: scrolling a `uniform_list` repaints without calling it, so the hook
  never fires - that experiment was written, measured at zero lines, and
  reverted. Cadence needs Perfetto (`gfx`/`sched`) or an instrumented GPUI
  presenter; the display itself is not the constraint at 120 Hz.
- **Narrow-panel typography is settled by measurement, not preference.** Earlier
  494 dp checks found a 70–90 character pane cannot fit at a readable size: the fit floors at
  11 px and clips the tail, and dropping the floor to 10 px still clipped the
  widest lines because fitting them would need about 8 px. Panning is the answer;
  do not lower the floor again without re-measuring `widest_line_chars`.
- Narrow-layout checks resize the display (`wm size 1080x2152`, then restore
  its previous size). At the current 390 dpi this is 443 dp wide, not the older
  494 dp assumption; record `am get-config` rather than assuming a dp width.
- Predictive back is not adopted yet. The app targets SDK 35, where it is
  opt-in (`enableOnBackInvokedCallback`). Opting in stops `KEYCODE_BACK` reaching
  `onKeyPreIme`, which `GpuiInputActivity` uses to tell GPUI the keyboard was
  dismissed. The migration has to move that signal (for example to IME inset
  visibility) and the compact drawer's Back to an `OnBackInvokedCallback`. It
  also needs an IME composition check typed into a disposable pane, never a live
  agent.

