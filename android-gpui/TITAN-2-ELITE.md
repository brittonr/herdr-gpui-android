# Titan 2 Elite compact preview — 0.3.0

This build targets the **Unihertz Titan 2 Elite**, while retaining selectable
standard layout for other Android phones. It uses real GPUI/wgpu rendering and
the same authenticated companion transport as the earlier GPUI preview.

## Device facts and boundaries

Checked on 2026-10-07 against the [official product page](https://www.unihertz.com/products/titan-2-elite)
and [official user manual](https://www.unihertz.com/pages/titan-2-elite-user-manual):

- Android 16; 4.03-inch AMOLED, 1080 × 1200 pixels, 120 Hz
- 9:10 screen aspect ratio, calculated from the pixel dimensions
- Manufacturer lists 401 PPI. This is **not** proof of Android's logical density
- The standard Elite uses Dimensity 7400, 12 GB RAM and 256 GB storage
- Physical QWERTY, Alt, Sym, Fn, Shift, Enter, Backspace and navigation keys
- No dedicated Ctrl, Esc, Tab or arrow cluster is shown in the official layout
- Fn defaults to the vendor Magic shortcut function; Sym/Fn can be remapped,
  including to Ctrl. No undocumented keycode/scancode mapping is assumed
- Keyboard-surface cursor/scroll gestures are firmware/IME features. Their
  delivery to this app still needs testing on the actual phone

The Titan 2 is a different, square-screen device. No manufacturer/model-name
autodetection or system keyboard-setting change is performed by this app.

## Changes

- **Automatic compact layout** when the usable viewport is short or narrow,
  plus explicit Compact / Titan 2 Elite and Standard choices
- A shorter, bounded title/status header, 48 dp view controls, adjustable
  11–18 terminal text size, and bounded system text scaling
- A native **View** button opens local layout, text and physical-Alt choices
  and shows the full current title/status when the compact header truncates it
- **Ctrl** arms a one-shot modifier for the next physical letter. Tap again to
  cancel. The latch clears on other input, opening View, or leaving the activity
- Existing user-editable Esc, Tab, arrow, Ctrl and macro keys remain in the
  horizontally scrollable extra-key strip. Edit them in the saved host settings
- **Device symbols** is the default Alt behavior, using Android's actual
  KeyCharacterMap Unicode. Select Terminal Alt shortcuts for a conventional
  external keyboard. Layout or soft-keyboard visibility never changes this rule
- Unsupported Alt+Enter/Backspace/arrows, mixed modifiers, Fn/Sym/Meta chords
  are rejected rather than silently converted into another terminal command
- The on-screen keyboard stays hidden initially; tap GPUI's **Keyboard**
  control when needed. GPUI focus/restore callbacks cannot open it without an
  explicit, generation-checked Keyboard request. Opening View cancels an unfinished on-screen draft and
  invalidates the old InputConnection, so it cannot commit into the terminal
- The IME is asked to disable suggestions and personalized learning. These
  are Android hints, not a guarantee about third-party keyboard behavior
- Identical snapshots are skipped before JNI conversion; unchanged display and
  state updates do not force GPUI repaint. Terminal rows remain virtualized

The app uses actual runtime dimensions and insets, including IME/cutouts. It
does not claim a fixed 432 × 480 dp viewport on the physical phone. The display's
120 Hz specification is not a measured application frame rate.

## Install and first use

This preview is **Herdr GPUI Compact**, application ID
`dev.herdr.android.gpui.compact`, version `0.3.0-gpui-compact` (code 3).
The previous cloud build workspace expired and its private development signing
key was not retained. Therefore this build deliberately installs **alongside**
the earlier `dev.herdr.android.gpui` app instead of asking you to uninstall it.
The earlier app and its saved hosts remain intact; enter hosts in the new app.
The new private development key is not included in the source archive.

1. Install the ARM64 APK on Android 10+ with a GPUI-compatible Vulkan driver
2. Run the companion from this source behind valid trusted HTTPS, following
   [host prerequisites](../android/README.md#host-prerequisites). The raw-text
   and expanded-key routes in this source are required; an older PR248 bridge
   alone is not sufficient
3. Add a host URL and token inside the app, then open a terminal and tap GPUI
4. Automatic layout should choose Compact on a short display. Use View to set
   Compact / Titan 2 Elite explicitly or change text size and Alt behavior
5. For native Ctrl chords, optionally map Sym/Fn to Ctrl in the phone's own
   keyboard settings as described by Unihertz; otherwise use the one-shot Ctrl
   control and editable extra keys. The app does not change those settings
6. Verify on a disposable terminal before entering consequential commands

Tokens remain encrypted with AndroidKeyStore, HTTPS validation stays enabled,
redirects remain rejected, and terminal windows remain FLAG_SECURE. No new
permissions, network tunnels or user-machine connections were added.

## Test scope

Current validation is recorded in `COMPACT-VALIDATION.md`. An Android 16 x86_64
VM at 1080 × 1200 with representative logical density 400 exercises the compact
geometry. This is not the physical Titan keyboard/firmware, ARM64 GPU or actual
host network. The VM's synthetic fixture does not establish real TLS or Herdr
interoperability. Prior version 0.2.1 evidence remains historical.

Rendering still uses bounded text snapshots rather than full VT-cell colors,
cursor fidelity, graphics, resize or scrollback. Emoji coverage remains limited.
