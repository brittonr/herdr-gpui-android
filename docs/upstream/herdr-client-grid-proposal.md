# Proposal: let a client request the pane grid it can display

Status: draft, from device evidence gathered with this client
(herdr-gpui-android) against a running Herdr daemon.

## Problem

A Herdr pane renders at the daemon's own row and column count. On a foldable
the client's surface is much larger than that grid: the Pixel 9 Pro Fold inner
panel is 2076×2152 px (798×828 dp) and fits roughly 45 rows of terminal text at
a comfortable size, while panes arrive with 24 rows. The client can only
letterbox the pane, spread its rows (what it now does, up to twice their pitch)
or lie about its geometry, so a large screen shows few, widely spaced lines and
the terminal never looks native to the device.

Columns have the same shape of problem in reverse: a pane that wraps at 90
characters is wider than a 494 dp cover panel at readable type, so the client
must either shrink text below legibility or scroll horizontally.

## What the client already sends, and what happens

| Experiment | Message | Observed effect |
|---|---|---|
| Report a real grid at connect | `ClientShellHello { cell_width_px, cell_height_px, surface_size }` with the measured 259×125 surface | Pane keeps the daemon's 24 rows |
| Report it again after the boot id exists | `ClientShellResize { cell_width_px, cell_height_px, surface_size }` (three attempts at 3 s, 8 s, 20 s) | Accepted, no change to pane rows |
| Try to resize the pane itself | `pane.resize { pane_id, direction }` | Nudges a split ratio; cannot set rows or columns |

The client's rendered output was checked by cross-correlating screen captures, so
"no change" means no change in paint, not merely a missing log line.

## Why this matters beyond one device

Any client whose window changes size — a foldable, a desktop window, a tablet in
split screen — has this problem. The daemon currently decides one grid for a
pane regardless of who is attached, which is also why two clients of different
sizes cannot both show a pane at native size.

## Options

1. **Honour the client's surface when it creates the pane** (smallest change).
   Applying `surface_size` from `ClientShellHello` to panes the client creates
   would fix single-client sessions. Two clients of different sizes still
   conflict, and the last attach would win.

2. **Accept `rows`/`cols` on `pane.resize`.** Lets a client ask for a grid
   explicitly and works for existing panes, not just new ones. Same
   last-writer-wins caveat, but the client can retry after a reconnect, which is
   what a phone does constantly.

3. **Per-client viewport rendering** (largest change, best semantics). The
   daemon keeps its own grid for the pane and renders each attached client at
   the client's viewport, the way `tmux attach -r`/`-c` behaves. Clients no
   longer fight over the pane, and a phone plus a desktop can watch one pane at
   their own sizes. Costs a per-client render path and a defined reflow rule for
   the pty.

Recommendation: **2 first, 3 as the destination.** Option 2 is additive, testable
against the current client in an afternoon, and removes the dead band on this
fold; option 3 is the answer for multi-client sessions and for the cover panel's
column problem, but it needs a reflow decision that should be made deliberately.

## Compatibility notes

- `ClientSurfaceSize` already exists on the wire; option 1 uses it as-is.
- Option 2 adds fields to an existing request; additive fields keep the
  generation-1 contract intact, reordering declarations does not.
- Wrapping and scrollback stay daemon-owned in every option: the client must not
  re-wrap text it receives, or two clients will disagree about line identity.

## Test plan for option 2

1. Attach a client with a reported 45-row surface, request `pane.resize` with
   `rows: 45, cols: 120`, and assert the next snapshot's `viewport_rows` is 45.
2. Verify scrollback arithmetic at the new grid: `offset_from_bottom`,
   `max_offset_from_bottom` and the rendered row count agree.
3. Attach a second client at 24 rows and confirm the defined behaviour
   (last writer wins, or per-client viewports once option 3 lands).
4. Re-run on the fold: the pane should fill the panel at its natural row pitch,
   with no spreading or letterboxing, and no clipping at the fit width.
