//! Platform-independent boundary validation, latest-state mailbox, and input FIFO.
use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::{collections::VecDeque, sync::Arc};

#[cfg(feature = "android-ui")]
use std::sync::{Mutex, MutexGuard, OnceLock};

pub const MAX_COMMANDS: usize = 64;
pub const MAX_COMMAND_JSON: usize = 65_536 * 6 + 128;
pub const MAX_TEXT_BYTES: usize = 65_536;
pub const MAX_SUBMIT_BYTES: usize = 4_096;
pub const MAX_SNAPSHOT_BYTES: usize = 524_288;
pub const MAX_SHORTCUT_JSON: usize = 2_097_152;
pub const MAX_LINES: usize = 4_000;
pub const MAX_LINE_CHARS: usize = 4_096;
/// Styled rows carry at most this many spans; extra spans merge into the last.
pub const MAX_RUNS_PER_LINE: usize = 256;
/// Cell attribute bits shared with the desktop painter.
pub const BOLD: u16 = 1;
pub const DIM: u16 = 1 << 1;
pub const ITALIC: u16 = 1 << 2;
pub const UNDERLINE: u16 = 1 << 3;
pub const REVERSED: u16 = 1 << 6;
pub const HIDDEN: u16 = 1 << 7;
pub const STRIKETHROUGH: u16 = 1 << 8;
/// Renderer-only bit: paint this cell with the palette accent (focused pane border).
pub const FOCUS_RING: u16 = 1 << 15;

/// A terminal palette: the 16 ANSI colors plus the default pair and chrome.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Palette {
    pub name: &'static str,
    pub ansi: [u32; 16],
    pub foreground: u32,
    pub background: u32,
    /// Header/footer surface and its border.
    pub chrome: u32,
    pub border: u32,
    pub muted: u32,
    pub accent: u32,
}

pub const PALETTES: [Palette; 4] = [
    Palette {
        name: "Default",
        ansi: [
            0x1c1f26, 0xcc6b6b, 0x8fc98a, 0xd8b06a, 0x7aa2d6, 0xb08ad6, 0x6fc0c6, 0xc9d1dc,
            0x566274, 0xe08a8a, 0xa6dc9f, 0xeed092, 0x94b8e8, 0xc9a6e8, 0x8ad6dc, 0xf2f5f9,
        ],
        foreground: 0xdce5ef,
        background: 0x10151d,
        chrome: 0x1e222a,
        border: 0x2b3747,
        muted: 0x9fb0c6,
        accent: 0x76d3b2,
    },
    Palette {
        name: "Nord",
        ansi: [
            0x3b4252, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x88c0d0, 0xe5e9f0,
            0x4c566a, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x8fbcbb, 0xeceff4,
        ],
        foreground: 0xd8dee9,
        background: 0x2e3440,
        chrome: 0x3b4252,
        border: 0x4c566a,
        muted: 0xaebacd,
        accent: 0x8fbcbb,
    },
    Palette {
        name: "Mocha",
        ansi: [
            0x45475a, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xcba6f7, 0x94e2d5, 0xbac2de,
            0x585b70, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xcba6f7, 0x94e2d5, 0xa6adc8,
        ],
        foreground: 0xcdd6f4,
        background: 0x1e1e2e,
        chrome: 0x181825,
        border: 0x313244,
        muted: 0xa6adc8,
        accent: 0xa6e3a1,
    },
    Palette {
        name: "Latte",
        ansi: [
            0x5c5f77, 0xd20f39, 0x40a02b, 0xdf8e1d, 0x1e66f5, 0x8839ef, 0x179299, 0xacb0be,
            0x6c6f85, 0xd20f39, 0x40a02b, 0xdf8e1d, 0x1e66f5, 0x8839ef, 0x179299, 0xbcc0cc,
        ],
        foreground: 0x4c4f69,
        background: 0xeff1f5,
        chrome: 0xe6e9ef,
        border: 0xccd0da,
        muted: 0x6c6f85,
        accent: 0x1e66f5,
    },
];

static PALETTE_INDEX: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(0);

pub fn palette() -> Palette {
    PALETTES[PALETTE_INDEX.load(std::sync::atomic::Ordering::Relaxed) as usize
        % PALETTES.len()]
}

/// Selects a built-in palette. Returns whether the choice changed.
/// Applies a palette and repaints the current window.
#[cfg(feature = "android-ui")]
pub fn apply_palette(index: u8) -> bool {
    if !set_palette(index) {
        return false;
    }
    let mut mailbox = mailbox();
    mailbox.revision = mailbox.revision.wrapping_add(1);
    #[cfg(feature = "android-ui")]
    signal_change();
    true
}

pub fn set_palette(index: u8) -> bool {
    if usize::from(index) >= PALETTES.len() {
        return false;
    }
    PALETTE_INDEX.swap(index, std::sync::atomic::Ordering::Relaxed) != index
}

/// Resolves a wire color (named 1..=16, indexed 0x01xxxxxx, RGB 0x02RRGGBB).
pub fn resolve_color(value: u32, default: u32, palette: &Palette) -> u32 {
    match value >> 24 {
        0 => match value & 0xff {
            1..=16 => palette.ansi[((value & 0xff) - 1) as usize],
            _ => default,
        },
        1 => {
            let index = (value & 0xff) as usize;
            match index {
                0..=15 => palette.ansi[index],
                16..=231 => {
                    let cube = index - 16;
                    let step = |value: usize| {
                        let level = (value % 6) as u32;
                        if level == 0 { 0 } else { 55 + level * 40 }
                    };
                    (step(cube / 36) << 16) | (step(cube / 6 % 6) << 8) | step(cube % 6)
                }
                _ => {
                    let level = 8 + (index as u32 - 232) * 10;
                    (level << 16) | (level << 8) | level
                }
            }
        }
        2 => value & 0xff_ffff,
        _ => default,
    }
}

/// Final foreground and background for one styled run under a palette.
pub fn styled_colors(run: &StyledRun, palette: &Palette) -> (u32, u32) {
    let mut fg = if run.modifier & FOCUS_RING != 0 {
        palette.accent
    } else {
        resolve_color(run.fg, palette.foreground, palette)
    };
    let mut bg = resolve_color(run.bg, palette.background, palette);
    if run.modifier & REVERSED != 0 {
        std::mem::swap(&mut fg, &mut bg);
    }
    if run.modifier & DIM != 0 {
        fg = ((fg & 0xfe_fefe) >> 1) + ((bg & 0xfe_fefe) >> 1);
    }
    if run.modifier & HIDDEN != 0 {
        fg = bg;
    }
    (fg, bg)
}

/// One contiguous span of cells that share fg, bg and attribute bits.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct StyledRun {
    /// Byte offset into the row text.
    pub start: usize,
    /// Length in bytes.
    pub len: usize,
    /// Wire-encoded color; the renderer resolves it against the palette.
    pub fg: u32,
    pub bg: u32,
    pub modifier: u16,
    /// Index into `Snapshot::links` when this run is clickable.
    pub link: Option<u16>,
}

#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct StyledRow {
    pub text: String,
    pub runs: Vec<StyledRun>,
}

/// Local display settings only: no terminal generation or remote resize is
/// implied. Java applies the user's 11–18 dp choice and bounded system font
/// scale before crossing JNI, so the effective size can reach 36 logical px.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DisplayPreferences {
    pub(crate) font_size: f32,
    pub(crate) compact: bool,
}

impl Default for DisplayPreferences {
    fn default() -> Self {
        Self {
            // Zero means "auto": the largest size at which the pane's widest
            // line still fits the surface width.
            font_size: 0.,
            compact: false,
        }
    }
}

impl DisplayPreferences {
    pub fn new(font_size: f32, compact: bool) -> Result<Self> {
        // 0 is the auto sentinel; everything else is a deliberate user choice.
        if !font_size.is_finite() || (font_size != 0. && !(11.0..=36.0).contains(&font_size)) {
            return Err(Error::InvalidDisplayPreferences);
        }
        Ok(Self { font_size, compact })
    }
}

/// Keep terminal painting, IME placement and responsive chrome on the same
/// logical-pixel geometry. Device physical resolution never chooses a profile.
#[cfg(any(feature = "android-ui", test))]
#[derive(Clone, Copy, Debug)]
pub(crate) struct DisplayMetrics {
    pub font_size: f32,
    pub row_height: f32,
    pub header_height: f32,
    /// Kept for the layout tests that pinned the removed footer row.
    #[allow(dead_code)]
    pub touch_height: f32,
    pub padding: f32,
    pub gap: f32,
    #[allow(dead_code)]
    pub stacked_controls: bool,
}

#[cfg(any(feature = "android-ui", test))]
impl DisplayMetrics {
    pub fn new(preferences: DisplayPreferences, viewport_width: f32) -> Self {
        Self::fit(preferences, viewport_width, 0)
    }

    /// `widest_line_chars` is the pane's own wrapping width in cells. Auto type
    /// takes the largest size whose line fits the surface, so a narrow pane on a
    /// wide panel grows instead of leaving the right half empty.
    pub fn fit(preferences: DisplayPreferences, viewport_width: f32, widest_line_chars: usize) -> Self {
        let font_size = if preferences.font_size > 0. {
            preferences.font_size
        } else if widest_line_chars > 0 && viewport_width > 0. {
            (viewport_width / (widest_line_chars as f32 * 0.6)).clamp(11., 20.)
        } else {
            13.
        };
        let padding = if preferences.compact { 4. } else { 8. };
        let gap = padding;
        // The longest control label is Keyboard. At larger accessibility sizes
        // it gets its own row; Follow and Back share a second row. Labels never
        // force the whole surface wider than a 320 dp window.
        let control_width = 8. * font_size * 0.65 + 6.;
        Self {
            font_size,
            row_height: (font_size * if preferences.compact { 1.4 } else { 1.5 }).ceil(),
            header_height: (font_size * 1.5 + 2. * padding).ceil(),
            touch_height: (font_size * 1.5 + 8.).ceil().max(48.),
            padding,
            gap,
            stacked_controls: 3. * control_width + 2. * (gap + padding) > viewport_width,
        }
    }

    pub fn row_width(self, widest_line_chars: usize) -> f32 {
        // Reserve space for fallback wide glyphs as before, and include both
        // row gutters so the last glyph remains reachable by horizontal scroll.
        (widest_line_chars.min(MAX_LINE_CHARS) as f32 * (self.font_size * 1.25).ceil() + 16.)
            .max(200.)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase", deny_unknown_fields)]
pub enum Command {
    Text {
        text: String,
    },
    Keys {
        keys: Vec<String>,
    },
    Close,
    /// One FIFO slot; the Java pump acknowledges text before sending Enter.
    Submit {
        text: String,
    },
}

impl Command {
    pub fn validate(&self) -> Result<()> {
        match self {
            Self::Text { text } if text.is_empty() || text.len() > MAX_TEXT_BYTES => {
                Err(Error::InvalidText)
            }
            Self::Submit { text }
                if text.is_empty()
                    || text.len() > MAX_SUBMIT_BYTES
                    || text
                        .chars()
                        .any(|ch| ch.is_control() || matches!(ch, '\u{2028}' | '\u{2029}')) =>
            {
                Err(Error::InvalidCommand)
            }
            Self::Keys { keys }
                if keys.is_empty() || keys.len() > 16 || !keys.iter().all(|key| valid_key(key)) =>
            {
                Err(Error::InvalidKeys)
            }
            _ => Ok(()),
        }
    }
}

/// Native Android extra keys/macros join physical and IME input synchronously
/// on Java's UI thread. Reject unknown fields and non-input commands at JNI.
pub fn parse_command(json: &str) -> Result<Command> {
    if json.len() > MAX_COMMAND_JSON {
        return Err(Error::InvalidCommand);
    }
    let command: Command = serde_json::from_str(json)?;
    if matches!(command, Command::Close) {
        return Err(Error::InvalidCommand);
    }
    command.validate()?;
    Ok(command)
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Shortcut {
    pub label: String,
    pub command: Command,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireShortcut {
    label: String,
    keys: Option<Vec<String>>,
    text: Option<String>,
}

pub fn parse_shortcuts(json: &str) -> Result<Vec<Shortcut>> {
    if json.len() > MAX_SHORTCUT_JSON {
        return Err(Error::InvalidShortcuts);
    }
    let wire: Vec<WireShortcut> = serde_json::from_str(json)?;
    if wire.len() > 24 {
        return Err(Error::InvalidShortcuts);
    }
    let mut result = Vec::with_capacity(wire.len());
    for item in wire {
        if item.label.is_empty()
            || item.label.chars().count() > 40
            || item.label.chars().any(char::is_control)
            || result.iter().any(|s: &Shortcut| s.label == item.label)
        {
            return Err(Error::InvalidShortcuts);
        }
        let command = match (item.keys, item.text) {
            (Some(keys), None) => Command::Keys { keys },
            (None, Some(text)) => Command::Text { text },
            _ => return Err(Error::InvalidShortcuts),
        };
        command.validate()?;
        result.push(Shortcut {
            label: item.label,
            command,
        });
    }
    Ok(result)
}

pub fn valid_key(key: &str) -> bool {
    matches!(
        key,
        "enter"
            | "esc"
            | "tab"
            | "space"
            | "backspace"
            | "up"
            | "down"
            | "left"
            | "right"
            | "shift+tab"
            | "y"
            | "n"
            | "1"
            | "2"
            | "3"
            | "4"
            | "5"
            | "6"
            | "7"
            | "8"
            | "9"
            | "f1"
            | "f2"
            | "f3"
            | "f4"
            | "f5"
            | "f6"
            | "f7"
            | "f8"
            | "f9"
            | "f10"
            | "f11"
            | "f12"
    ) || key
        .strip_prefix("ctrl+")
        .or_else(|| key.strip_prefix("alt+"))
        .is_some_and(|letter| letter.len() == 1 && letter.as_bytes()[0].is_ascii_lowercase())
}

/// Only the exact companion grammar can cross the input boundary. Printable
/// characters use the OS text commit path, which preserves layout and IME.
pub fn physical_key(
    key: &str,
    control: bool,
    alt: bool,
    shift: bool,
    platform: bool,
) -> Option<Command> {
    if platform || (control && alt) || ((control || alt) && shift) {
        return None;
    }
    let key = match key {
        "escape" => "esc",
        "return" => "enter",
        other => other,
    };
    let key = if control || alt {
        format!("{}+{key}", if control { "ctrl" } else { "alt" })
    } else if shift {
        if key != "tab" {
            return None;
        }
        "shift+tab".into()
    } else {
        // Digits and y/n are printable, even though they are accepted by the API.
        if key.chars().count() == 1 {
            return None;
        }
        key.into()
    };
    valid_key(&key).then_some(Command::Keys { keys: vec![key] })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Snapshot {
    pub title: String,
    pub status: String,
    pub lines: Vec<Arc<str>>,
    /// Present when the daemon surface styles tile the plain lines exactly.
    pub styled: Option<Arc<Vec<StyledRow>>>,
    /// Bounded, browser-openable targets referenced by styled runs.
    pub links: Arc<Vec<String>>,
    pub shortcuts: Vec<Shortcut>,
    pub truncated: bool,
    pub widest_line_chars: usize,
}

impl Default for Snapshot {
    fn default() -> Self {
        Self {
            title: "Herdr · GPUI".into(),
            status: "Connecting…".into(),
            lines: Vec::new(),
            styled: None,
            links: Arc::default(),
            shortcuts: Vec::new(),
            truncated: false,
            widest_line_chars: 1,
        }
    }
}

impl Snapshot {
    pub fn new(title: &str, text: &str, status: &str, shortcuts: &str) -> Result<Self> {
        let title = plain_label(title, 256);
        let status = plain_label(status, 1024);
        let shortcuts = parse_shortcuts(shortcuts)?;
        let (plain, mut truncated) = display_text(text);
        let mut lines: VecDeque<Arc<str>> = VecDeque::new();
        for line in plain.split('\n') {
            let mut chars = line.chars();
            let line: String = chars.by_ref().take(MAX_LINE_CHARS).collect();
            truncated |= chars.next().is_some();
            if lines.len() == MAX_LINES {
                lines.pop_front();
                truncated = true;
            }
            lines.push_back(Arc::from(line));
        }
        let widest_line_chars = lines
            .iter()
            .map(|line| line.chars().count())
            .max()
            .unwrap_or(1)
            .max(1);
        Ok(Self {
            title,
            status,
            lines: lines.into(),
            styled: None,
            links: Arc::default(),
            shortcuts,
            truncated,
            widest_line_chars,
        })
    }

    /// Parses the optional styled surface. A surface whose rows do not tile the
    /// plain text exactly is dropped rather than misaligned; the renderer then
    /// falls back to plain lines.
    pub fn with_surface(
        title: &str,
        text: &str,
        status: &str,
        shortcuts: &str,
        surface: Option<&str>,
    ) -> Result<Self> {
        let mut snapshot = Self::new(title, text, status, shortcuts)?;
        if let Some(raw) = surface.filter(|raw| !raw.is_empty()) {
            let (rows, links) = parse_surface(raw)?;
            if rows.len() == snapshot.lines.len() {
                snapshot.styled = Some(Arc::new(rows));
                snapshot.links = Arc::new(links);
            } else {
                snapshot.truncated = true;
            }
        }
        Ok(snapshot)
    }
}

/// Parses `{"rows":[{"t":text,"r":[[start,len,fg,bg,modifier],…]},…]}`. Runs must
/// tile every row exactly, so the renderer can index bytes without guessing.
pub fn parse_surface(raw: &str) -> Result<(Vec<StyledRow>, Vec<String>)> {
    if raw.len() > MAX_SNAPSHOT_BYTES {
        return Err(Error::InvalidText);
    }
    let value: serde_json::Value =
        serde_json::from_str(raw).map_err(|_| Error::InvalidText)?;
    let mut links = Vec::new();
    if let Some(values) = value.get("links").and_then(serde_json::Value::as_array) {
        if values.len() > 256 {
            return Err(Error::InvalidText);
        }
        for value in values {
            let url = value.as_str().ok_or(Error::InvalidText)?;
            if url.len() > 2048
                || !(url.starts_with("https://") || url.starts_with("http://"))
                || url.chars().any(char::is_control)
            {
                return Err(Error::InvalidText);
            }
            links.push(url.to_owned());
        }
    }
    let rows = value
        .get("rows")
        .and_then(serde_json::Value::as_array)
        .ok_or(Error::InvalidText)?;
    if rows.len() > MAX_LINES {
        return Err(Error::InvalidText);
    }
    let mut styled = Vec::with_capacity(rows.len());
    let mut bytes = 0usize;
    for row in rows {
        let text = row
            .get("t")
            .and_then(serde_json::Value::as_str)
            .ok_or(Error::InvalidText)?;
        if text.chars().count() > MAX_LINE_CHARS {
            return Err(Error::InvalidText);
        }
        bytes += text.len();
        if bytes > MAX_SNAPSHOT_BYTES {
            return Err(Error::InvalidText);
        }
        let spans = row
            .get("r")
            .and_then(serde_json::Value::as_array)
            .ok_or(Error::InvalidText)?;
        if spans.len() > MAX_RUNS_PER_LINE {
            return Err(Error::InvalidText);
        }
        let mut runs = Vec::with_capacity(spans.len());
        let mut offset = 0usize;
        for span in spans {
            let fields = span.as_array().ok_or(Error::InvalidText)?;
            if fields.len() != 6 {
                return Err(Error::InvalidText);
            }
            let number = |index: usize| -> Result<u32> {
                fields[index]
                    .as_u64()
                    .and_then(|value| u32::try_from(value).ok())
                    .ok_or(Error::InvalidText)
            };
            let start = number(0)? as usize;
            let len = number(1)? as usize;
            if start != offset || len == 0 {
                return Err(Error::InvalidText);
            }
            let end = start.checked_add(len).ok_or(Error::InvalidText)?;
            if end > text.len() || !text.is_char_boundary(start) || !text.is_char_boundary(end) {
                return Err(Error::InvalidText);
            }
            if number(4)? > u16::MAX.into() {
                return Err(Error::InvalidText);
            }
            let link = match number(5)? {
                u32::MAX => None,
                index => Some(u16::try_from(index).map_err(|_| Error::InvalidText)?),
            };
            if link.is_some_and(|index| usize::from(index) >= links.len()) {
                return Err(Error::InvalidText);
            }
            runs.push(StyledRun {
                start,
                len,
                fg: number(2)?,
                bg: number(3)?,
                modifier: number(4)? as u16,
                link,
            });
            offset = end;
        }
        if offset != text.len() {
            return Err(Error::InvalidText);
        }
        styled.push(StyledRow {
            text: text.to_owned(),
            runs,
        });
    }
    Ok((styled, links))
}

fn plain_label(value: &str, max: usize) -> String {
    value
        .chars()
        .filter(|c| !c.is_control())
        .take(max)
        .collect()
}

/// Strip all escape/control sequences rather than interpreting terminal output.
/// OSC clipboard/links, DCS graphics and device queries have no side effects.
fn display_text(text: &str) -> (String, bool) {
    enum Escape {
        None,
        Start,
        Csi,
        String,
        StringTerminator,
    }
    let mut escape = Escape::None;
    let mut result = String::new();
    let mut truncated = false;
    for ch in text.chars() {
        match escape {
            Escape::Start => {
                escape = match ch {
                    '[' => Escape::Csi,
                    ']' | 'P' | '^' | '_' => Escape::String,
                    _ => Escape::None,
                };
                continue;
            }
            Escape::Csi => {
                if ('\u{40}'..='\u{7e}').contains(&ch) {
                    escape = Escape::None;
                }
                continue;
            }
            Escape::String => {
                if ch == '\u{7}' || ch == '\u{9c}' {
                    escape = Escape::None;
                } else if ch == '\u{1b}' {
                    escape = Escape::StringTerminator;
                }
                continue;
            }
            Escape::StringTerminator => {
                escape = if ch == '\\' {
                    Escape::None
                } else {
                    Escape::String
                };
                continue;
            }
            Escape::None => {}
        }
        match ch {
            '\u{1b}' => {
                escape = Escape::Start;
                continue;
            }
            '\u{9b}' => {
                escape = Escape::Csi;
                continue;
            }
            '\u{9d}' | '\u{90}' | '\u{98}' | '\u{9e}' | '\u{9f}' => {
                escape = Escape::String;
                continue;
            }
            '\r' => continue,
            '\n' | '\t' => {}
            c if c.is_control() => continue,
            _ => {}
        }
        if result.len() + ch.len_utf8() > MAX_SNAPSHOT_BYTES {
            truncated = true;
            break;
        }
        result.push(ch);
    }
    (result, truncated)
}

pub struct Mailbox {
    generation: i64,
    active: bool,
    revision: u64,
    snapshot: Arc<Snapshot>,
    commands: VecDeque<Command>,
    ime_session: i64,
    preedit: Arc<str>,
    display_preferences: DisplayPreferences,
    pub follow: bool,
}

#[derive(Clone)]
pub struct ViewState {
    pub generation: i64,
    pub active: bool,
    pub revision: u64,
    pub snapshot: Arc<Snapshot>,
    pub preedit: Arc<str>,
    pub display_preferences: DisplayPreferences,
    /// Whether new output should pull the viewport to the newest line.
    pub follow: bool,
}

impl Default for Mailbox {
    fn default() -> Self {
        Self {
            generation: 0,
            active: false,
            revision: 0,
            snapshot: Arc::default(),
            commands: VecDeque::new(),
            ime_session: 0,
            preedit: Arc::from(""),
            display_preferences: DisplayPreferences::default(),
            // A fresh session tails its pane until the user scrolls back.
            follow: true,
        }
    }
}

impl Mailbox {
    pub fn set_active(&mut self, generation: i64, active: bool) -> bool {
        if generation <= 0 || generation < self.generation {
            return false;
        }
        if generation == self.generation && active == self.active {
            return false;
        }
        if generation > self.generation {
            self.generation = generation;
            self.snapshot = Arc::default();
            self.commands.clear();
            self.ime_session = 0;
            self.preedit = Arc::from("");
        }
        if !active {
            self.commands.clear();
            self.ime_session = 0;
            self.preedit = Arc::from("");
        }
        self.active = active;
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
        true
    }

    pub fn set_display_preferences(&mut self, font_size: f32, compact: bool) -> Result<bool> {
        let preferences = DisplayPreferences::new(font_size, compact)?;
        if self.display_preferences == preferences {
            return Ok(false);
        }
        self.display_preferences = preferences;
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
        Ok(true)
    }

    /// The Android side owns whether the pane is tailing or reading scrollback.
    pub fn set_follow(&mut self, generation: i64, follow: bool) -> bool {
        if generation <= 0 || generation < self.generation || follow == self.follow {
            return false;
        }
        self.follow = follow;
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
        true
    }

    pub fn set_snapshot(&mut self, generation: i64, snapshot: Snapshot) -> bool {
        if generation <= 0 || generation != self.generation {
            return false;
        }
        if *self.snapshot != snapshot {
            self.snapshot = Arc::new(snapshot);
            self.revision = self.revision.wrapping_add(1);
            #[cfg(feature = "android-ui")]
            signal_change();
        }
        true
    }

    pub fn state(&self) -> ViewState {
        ViewState {
            generation: self.generation,
            active: self.active,
            revision: self.revision,
            snapshot: self.snapshot.clone(),
            preedit: self.preedit.clone(),
            display_preferences: self.display_preferences,
            follow: self.follow,
        }
    }

    fn check_input(&self, generation: i64) -> Result<()> {
        if generation != self.generation {
            return Err(Error::StaleGeneration);
        }
        if !self.active {
            return Err(Error::Inactive);
        }
        Ok(())
    }

    pub fn enqueue(&mut self, generation: i64, command: Command) -> Result<()> {
        self.check_input(generation)?;
        if !self.preedit.is_empty() {
            return Err(Error::Composing);
        }
        self.push_command(command)
    }

    fn push_command(&mut self, command: Command) -> Result<()> {
        command.validate()?;
        if self.commands.len() >= MAX_COMMANDS {
            return Err(Error::QueueFull);
        }
        self.commands.push_back(command);
        Ok(())
    }

    pub fn set_ime_session(&mut self, generation: i64, session: i64) -> bool {
        if generation <= 0
            || generation != self.generation
            || session <= 0
            || session < self.ime_session
        {
            return false;
        }
        self.ime_session = session;
        self.set_preedit("");
        true
    }

    fn set_preedit(&mut self, text: &str) {
        if &*self.preedit == text {
            return;
        }
        self.preedit = Arc::from(text);
        self.revision = self.revision.wrapping_add(1);
        #[cfg(feature = "android-ui")]
        signal_change();
    }

    /// Java's InputConnection callback serializes all commits, surrounding
    /// deletion and physical keys into this same bounded FIFO immediately.
    /// Preedit remains local; it can never race ahead of a terminal Enter key.
    pub fn ime_event(
        &mut self,
        generation: i64,
        session: i64,
        kind: i32,
        text: &str,
        before: usize,
        after: usize,
    ) -> Result<()> {
        self.check_input(generation)?;
        if session <= 0 || session != self.ime_session || text.len() > MAX_TEXT_BYTES {
            return Err(Error::InvalidIme);
        }
        match kind {
            0 => {
                self.set_preedit(text);
                Ok(())
            }
            1 => {
                let result = if text.is_empty() {
                    Ok(())
                } else {
                    let command = if matches!(text, "\n" | "\r" | "\r\n") {
                        Command::Keys {
                            keys: vec!["enter".into()],
                        }
                    } else {
                        Command::Text { text: text.into() }
                    };
                    self.push_command(command)
                };
                // The proxy has committed and cleared its buffer even if our
                // FIFO is full. Report the rejection; never replay it later.
                self.set_preedit("");
                result
            }
            2 | 3 => {
                if !self.preedit.is_empty() {
                    return Err(Error::Composing);
                }
                if after != 0 || before > 16 {
                    return Err(Error::InvalidIme);
                }
                if before == 0 {
                    return Ok(());
                }
                self.push_command(Command::Keys {
                    keys: vec!["backspace".into(); before],
                })
            }
            4 => {
                self.set_preedit("");
                Ok(())
            }
            _ => Err(Error::InvalidIme),
        }
    }

    pub fn take_command(&mut self, generation: i64) -> Option<Command> {
        if !self.active || generation != self.generation {
            return None;
        }
        self.commands.pop_front()
    }
}

// One process-lived TerminalView consumes this single coalesced wake. The actual
// snapshot stays in the mailbox, so slow rendering never builds a state queue.
#[cfg(feature = "android-ui")]
static CHANGES: OnceLock<(async_channel::Sender<()>, async_channel::Receiver<()>)> =
    OnceLock::new();

#[cfg(feature = "android-ui")]
fn signal_change() {
    let (sender, _) = CHANGES.get_or_init(|| async_channel::bounded(1));
    let _ = sender.try_send(());
}

#[cfg(feature = "android-ui")]
pub(crate) fn snapshot_changes() -> async_channel::Receiver<()> {
    CHANGES.get_or_init(|| async_channel::bounded(1)).1.clone()
}

#[cfg(feature = "android-ui")]
static MAILBOX: OnceLock<Mutex<Mailbox>> = OnceLock::new();
#[cfg(feature = "android-ui")]
pub(crate) fn mailbox() -> MutexGuard<'static, Mailbox> {
    // Fail closed after a panicking owner: never replay a possibly partial queue.
    match MAILBOX.get_or_init(Mutex::default).lock() {
        Ok(guard) => guard,
        Err(poisoned) => {
            let mut guard = poisoned.into_inner();
            guard.active = false;
            guard.commands.clear();
            guard
        }
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;
    fn text(value: &str) -> Command {
        Command::Text { text: value.into() }
    }
    fn submit(value: &str) -> Command {
        Command::Submit { text: value.into() }
    }
    #[test]
    fn lifecycle_and_delayed_generations_are_fenced() {
        let mut box_ = Mailbox::default();
        box_.set_active(10, true);
        box_.enqueue(10, text("old")).unwrap();
        box_.set_active(11, false);
        assert!(box_.take_command(10).is_none());
        assert!(!box_.set_active(10, true));
        assert!(!box_.set_snapshot(10, Snapshot::default()));
        assert!(matches!(
            box_.enqueue(10, text("stale")),
            Err(Error::StaleGeneration)
        ));
        assert!(matches!(
            box_.enqueue(11, text("paused")),
            Err(Error::Inactive)
        ));
        box_.set_active(11, true);
        assert!(box_.take_command(11).is_none());
        box_.enqueue(11, text("new")).unwrap();
        box_.set_active(11, false);
        box_.set_active(11, true);
        assert!(box_.take_command(11).is_none());
    }
    #[test]
    fn repeated_state_and_snapshot_updates_preserve_revision_and_fifo() {
        let mut box_ = Mailbox::default();
        assert!(box_.set_active(10, true));
        let snapshot = Snapshot::new("title", "unchanged\noutput", "Live", "[]").unwrap();
        assert!(box_.set_snapshot(10, snapshot.clone()));
        box_.enqueue(10, text("queued")).unwrap();
        let before = box_.state();
        assert!(!box_.set_active(10, true));
        assert!(box_.set_snapshot(10, snapshot));
        let after = box_.state();
        assert_eq!(before.revision, after.revision);
        assert!(Arc::ptr_eq(&before.snapshot, &after.snapshot));
        assert_eq!(box_.take_command(10), Some(text("queued")));
        assert!(box_.set_active(10, false));
        let revision = box_.state().revision;
        assert!(!box_.set_active(10, false));
        assert_eq!(revision, box_.state().revision);
        // An identical inactive flag with a NEW generation still fences state.
        assert!(box_.set_active(11, false));
        assert_eq!(box_.state().generation, 11);
        assert!(box_.state().snapshot.lines.is_empty());
    }
    #[test]
    fn auto_type_fills_a_narrow_pane_and_stays_on_screen_for_a_wide_one() {
        let auto = DisplayPreferences::default();
        // A 70-cell pane on a 798 dp panel grows instead of leaving the row half empty.
        let roomy = DisplayMetrics::fit(auto, 798., 70);
        assert!(roomy.font_size > 13., "{}", roomy.font_size);
        assert!(roomy.font_size <= 20., "{}", roomy.font_size);
        // A 150-cell pane clamps down so the last cell still fits.
        let dense = DisplayMetrics::fit(auto, 798., 150);
        assert!((11.0..=13.0).contains(&dense.font_size), "{}", dense.font_size);
        // An explicit choice always wins over the fit.
        let chosen = DisplayPreferences::new(17., false).unwrap();
        assert_eq!(DisplayMetrics::fit(chosen, 798., 70).font_size, 17.);
        // Before a pane arrives the long-standing default stands.
        assert_eq!(DisplayMetrics::fit(auto, 798., 0).font_size, 13.);
    }

    fn display_preferences_are_bounded_local_and_generation_independent() {
        let mut box_ = Mailbox::default();
        // A fresh session is auto-sized, so 0 changes nothing and 13 is a choice.
        assert!(!box_.set_display_preferences(0., false).unwrap());
        assert!(box_.set_display_preferences(13., false).unwrap());
        assert!(box_.set_display_preferences(12., true).unwrap());
        assert_eq!(box_.state().generation, 0);
        assert!(!box_.state().active);
        box_.set_active(10, true);
        box_.enqueue(10, text("queued")).unwrap();
        box_.set_ime_session(10, 2);
        box_.ime_event(10, 2, 0, "composing", 0, 0).unwrap();
        let before = box_.state();
        assert!(!box_.set_display_preferences(12., true).unwrap());
        assert_eq!(before.revision, box_.state().revision);
        for invalid in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY, 10.9, 36.1] {
            assert!(matches!(
                box_.set_display_preferences(invalid, false),
                Err(Error::InvalidDisplayPreferences)
            ));
        }
        assert_eq!(before.revision, box_.state().revision);
        assert!(box_.set_display_preferences(36., false).unwrap());
        let after = box_.state();
        assert_eq!(after.revision, before.revision + 1);
        assert_eq!(after.generation, 10);
        assert!(after.active);
        assert!(Arc::ptr_eq(&before.snapshot, &after.snapshot));
        assert!(Arc::ptr_eq(&before.preedit, &after.preedit));
        assert_eq!(box_.take_command(10), Some(text("queued")));
        box_.ime_event(10, 2, 1, "committed", 0, 0).unwrap();
        assert_eq!(box_.take_command(10), Some(text("committed")));
        box_.set_active(11, false);
        assert_eq!(box_.state().display_preferences, after.display_preferences);
        assert!(box_.set_display_preferences(11., true).unwrap());
    }
    #[test]
    fn unchanged_preedit_does_not_allocate_or_wake_but_commit_stays_ordered() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        let revision = box_.state().revision;
        box_.set_ime_session(1, 10);
        assert_eq!(revision, box_.state().revision);
        box_.ime_event(1, 10, 0, "ni", 0, 2).unwrap();
        let before = box_.state();
        box_.ime_event(1, 10, 0, "ni", 0, 2).unwrap();
        assert_eq!(before.revision, box_.state().revision);
        assert!(Arc::ptr_eq(&before.preedit, &box_.state().preedit));
        box_.ime_event(1, 10, 1, "你", 0, 0).unwrap();
        let committed_revision = box_.state().revision;
        box_.ime_event(1, 10, 4, "", 0, 0).unwrap();
        assert_eq!(committed_revision, box_.state().revision);
        assert_eq!(box_.take_command(1), Some(text("你")));
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn compact_geometry_adds_terminal_rows_without_reducing_touch_targets() {
        let compact = DisplayMetrics::new(DisplayPreferences::new(12., true).unwrap(), 320.);
        let standard = DisplayMetrics::new(DisplayPreferences::default(), 320.);
        let visible_rows = |metrics: DisplayMetrics, height: f32| {
            let footer_height = metrics.touch_height + 2. * metrics.padding + 1.;
            ((height - metrics.header_height - footer_height) / metrics.row_height).floor()
        };
        assert!(!compact.stacked_controls);
        assert!(!standard.stacked_controls);
        assert_eq!(compact.touch_height, 48.);
        assert_eq!(standard.touch_height, 48.);
        for height in [320., 432., 480., 500.] {
            assert!(visible_rows(compact, height) > visible_rows(standard, height));
            assert!(visible_rows(compact, height) >= 13.);
        }
    }
    #[test]
    fn accessibility_geometry_scales_text_and_keeps_controls_inside_narrow_windows() {
        for compact in [false, true] {
            let baseline =
                DisplayMetrics::new(DisplayPreferences::new(12., compact).unwrap(), 320.);
            for font_size in [11., 12., 13., 18., 24., 36.] {
                for width in [320., 360., 432., 480., 600.] {
                    let metrics = DisplayMetrics::new(
                        DisplayPreferences::new(font_size, compact).unwrap(),
                        width,
                    );
                    assert_eq!(metrics.font_size, font_size);
                    assert!(metrics.row_height >= font_size * 1.4);
                    assert!(metrics.header_height >= font_size * 1.5);
                    assert!(metrics.touch_height >= 48.);
                    let available = width - 2. * metrics.padding;
                    let keyboard_width = if metrics.stacked_controls {
                        available
                    } else {
                        (available - 2. * metrics.gap) / 3.
                    };
                    assert!(keyboard_width >= 8. * font_size * 0.65 + 6.);
                    if metrics.stacked_controls {
                        let other_width = (available - metrics.gap) / 2.;
                        assert!(other_width >= 6. * font_size * 0.65 + 6.);
                    }
                    assert!(metrics.row_width(1) >= 200.);
                    assert_eq!(
                        metrics.row_width(usize::MAX),
                        metrics.row_width(MAX_LINE_CHARS)
                    );
                    if font_size > 12. {
                        assert!(metrics.row_width(100) > baseline.row_width(100));
                    }
                }
            }
        }
        let largest = DisplayMetrics::new(DisplayPreferences::new(36., true).unwrap(), 320.);
        assert!(largest.stacked_controls);
    }
    #[test]
    fn inactive_status_updates_are_visible_without_enabling_input() {
        let mut box_ = Mailbox::default();
        box_.set_active(5, false);
        assert!(box_.set_snapshot(5, Snapshot::new("a", "live", "Offline", "[]").unwrap()));
        assert_eq!(box_.state().snapshot.status, "Offline");
        assert!(!box_.state().active);
    }
    #[test]
    fn fifo_is_bounded_and_never_reorders_or_drops_old_commands() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        for i in 0..MAX_COMMANDS {
            box_.enqueue(1, text(&i.to_string())).unwrap();
        }
        assert!(matches!(
            box_.enqueue(1, text("overflow")),
            Err(Error::QueueFull)
        ));
        for i in 0..MAX_COMMANDS {
            assert_eq!(box_.take_command(1), Some(text(&i.to_string())));
        }
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn shortcut_schema_is_strict_and_uses_actual_companion_keys() {
        assert_eq!(
            parse_shortcuts(
                r#"[{"label":"C-c","keys":["ctrl+c"]},{"label":"Status","text":"git status"}]"#
            )
            .unwrap()
            .len(),
            2
        );
        for bad in [
            r#"[{"label":"bad","keys":["C-c"]}]"#,
            r#"[{"label":"both","keys":["esc"],"text":"oops"}]"#,
            r#"[{"label":"bad","text":"ok","submit":true}]"#,
            r#"[{"label":"bad","keys":[]}]"#,
        ] {
            assert!(parse_shortcuts(bad).is_err());
        }
    }
    #[test]
    fn text_and_keys_bounds_are_enforced() {
        assert!(text(&"a".repeat(MAX_TEXT_BYTES)).validate().is_ok());
        assert!(
            text(&"😀".repeat(MAX_TEXT_BYTES / 4 + 1))
                .validate()
                .is_err()
        );
        assert!(text("").validate().is_err());
        assert!(
            Command::Keys {
                keys: vec!["enter".into(); 17]
            }
            .validate()
            .is_err()
        );
    }
    #[test]
    fn submit_accepts_only_bounded_single_line_unicode() {
        for valid in [
            "x".into(),
            " git status ".into(),
            "a".repeat(MAX_SUBMIT_BYTES),
            "é".repeat(MAX_SUBMIT_BYTES / 2),
            "😀".repeat(MAX_SUBMIT_BYTES / 4),
        ] {
            assert!(submit(&valid).validate().is_ok());
        }
        for invalid in [
            String::new(),
            "a".repeat(MAX_SUBMIT_BYTES + 1),
            "é".repeat(MAX_SUBMIT_BYTES / 2 + 1),
            "😀".repeat(MAX_SUBMIT_BYTES / 4 + 1),
        ] {
            assert!(matches!(
                submit(&invalid).validate(),
                Err(Error::InvalidCommand)
            ));
        }
        for ch in ('\0'..='\u{1f}')
            .chain('\u{7f}'..='\u{9f}')
            .chain(['\u{2028}', '\u{2029}'])
        {
            let invalid = format!("before{ch}after");
            assert!(matches!(
                submit(&invalid).validate(),
                Err(Error::InvalidCommand)
            ));
            // Explicit text input keeps its original multiline/control policy.
            assert!(text(&invalid).validate().is_ok());
        }
    }
    #[test]
    fn modifier_chords_are_never_silently_stripped() {
        assert_eq!(
            physical_key("c", true, false, false, false),
            Some(Command::Keys {
                keys: vec!["ctrl+c".into()]
            })
        );
        for flags in [
            (true, true, false, false),
            (true, false, true, false),
            (false, false, false, true),
        ] {
            assert!(physical_key("c", flags.0, flags.1, flags.2, flags.3).is_none());
        }
        assert!(physical_key("y", false, false, false, false).is_none());
        assert_eq!(
            physical_key("tab", false, false, true, false),
            Some(Command::Keys {
                keys: vec!["shift+tab".into()]
            })
        );
    }
    #[test]
    fn terminal_sequences_are_never_executed_or_displayed_as_commands() {
        let (plain, _) = display_text(
            "a\u{1b}[31mred\u{1b}[0m\u{1b}]52;c;secret\u{7}b\u{1b}Pgraphics\u{1b}\\c\u{9d}link\u{9c}d\0\n",
        );
        assert_eq!(plain, "aredbcd\n");
    }
    #[test]
    fn snapshot_unicode_and_line_work_are_bounded() {
        let snapshot =
            Snapshot::new("title", &"😀".repeat(MAX_LINE_CHARS + 1), "ok", "[]").unwrap();
        assert_eq!(snapshot.lines[0].chars().count(), MAX_LINE_CHARS);
        assert!(snapshot.truncated);
        let snapshot = Snapshot::new("title", &"x\n".repeat(MAX_LINES + 1), "ok", "[]").unwrap();
        assert_eq!(snapshot.lines.len(), MAX_LINES);
        assert!(snapshot.truncated);
        let (plain, truncated) = display_text(&"😀".repeat(MAX_SNAPSHOT_BYTES));
        assert!(plain.len() <= MAX_SNAPSHOT_BYTES);
        assert!(truncated);
    }
    #[test]
    fn serialization_keeps_text_and_submission_explicit() {
        assert_eq!(
            serde_json::to_string(&text("x\n")).unwrap(),
            r#"{"type":"text","text":"x\n"}"#
        );
        assert_eq!(
            serde_json::to_string(&Command::Close).unwrap(),
            r#"{"type":"close"}"#
        );
        assert_eq!(
            serde_json::to_string(&submit("git status")).unwrap(),
            r#"{"type":"submit","text":"git status"}"#
        );
    }
    #[test]
    fn ime_commits_and_physical_enter_share_exact_fifo_order() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 1, "你", 0, 0).unwrap();
        box_.enqueue(
            1,
            Command::Keys {
                keys: vec!["enter".into()],
            },
        )
        .unwrap();
        assert_eq!(box_.take_command(1), Some(text("你")));
        assert_eq!(
            box_.take_command(1),
            Some(Command::Keys {
                keys: vec!["enter".into()]
            })
        );
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn preedit_blocks_keys_and_only_commit_sends_text() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 0, "ni", 0, 2).unwrap();
        assert_eq!(&*box_.state().preedit, "ni");
        assert!(matches!(
            box_.enqueue(
                1,
                Command::Keys {
                    keys: vec!["enter".into()]
                }
            ),
            Err(Error::Composing)
        ));
        assert!(box_.take_command(1).is_none());
        box_.ime_event(1, 10, 1, "你", 0, 0).unwrap();
        assert_eq!(box_.take_command(1), Some(text("你")));
        assert!(box_.state().preedit.is_empty());
    }
    #[test]
    fn old_ime_sessions_and_generations_cannot_replay_input() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 0, "old", 0, 0).unwrap();
        box_.set_ime_session(1, 11);
        assert!(matches!(
            box_.ime_event(1, 10, 1, "late", 0, 0),
            Err(Error::InvalidIme)
        ));
        box_.set_active(2, true);
        box_.set_ime_session(2, 12);
        assert!(matches!(
            box_.ime_event(1, 11, 1, "late", 0, 0),
            Err(Error::StaleGeneration)
        ));
        assert!(box_.state().preedit.is_empty());
        assert!(box_.take_command(2).is_none());
    }
    #[test]
    fn ime_deletion_bounds_and_done_are_explicit() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        assert!(box_.ime_event(1, 10, 2, "", 17, 0).is_err());
        assert!(box_.ime_event(1, 10, 3, "", 1, 1).is_err());
        box_.ime_event(1, 10, 3, "", 2, 0).unwrap();
        assert_eq!(
            box_.take_command(1),
            Some(Command::Keys {
                keys: vec!["backspace".into(); 2]
            })
        );
        box_.ime_event(1, 10, 4, "", 0, 0).unwrap();
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn native_command_json_is_strict_and_rejects_close() {
        assert_eq!(
            parse_command(r#"{"type":"text","text":"git status"}"#).unwrap(),
            text("git status")
        );
        assert_eq!(
            parse_command(r#"{"type":"submit","text":"echo \ud83d\ude00"}"#).unwrap(),
            submit("echo 😀")
        );
        assert!(matches!(
            parse_command(r#"{"type":"close"}"#),
            Err(Error::InvalidCommand)
        ));
        for bad in [
            r#"{"type":"text","text":"x","submit":true}"#,
            r#"{"type":"keys","keys":["ctrl+c"],"text":"x"}"#,
            r#"{"type":"keys","keys":["C-c"]}"#,
            r#"{"type":"submit"}"#,
            r#"{"type":"submit","text":null}"#,
            r#"{"type":"submit","text":1}"#,
            r#"{"type":"submit","text":"x","keys":[]}"#,
            r#"{"type":"submit","text":"x","submit":true}"#,
            r#"{"type":"submit","type":"text","text":"x"}"#,
            r#"{"type":"submit","text":"x","text":"y"}"#,
            r#"{"type":"submit","text":"x"}{}"#,
            r#"{"type":"submit","text":"\ud800"}"#,
            r#"{"type":"submit","text":"\udc00"}"#,
            r#"{"type":"submit","text":"\ud800x"}"#,
        ] {
            assert!(parse_command(bad).is_err(), "accepted {bad}");
        }
        for invalid in ["", "x\ny", "x\ry", "x\ty", "x\u{2028}y", "x\u{2029}y"] {
            let json = serde_json::to_string(&submit(invalid)).unwrap();
            assert!(matches!(parse_command(&json), Err(Error::InvalidCommand)));
        }
    }
    #[test]
    fn submission_uses_one_bounded_fifo_slot_without_splitting_enter() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        for i in 0..MAX_COMMANDS - 1 {
            box_.enqueue(1, text(&i.to_string())).unwrap();
        }
        box_.enqueue(1, submit("git status")).unwrap();
        assert!(matches!(
            box_.enqueue(1, submit("overflow")),
            Err(Error::QueueFull)
        ));
        for i in 0..MAX_COMMANDS - 1 {
            assert_eq!(box_.take_command(1), Some(text(&i.to_string())));
        }
        assert_eq!(box_.take_command(1), Some(submit("git status")));
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn submission_shares_fifo_order_with_ime_commits_and_physical_keys() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 1, "first", 0, 0).unwrap();
        box_.enqueue(
            1,
            parse_command(r#"{"type":"submit","text":"git status"}"#).unwrap(),
        )
        .unwrap();
        let key = physical_key("c", true, false, false, false).unwrap();
        box_.enqueue(1, key.clone()).unwrap();
        box_.ime_event(1, 10, 1, "last", 0, 0).unwrap();
        assert_eq!(box_.take_command(1), Some(text("first")));
        assert_eq!(box_.take_command(1), Some(submit("git status")));
        assert_eq!(box_.take_command(1), Some(key));
        assert_eq!(box_.take_command(1), Some(text("last")));
        assert!(box_.take_command(1).is_none());
    }
    #[test]
    fn submission_is_composition_and_generation_fenced_without_replay() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 0, "composing", 0, 0).unwrap();
        assert!(matches!(
            box_.enqueue(1, submit("git status")),
            Err(Error::Composing)
        ));
        box_.ime_event(1, 10, 4, "", 0, 0).unwrap();
        assert!(box_.take_command(1).is_none());
        assert!(matches!(
            box_.enqueue(1, submit("invalid\ncommand")),
            Err(Error::InvalidCommand)
        ));
        assert!(box_.take_command(1).is_none());
        box_.enqueue(1, submit("queued")).unwrap();
        box_.set_active(2, true);
        assert!(box_.take_command(1).is_none());
        assert!(box_.take_command(2).is_none());
        assert!(matches!(
            box_.enqueue(1, submit("stale")),
            Err(Error::StaleGeneration)
        ));
        box_.enqueue(2, submit("paused")).unwrap();
        box_.set_active(2, false);
        assert!(matches!(
            box_.enqueue(2, submit("inactive")),
            Err(Error::Inactive)
        ));
        box_.set_active(2, true);
        assert!(box_.take_command(2).is_none());
    }
    #[test]
    fn native_extra_keys_share_fifo_with_ime_and_physical_keys() {
        let mut box_ = Mailbox::default();
        box_.set_active(1, true);
        box_.set_ime_session(1, 10);
        box_.ime_event(1, 10, 1, "first", 0, 0).unwrap();
        box_.enqueue(
            1,
            parse_command(r#"{"type":"text","text":"macro"}"#).unwrap(),
        )
        .unwrap();
        box_.enqueue(
            1,
            Command::Keys {
                keys: vec!["enter".into()],
            },
        )
        .unwrap();
        assert_eq!(box_.take_command(1), Some(text("first")));
        assert_eq!(box_.take_command(1), Some(text("macro")));
        assert_eq!(
            box_.take_command(1),
            Some(Command::Keys {
                keys: vec!["enter".into()]
            })
        );
    }
}
