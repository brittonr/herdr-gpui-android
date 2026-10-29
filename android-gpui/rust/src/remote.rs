//! Bounded Android ownership for embedded SSH and the generation-1 client.
//! No reconnect, daemon lifecycle commands, terminal emulation, or input replay.
use crate::model::{self, Command};
use herdr_client::{ClientEvent, ClientHandle, Method, protocol::*};
use herdr_remote_transport::{CancellationToken, Config, Credentials, Transport};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    collections::HashMap,
    io,
    sync::{
        Arc, Mutex, MutexGuard, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};
use zeroize::Zeroizing;

/// Live sessions: the terminal, one background mirror per other saved host, and
/// transient connections while switching. Still bounded and refused past this.
const MAX_HANDLES: usize = 8;
const MAX_CONFIG: usize = 8192;
const MAX_PANES: usize = 1024;
/// Rows the phone picker can show; the poll payload serializes no more.
const MAX_PICKER: usize = 256;
/// Sidebar rows are bounded so the poll payload stays inside the JNI state limit.
const MAX_CHROME: usize = 64;
const MAX_DISPLAY: usize = 262_144;
pub const MAX_CREDENTIAL_JSON: usize = 420_000;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("Invalid remote configuration")]
    Config,
    #[error("Provide either a bounded password or private key with optional passphrase")]
    Credentials,
    #[error("Remote session is closed")]
    Closed,
    #[error("Too many open remote sessions")]
    Capacity,
    #[error("Select a current pane and wait for its focused surface before sending input")]
    StaleTarget,
    #[error("Pane focus is already pending")]
    FocusPending,
    #[error("Herdr is missing on this host")]
    Missing,
    #[error("Herdr on this host does not support this client")]
    Outdated,
    #[error("The selected Herdr session is stopped")]
    Stopped,
    #[error("Remote authentication failed")]
    Authentication,
    #[error("Could not probe the remote Herdr session")]
    ProbeFailed,
    #[error("Remote state exceeds the mobile client limit")]
    StateLimit,
    #[error(transparent)]
    Json(#[from] serde_json::Error),
    #[error(transparent)]
    Input(#[from] crate::Error),
    #[error(transparent)]
    Client(#[from] herdr_client::Error),
    #[error(transparent)]
    Transport(#[from] herdr_remote_transport::Error),
    #[error(transparent)]
    Terminal(#[from] gpui_rio::Error),
    #[error(transparent)]
    Io(#[from] io::Error),
}
pub type Result<T> = std::result::Result<T, Error>;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireConfig {
    transport: String,
    #[serde(default)]
    host: String,
    #[serde(default = "default_port")]
    port: u16,
    #[serde(default)]
    endpoint_id: String,
    username: String,
    session: String,
    #[serde(default)]
    expected_fingerprint: String,
}
fn default_port() -> u16 {
    22
}
pub(crate) fn parse_config(raw: &str) -> Result<(Config, String)> {
    if raw.len() > MAX_CONFIG {
        return Err(Error::Config);
    }
    let wire: WireConfig = serde_json::from_str(raw)?;
    if wire.username.is_empty()
        || wire.username.len() > 256
        || wire.username.chars().any(char::is_control)
        || wire.session.len() > 128
    {
        return Err(Error::Config);
    }
    herdr_client::remote_probe_command(&wire.session)?;
    let transport = match wire.transport.as_str() {
        "tcp"
            if !wire.host.is_empty()
                && wire.host.len() <= 253
                && wire.port != 0
                && !wire.host.chars().any(char::is_control) =>
        {
            Transport::Tcp {
                host: wire.host,
                port: wire.port,
            }
        }
        "iroh" => Transport::Iroh {
            endpoint_id: wire.endpoint_id,
        },
        _ => return Err(Error::Config),
    };
    Ok((
        Config {
            transport,
            username: wire.username,
            expected_fingerprint: if wire.expected_fingerprint.is_empty() {
                None
            } else {
                Some(wire.expected_fingerprint)
            },
        },
        wire.session,
    ))
}

#[derive(Clone, Serialize, Hash)]
pub struct Pane {
    id: String,
    title: String,
    workspace_id: String,
    tab_id: String,
    status: &'static str,
}
#[derive(Clone, Serialize, Hash)]
pub struct Workspace {
    id: String,
    label: String,
    focused: bool,
    status: &'static str,
}
#[derive(Clone, Serialize, Hash)]
pub struct Tab {
    id: String,
    workspace_id: String,
    label: String,
    focused: bool,
    zoomed: bool,
    status: &'static str,
}
#[derive(Clone, Serialize)]
pub struct View {
    state: &'static str,
    status: String,
    boot_id: String,
    panes: Vec<Pane>,
    workspaces: Vec<Workspace>,
    tabs: Vec<Tab>,
    pane_id: String,
    /// The daemon's own focused pane, used when adopting a host whose first
    /// listed pane is rarely the interesting one.
    focused_pane: String,
    /// Window header prepared with the active host's local display name.
    title: String,
    text: String,
    surface: String,
    /// One pending OSC 52 write, delivered once by `poll`.
    clipboard: Option<String>,
    clipboard_serial: u64,
    /// Notifications and bells, drained by `poll` so each is delivered once.
    notifications: Vec<Notice>,
    bell: u32,
    scroll: Scroll,
    revision: u64,
    /// Content hash the client mirrors back in `poll`; not part of the hash.
    token: i64,
    /// Navigation identity and metadata only; terminal paint never invalidates it.
    chrome_token: i64,
    /// Scroll offset the delivered frame was rendered at.
    rendered_scroll: i64,
    /// Rows above the tail the client is showing from its own buffer.
    local_offset: i64,
    /// Number of rows the buffer can serve beyond the tail window.
    buffered_rows: i64,
}
impl Default for View {
    fn default() -> Self {
        Self {
            state: "connecting",
            status: "Connecting over SSH".into(),
            boot_id: String::new(),
            panes: Vec::new(),
            workspaces: Vec::new(),
            tabs: Vec::new(),
            pane_id: String::new(),
            focused_pane: String::new(),
            title: String::new(),
            text: String::new(),
            surface: String::new(),
            clipboard: None,
            clipboard_serial: 0,
            notifications: Vec::new(),
            bell: 0,
            scroll: Scroll::default(),
            revision: 0,
            token: 0,
            chrome_token: 0,
            rendered_scroll: 0,
            local_offset: 0,
            buffered_rows: 0,
        }
    }
}
struct PendingFocus {
    request_id: String,
    pane_id: String,
    acknowledged: bool,
    started: Instant,
}
#[derive(Default)]
struct State {
    view: View,
    client: Option<ClientHandle>,
    snapshot: Option<Arc<ClientShellSnapshot>>,
    surface: Option<Arc<PaneSurfaceFrame>>,
    pending_focus: Option<PendingFocus>,
    /// Set by our own tab or workspace switch; the next projection adopts the
    /// daemon's focused pane. Another client's focus move is never adopted.
    follow_focus: bool,
    /// Scroll offsets requested but not yet answered by a frame, oldest first.
    pending_offsets: std::collections::VecDeque<i64>,
    /// Pane the currently held text belongs to, so a skew never blanks it.
    text_pane: String,
    /// Rows seen for this pane, keyed by distance from the bottom.
    history: std::collections::BTreeMap<i64, StoredRow>,
    /// Row shown at the top of the viewport, counted from the bottom. `None`
    /// follows the tail, so new output keeps scrolling as usual.
    anchor: Option<i64>,
    /// Hyperlink table shared by the buffered rows.
    history_links: serde_json::Value,
}
struct Session {
    state: Mutex<State>,
    cancel: CancellationToken,
}
static SESSIONS: OnceLock<Mutex<HashMap<u64, Arc<Session>>>> = OnceLock::new();
static NEXT_ID: AtomicU64 = AtomicU64::new(1);
fn lock<T>(value: &Mutex<T>) -> MutexGuard<'_, T> {
    value
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}
fn sessions() -> &'static Mutex<HashMap<u64, Arc<Session>>> {
    SESSIONS.get_or_init(Mutex::default)
}
fn session(id: u64) -> Result<Arc<Session>> {
    lock(sessions()).get(&id).cloned().ok_or(Error::Closed)
}

/// Called by Java's background executor. Inspection does not authenticate.
pub fn inspect(raw: &str) -> Result<String> {
    let (config, _) = parse_config(raw)?;
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()?;
    let key = runtime.block_on(herdr_remote_transport::inspect_host_key(
        &config,
        CancellationToken::new(),
    ))?;
    Ok(serde_json::to_string(&json!({"fingerprint": key}))?)
}

/// Returns as soon as a bounded handle and owned background worker exist.
pub fn connect(raw: &str, password: String) -> Result<u64> {
    let mut password = Zeroizing::new(password);
    if password.is_empty() || password.len() > 4096 {
        return Err(Error::Credentials);
    }
    begin(raw, Credentials::password(std::mem::take(&mut *password)))
}

/// "cols,rows,cell_w_px,cell_h_px" from the phone's surface. Out-of-range or
/// malformed input falls back to the 80x24 default rather than a guessed grid.
fn parse_grid(raw: &str) -> herdr_client::ConnectOptions {
    let mut parts = raw.split(',').map(str::trim);
    let cols = parts.next().and_then(|v| v.parse::<u16>().ok());
    let rows = parts.next().and_then(|v| v.parse::<u16>().ok());
    let cell_w = parts.next().and_then(|v| v.parse::<u32>().ok());
    let cell_h = parts.next().and_then(|v| v.parse::<u32>().ok());
    // Exactly four fields: trailing junk means the caller is confused, so fall
    // back rather than accept a value that may not mean what it looks like.
    let nothing_extra = parts.next().is_none();
    match (cols, rows, cell_w, cell_h, nothing_extra) {
        (Some(cols), Some(rows), Some(cell_w), Some(cell_h), true)
            if (20..=400).contains(&cols)
                && (5..=200).contains(&rows)
                && (1..=64).contains(&cell_w)
                && (1..=64).contains(&cell_h) =>
        {
            herdr_client::ConnectOptions {
                surface_size: ClientSurfaceSize { cols, rows },
                cell_width_px: cell_w,
                cell_height_px: cell_h,
            }
        }
        _ => herdr_client::ConnectOptions::default(),
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireCredentials {
    password: Option<Zeroizing<String>>,
    private_key: Option<Zeroizing<String>>,
    passphrase: Option<Zeroizing<String>>,
}
pub(crate) fn parse_credentials(raw: &str) -> Result<Credentials> {
    if raw.len() > MAX_CREDENTIAL_JSON {
        return Err(Error::Credentials);
    }
    let mut wire: WireCredentials = serde_json::from_str(raw)?;
    match (
        &mut wire.password,
        &mut wire.private_key,
        &mut wire.passphrase,
    ) {
        (Some(password), None, None) if !password.is_empty() && password.len() <= 4096 => {
            Ok(Credentials::password(std::mem::take(&mut **password)))
        }
        (None, Some(key), passphrase)
            if !key.is_empty()
                && key.len() <= 65_536
                && passphrase.as_ref().is_none_or(|value| value.len() <= 4096) =>
        {
            Ok(Credentials::private_key(
                std::mem::take(&mut **key),
                passphrase
                    .as_mut()
                    .map(|value| std::mem::take(&mut **value)),
            ))
        }
        _ => Err(Error::Credentials),
    }
}
/// JNI retains the raw credential JSON in a zeroizing buffer until this returns.
/// Parsed secrets move directly into transport-owned zeroizing storage.
pub fn connect_credentials(raw: &str, credential_json: &str) -> Result<u64> {
    begin_with_grid(raw, parse_credentials(credential_json)?, "", true)
}

/// Negotiate surface interest at connection time; inactive mirrors still
/// receive snapshots. See `parse_grid` for the accepted geometry form.
pub fn connect_credentials_grid(
    raw: &str,
    credential_json: &str,
    grid: &str,
    surface_active: bool,
) -> Result<u64> {
    begin_with_grid(
        raw,
        parse_credentials(credential_json)?,
        grid,
        surface_active,
    )
}

fn begin(raw: &str, credentials: Credentials) -> Result<u64> {
    begin_with_grid(raw, credentials, "", true)
}

fn begin_with_grid(
    raw: &str,
    credentials: Credentials,
    grid: &str,
    surface_active: bool,
) -> Result<u64> {
    let grid = grid.to_owned();
    let (config, name) = parse_config(raw)?;
    let mut all = lock(sessions());
    if all.len() >= MAX_HANDLES {
        return Err(Error::Capacity);
    }
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    if id == 0 || id > i64::MAX as u64 {
        return Err(Error::Capacity);
    }
    let session = Arc::new(Session {
        state: Mutex::new(State::default()),
        cancel: CancellationToken::new(),
    });
    let worker = session.clone();
    std::thread::Builder::new()
        .name("herdr-android-session".into())
        .spawn(move || {
            if let Err(error) = run(
                &worker,
                config,
                credentials,
                name,
                parse_grid(&grid),
                surface_active,
            ) {
                finish(&worker, &error.to_string());
            }
        })?;
    all.insert(id, session);
    Ok(id)
}
fn finish(session: &Session, status: &str) {
    session.cancel.cancel();
    let mut state = lock(&session.state);
    if let Some(client) = state.client.take() {
        client.disconnect();
    }
    state.view.state = "closed";
    state.view.status = clean(status, 1024);
    state.view.text.clear();
    state.view.surface.clear();
    state.pending_focus = None;
    state.surface = None;
    state.snapshot = None;
}
pub fn close(id: u64) {
    if let Some(session) = lock(sessions()).remove(&id) {
        finish(&session, "Disconnected");
    }
}
/// Returns the session view. When `since` already matches the projection
/// revision and nothing one-shot is pending, the answer is a small summary: the
/// app polls twenty times a second and must not re-serialise a whole tree for a
/// frame that did not change.
pub fn poll(id: u64, since: u64, mirror: bool) -> Result<String> {
    let owner = session(id)?;
    let mut state = lock(&owner.state);
    let content = if mirror {
        PollContent::Mirror
    } else {
        PollContent::Full
    };
    poll_view(&mut state, since, content).map(|(json, _)| json)
}

/// Paints the exact view returned by this poll without sending its styled body
/// through Java. The caller holds its connection lock across poll and adoption;
/// the mailbox generation independently fences a replaced activity.
/// Zero `since` requests the full view; zero `since_chrome` refreshes navigation
/// even when the caller already holds the current terminal frame.
#[cfg(feature = "android-ui")]
pub fn poll_terminal(
    id: u64,
    since: u64,
    since_chrome: u64,
    generation: i64,
    host_name: &str,
    shortcuts: &str,
) -> Result<String> {
    if generation <= 0 || model::mailbox().state().generation != generation {
        return Err(Error::StaleTarget);
    }
    let owner = session(id)?;
    let mut state = lock(&owner.state);
    let (json, snapshot) =
        poll_terminal_view(&mut state, since, since_chrome, host_name, shortcuts)?;
    if let Some(snapshot) = snapshot
        && !model::mailbox().set_snapshot(generation, snapshot)
    {
        return Err(Error::StaleTarget);
    }
    Ok(json)
}

#[cfg(any(feature = "android-ui", test))]
fn poll_terminal_view(
    state: &mut State,
    since: u64,
    since_chrome: u64,
    host_name: &str,
    shortcuts: &str,
) -> Result<(String, Option<model::Snapshot>)> {
    update_terminal_title(&mut state.view, host_name);
    let (json, changed) = poll_view(state, since, PollContent::Terminal { since_chrome })?;
    let snapshot = (changed && state.view.state != "closed")
        .then(|| terminal_snapshot(&state.view, shortcuts))
        .transpose()?;
    Ok((json, snapshot))
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum PollContent {
    Full,
    Mirror,
    #[cfg(any(feature = "android-ui", test))]
    Terminal {
        since_chrome: u64,
    },
}

#[cfg(any(feature = "android-ui", test))]
fn update_terminal_title(view: &mut View, host_name: &str) {
    view.title.clear();
    let Some(pane) = view.panes.iter().find(|pane| pane.id == view.pane_id) else {
        view.title.push_str(host_name);
        return;
    };
    let title = pane.title.trim_matches(|ch| ch <= '\u{20}');
    let title = if title.is_empty() { &pane.id } else { title };
    // Android chrome limits labels to 60 UTF-16 units. Keep that width without
    // cutting a surrogate pair when the last character is supplementary.
    let mut units = 0;
    let cut = title.char_indices().find_map(|(index, ch)| {
        units += ch.len_utf16();
        (units > 60).then_some(index)
    });
    let (title, ellipsis) = cut.map_or((title, ""), |end| (&title[..end], "…"));
    view.title
        .reserve(title.len() + ellipsis.len() + " · ".len() + host_name.len());
    view.title.push_str(title);
    view.title.push_str(ellipsis);
    view.title.push_str(" · ");
    view.title.push_str(host_name);
}

#[cfg(any(feature = "android-ui", test))]
fn terminal_snapshot(view: &View, shortcuts: &str) -> Result<model::Snapshot> {
    let selected = view.state == "ready" && !view.pane_id.is_empty() && !view.boot_id.is_empty();
    Ok(model::Snapshot::with_surface(
        &view.title,
        if selected { &view.text } else { "" },
        if selected {
            "SSH connected"
        } else {
            "Waiting for selected remote pane…"
        },
        shortcuts,
        selected.then_some(view.surface.as_str()),
    )?)
}

fn poll_view(state: &mut State, since: u64, content: PollContent) -> Result<(String, bool)> {
    let pending = state.view.clipboard.is_some()
        || !state.view.notifications.is_empty()
        || state.view.bell > 0;
    // The projection revision moves even when nothing a client draws changes,
    // so the token hashes the rendered content instead.
    let (token, chrome_token) = view_tokens(&state.view);
    // A current frame cannot repair a missing or stale navigation cache.
    let (omit_chrome, refresh_chrome) = match content {
        #[cfg(any(feature = "android-ui", test))]
        PollContent::Terminal { since_chrome } => {
            let matches = since_chrome != 0 && since_chrome == chrome_token;
            (since != 0 && matches, !matches)
        }
        _ => (false, false),
    };
    // Zero means "I hold nothing"; a brand-new session must get the whole view
    // even though its token is still zero.
    if since != 0 && since == token && !pending && !refresh_chrome {
        let quiet = json!({
            "unchanged": true,
            "token": token as i64,
            "chrome_token": chrome_token as i64,
            "revision": state.view.revision,
            "state": state.view.state,
            "status": state.view.status,
            "boot_id": state.view.boot_id,
            "pane_id": state.view.pane_id,
            "focused_pane": state.view.focused_pane,
            "scroll": &state.view.scroll,
        });
        return Ok((serde_json::to_string(&quiet)?, false));
    }
    // java.lang.Long cannot hold the top of u64, so publish it signed.
    state.view.token = token as i64;
    state.view.chrome_token = chrome_token as i64;
    // Neither mirror nor terminal metadata needs a body. The terminal renders
    // in Rust and reads its held snapshot only when Copy is requested.
    let text = (content != PollContent::Full).then(|| std::mem::take(&mut state.view.text));
    let surface = (content != PollContent::Full).then(|| std::mem::take(&mut state.view.surface));
    // The Java connection already owns these arrays at this chrome token. Move
    // them aside without copying; restore them before preparing the native frame.
    let chrome = omit_chrome.then(|| {
        (
            std::mem::take(&mut state.view.panes),
            std::mem::take(&mut state.view.workspaces),
            std::mem::take(&mut state.view.tabs),
            std::mem::take(&mut state.view.title),
        )
    });
    let json = serde_json::to_string(&state.view);
    if let Some((panes, workspaces, tabs, title)) = chrome {
        state.view.panes = panes;
        state.view.workspaces = workspaces;
        state.view.tabs = tabs;
        state.view.title = title;
    }
    if let Some(text) = text {
        state.view.text = text;
    }
    if let Some(surface) = surface {
        state.view.surface = surface;
    }
    let json = json?;
    if json.len() > 1_048_576 {
        return Err(Error::StateLimit);
    }
    // Clipboard writes, notifications and bells are delivered exactly once,
    // so they never inflate every later poll with the same payload.
    state.view.clipboard = None;
    state.view.notifications.clear();
    state.view.bell = 0;
    Ok((json, true))
}
/// Finish the navigation hash before terminal data, so both invalidation scopes
/// share one pass over metadata and neither hashes its own derived tokens.
fn view_tokens(view: &View) -> (u64, u64) {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    view.boot_id.hash(&mut hasher);
    view.pane_id.hash(&mut hasher);
    view.focused_pane.hash(&mut hasher);
    view.title.hash(&mut hasher);
    view.panes.hash(&mut hasher);
    view.workspaces.hash(&mut hasher);
    view.tabs.hash(&mut hasher);
    let chrome_token = hasher.finish();
    view.state.hash(&mut hasher);
    view.status.hash(&mut hasher);
    view.text.hash(&mut hasher);
    view.surface.hash(&mut hasher);
    view.scroll.offset.hash(&mut hasher);
    view.scroll.max.hash(&mut hasher);
    view.scroll.rows.hash(&mut hasher);
    (hasher.finish(), chrome_token)
}

fn clean(raw: &str, max: usize) -> String {
    let mut result = String::new();
    for ch in raw.chars().filter(|c| !c.is_control()) {
        if result.len() + ch.len_utf8() > max {
            break;
        }
        result.push(ch);
    }
    result
}

fn run(
    owner: &Arc<Session>,
    config: Config,
    credentials: Credentials,
    name: String,
    options: herdr_client::ConnectOptions,
    surface_active: bool,
) -> Result<()> {
    let runtime = Arc::new(
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()?,
    );
    let guard = Arc::new(Mutex::new(None));
    let worker_runtime = runtime.clone();
    let worker_guard = guard.clone();
    let (setup_finished, setup_done) = std::sync::mpsc::sync_channel(1);
    let cancel = owner.cancel.clone();
    let client =
        herdr_client::connect_with_remote_connector(options, surface_active, move |stop| {
            let setup = || -> Result<_> {
                let mut ssh = worker_runtime.block_on(herdr_remote_transport::connect(
                    &config,
                    credentials,
                    cancel,
                ))?;
                let probe_result = (|| -> Result<String> {
                    let probe = herdr_client::remote_probe_command(&name)?;
                    let output = worker_runtime.block_on(ssh.exec_bounded(
                        &probe,
                        herdr_remote_transport::ExecLimits {
                            max_stdout_bytes: 64 * 1024,
                            ..Default::default()
                        },
                    ))?;
                    if output.exit_status != 0 {
                        return Err(Error::ProbeFailed);
                    }
                    match herdr_client::classify_remote_probe(&output.stdout)? {
                        herdr_client::HostProbe::Running => {}
                        herdr_client::HostProbe::Missing => return Err(Error::Missing),
                        herdr_client::HostProbe::Outdated => return Err(Error::Outdated),
                        herdr_client::HostProbe::Stopped => return Err(Error::Stopped),
                        herdr_client::HostProbe::SshFailed => return Err(Error::Authentication),
                    }
                    Ok(herdr_client::remote_bridge_command(&name)?)
                })();
                let command = match probe_result {
                    Ok(command) => command,
                    Err(error) => {
                        worker_runtime.block_on(ssh.shutdown());
                        return Err(error);
                    }
                };
                let bridge = worker_runtime.block_on(ssh.into_bridge(&command))?;
                let (mut stream, bridge_guard) = bridge.into_parts();
                let negotiation = (|| -> Result<()> {
                    stream.set_read_timeout(Some(Duration::from_millis(10)))?;
                    stream.set_write_timeout(Some(Duration::from_secs(1)))?;
                    herdr_client::negotiate_remote_bridge(&mut stream, stop)?;
                    Ok(())
                })();
                if let Err(error) = negotiation {
                    worker_runtime.block_on(bridge_guard.shutdown());
                    return Err(error);
                }
                *lock(&worker_guard) = Some(bridge_guard);
                Ok(stream)
            };
            let result = setup().map_err(io::Error::other);
            let _ = setup_finished.try_send(());
            result
        })?;
    {
        let mut state = lock(&owner.state);
        if owner.cancel.is_cancelled() {
            client.handle.disconnect();
        } else {
            state.client = Some(client.handle.clone());
        }
    }
    // Herdr answers its programs' color queries with the foreground client's
    // theme, and an attaching terminal connection becomes that client. Report
    // the palette once the boot is known and again whenever it changes. A
    // failed enqueue leaves `reported` behind, so a later tick sends it again.
    let mut reported: Option<model::Palette> = None;
    while !owner.cancel.is_cancelled() && !client.handle.is_disconnected() {
        if let Ok(event) = client.events.recv_timeout(Duration::from_millis(25)) {
            let failure = apply_event(&mut lock(&owner.state), event);
            if let Some(reason) = failure {
                finish(owner, &reason);
                break;
            }
        }
        let palette = model::palette();
        if reported != Some(palette) {
            let boot_id = lock(&owner.state).view.boot_id.clone();
            if !boot_id.is_empty()
                && client
                    .handle
                    .set_host_theme(&boot_id, &host_theme(&palette))
                    .is_ok()
            {
                reported = Some(palette);
            }
        }
        let focus_expired = lock(&owner.state)
            .pending_focus
            .as_ref()
            .is_some_and(|pending| pending.started.elapsed() > Duration::from_secs(10));
        if focus_expired {
            finish(
                owner,
                "Pane focus timed out. Reopen the session before sending input",
            );
            break;
        }
    }
    if !owner.cancel.is_cancelled() {
        // Drain the terminal event before reporting a generic disconnected state.
        for event in client.events.try_iter() {
            let failure = apply_event(&mut lock(&owner.state), event);
            if let Some(reason) = failure {
                finish(owner, &reason);
                break;
            }
        }
        if !owner.cancel.is_cancelled() {
            finish(owner, "Remote session disconnected");
        }
    }
    client.handle.disconnect();
    owner.cancel.cancel();
    // Setup observes cancellation and bounds every transport wait. Its final
    // signal fences guard transfer even when Close races bridge negotiation.
    // This wait runs only on this owned background manager, never a JNI caller.
    let _ = setup_done.recv();
    let bridge_guard = lock(&guard).take();
    if let Some(bridge_guard) = bridge_guard {
        runtime.block_on(bridge_guard.shutdown());
    }
    // Runtime and relay ownership ends on this background thread, never JNI/UI.
    drop(runtime);
    Ok(())
}

/// What Herdr is told about this client's terminal, so a program asking for
/// its colors (OSC 10/11/4) or color scheme gets the palette the phone paints
/// with. The appearance is the palette's own: "Follow system" already turns the
/// system appearance into a light or dark palette, and a chosen palette is what
/// the cells look like whatever the system says.
fn host_theme(palette: &model::Palette) -> herdr_client::HostTheme {
    let rgb = |color: u32| {
        let [_, r, g, b] = color.to_be_bytes();
        ClientHostColor { r, g, b }
    };
    herdr_client::HostTheme {
        foreground: rgb(palette.foreground),
        background: rgb(palette.background),
        // Every indexed wire color as painted: the ANSI sixteen, cube and greys.
        palette: std::array::from_fn(|index| {
            rgb(model::resolve_color(
                0x0100_0000 | index as u32,
                palette.background,
                palette,
            ))
        }),
        appearance: if palette.is_light() {
            ClientHostAppearance::Light
        } else {
            ClientHostAppearance::Dark
        },
    }
}

/// Scroll position of the focused pane, so the activity can bound a swipe.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
pub struct Scroll {
    offset: u64,
    max: u64,
    rows: u64,
}

/// Clamps a requested offset to the pane's own scrollback window.
fn clamp_offset(requested: i64, max: u64) -> u64 {
    requested.max(0).min(i64::try_from(max).unwrap_or(i64::MAX)) as u64
}

/// One daemon notification waiting to be posted by the activity.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Notice {
    title: String,
    body: String,
    pane_id: String,
}

/// Pending notifications kept for the activity; the oldest is dropped first.
const MAX_NOTICES: usize = 8;

/// The largest decoded OSC 52 write this client will put on the pasteboard.
const MAX_CLIPBOARD_TEXT: usize = 256 * 1024;

/// OSC 52 payloads are base64 of the raw bytes. Only bounded, valid UTF-8 text
/// is accepted; anything else is dropped rather than guessed at.
fn decode_osc52(data: &str) -> Option<String> {
    if data.len() > MAX_CLIPBOARD_TEXT.div_ceil(3) * 4 + 4 {
        return None;
    }
    let mut normalised = String::with_capacity(data.len());
    for ch in data.chars() {
        match ch {
            '\n' | '\r' => continue,
            '-' => normalised.push('+'),
            '_' => normalised.push('/'),
            other => normalised.push(other),
        }
    }
    // Padding may only trail; a symbol after it is malformed.
    let trimmed = normalised.trim_end_matches('=');
    if trimmed.contains('=') {
        return None;
    }
    let mut padded = trimmed.to_owned();
    match padded.len() % 4 {
        0 => {}
        2 => padded.push_str("=="),
        3 => padded.push('='),
        _ => return None,
    }
    let bytes = base64_decode(&padded)?;
    if bytes.len() > MAX_CLIPBOARD_TEXT {
        return None;
    }
    String::from_utf8(bytes).ok()
}

fn base64_decode(data: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(data.len() / 4 * 3);
    let mut buffer: u32 = 0;
    let mut bits: u32 = 0;
    let mut padding = 0usize;
    for byte in data.bytes() {
        let value = match byte {
            b'A'..=b'Z' => byte - b'A',
            b'a'..=b'z' => byte - b'a' + 26,
            b'0'..=b'9' => byte - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' if padding < 2 => {
                padding += 1;
                continue;
            }
            _ => return None,
        };
        if padding != 0 {
            return None;
        }
        buffer = (buffer << 6) | u32::from(value);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits) as u8);
            buffer &= (1 << bits) - 1;
        }
    }
    // A non-canonical tail carries data no base64 writer would produce.
    if padding != 0 && buffer != 0 {
        return None;
    }
    Some(out)
}

fn apply_event(state: &mut State, event: ClientEvent) -> Option<String> {
    match event {
        ClientEvent::Snapshot(snapshot) => {
            if !state.view.boot_id.is_empty() && state.view.boot_id != snapshot.boot_id {
                return Some(
                    "Herdr restarted. Reopen the remote session before sending input".into(),
                );
            }
            if snapshot.boot_id.is_empty()
                || snapshot.boot_id.len() > 128
                || snapshot.panes.len() > MAX_PANES
                || snapshot.agents.len() > MAX_PANES
                || snapshot.workspaces.len() > MAX_PANES
                || snapshot.tabs.len() > MAX_PANES
                || snapshot
                    .panes
                    .iter()
                    .any(|pane| !valid_pane_id(&pane.pane_id))
            {
                return Some("Remote snapshot exceeds the mobile client limits".into());
            }
            if state
                .snapshot
                .as_ref()
                .is_some_and(|current| current.revision > snapshot.revision)
            {
                return None;
            }
            state.view.boot_id.clone_from(&snapshot.boot_id);
            state.view.workspaces = snapshot
                .workspaces
                .iter()
                .take(MAX_CHROME)
                .map(|workspace| Workspace {
                    id: workspace.workspace_id.clone(),
                    label: clean(&workspace.label, 80),
                    focused: workspace.focused,
                    status: agent_status(&workspace.agent_status),
                })
                .collect();
            state.view.tabs = snapshot
                .tabs
                .iter()
                .take(MAX_CHROME)
                .map(|tab| Tab {
                    id: tab.tab_id.clone(),
                    workspace_id: tab.workspace_id.clone(),
                    label: clean(&tab.label, 80),
                    focused: tab.focused,
                    zoomed: tab.zoomed,
                    status: agent_status(&tab.agent_status),
                })
                .collect();
            state.view.focused_pane = snapshot
                .focused_pane_id
                .clone()
                .filter(|pane| valid_pane_id(pane))
                .unwrap_or_default();
            state.view.panes = snapshot
                .panes
                .iter()
                .take(MAX_PICKER)
                .map(|pane| {
                    let agent = snapshot
                        .agents
                        .iter()
                        .find(|agent| agent.pane_id == pane.pane_id);
                    let title = agent
                        .and_then(|a| {
                            a.title
                                .as_deref()
                                .or(a.name.as_deref())
                                .or(a.terminal_title_stripped.as_deref())
                        })
                        .or(pane.label.as_deref())
                        .unwrap_or(&pane.pane_id);
                    Pane {
                        id: pane.pane_id.clone(),
                        title: clean(title, 160),
                        workspace_id: pane.workspace_id.clone(),
                        tab_id: pane.tab_id.clone(),
                        status: agent.map_or("unknown", |a| agent_status(&a.agent_status)),
                    }
                })
                .collect();
            if !state.view.pane_id.is_empty()
                && !snapshot
                    .panes
                    .iter()
                    .any(|pane| pane.pane_id == state.view.pane_id)
            {
                return Some("The selected pane was closed. Reopen the remote session".into());
            }
            if state.pending_focus.as_ref().is_some_and(|pending| {
                !snapshot
                    .panes
                    .iter()
                    .any(|pane| pane.pane_id == pending.pane_id)
            }) {
                return Some("The requested pane was closed".into());
            }
            state.snapshot = Some(snapshot);
        }
        ClientEvent::Message(ServerMessage::SemanticNotification(notification)) => {
            let title = clean(&notification.title, 200);
            if title.is_empty() {
                return None;
            }
            let body = clean(notification.body.as_deref().unwrap_or_default(), 500);
            let pane_id = notification
                .pane_id
                .as_deref()
                .filter(|pane| valid_pane_id(pane))
                .unwrap_or_default()
                .to_owned();
            // A pane owns at most one pending notice; the newest replaces it.
            if !pane_id.is_empty() {
                state
                    .view
                    .notifications
                    .retain(|notice| notice.pane_id != pane_id);
            }
            if state.view.notifications.len() >= MAX_NOTICES {
                state.view.notifications.remove(0);
            }
            state.view.notifications.push(Notice {
                title,
                body,
                pane_id,
            });
            return None;
        }
        ClientEvent::Message(ServerMessage::TerminalBell { count }) => {
            if count > 0 {
                state.view.bell = state.view.bell.saturating_add(u32::from(count));
            }
            return None;
        }
        ClientEvent::Message(ServerMessage::Clipboard { data }) => {
            if let Some(text) = decode_osc52(&data) {
                state.view.clipboard = Some(text);
                state.view.clipboard_serial = state.view.clipboard_serial.wrapping_add(1);
            }
        }
        ClientEvent::Surface(surface) => {
            if !state.view.boot_id.is_empty() && state.view.boot_id != surface.boot_id {
                return Some("Herdr restarted. Reopen the remote session".into());
            }
            if surface.frame.validate().is_err()
                || surface.frame.cells.len() > 1_000_000
                || surface.panes.len() > MAX_PANES
                || surface
                    .panes
                    .iter()
                    .any(|pane| !valid_geometry(surface.as_ref(), pane))
            {
                return Some("Invalid terminal surface".into());
            }
            state.surface = Some(surface);
            // The frame answers the oldest outstanding scroll request.
            if let Some(offset) = state.pending_offsets.pop_front() {
                state.view.rendered_scroll = offset;
            }
        }
        ClientEvent::Response {
            request_id,
            response,
        } => {
            if let Some(pending) = state
                .pending_focus
                .as_mut()
                .filter(|pending| pending.request_id == request_id)
            {
                if response.get("error").is_some_and(|error| !error.is_null())
                    || response.get("result").is_none_or(|result| result.is_null())
                {
                    return Some("Herdr rejected the pane focus request".into());
                }
                pending.acknowledged = true;
            }
        }
        ClientEvent::CommandRejected { reason, .. } => {
            return Some(format!("Command rejected: {reason}"));
        }
        ClientEvent::Disconnected { reason } => return Some(reason),
        _ => {}
    }
    project(state);
    None
}

fn matching_surface<'a>(
    state: &'a State,
    pane_id: &str,
) -> Option<(&'a PaneSurfaceFrame, &'a PaneSurfacePane)> {
    let snapshot = state.snapshot.as_ref()?;
    let surface = state.surface.as_ref()?;
    if snapshot.boot_id != surface.boot_id
        || snapshot.revision != surface.projection_revision
        || snapshot.focused_pane_id.as_deref() != Some(pane_id)
        || surface.popup.is_some()
    {
        return None;
    }
    let pane = surface
        .panes
        .iter()
        .find(|pane| pane.pane_id == pane_id && pane.focused)?;
    Some((surface, pane))
}
/// The latest surface for `pane_id`. Scrolling and history depend on the frame
/// the daemon drew, not on its snapshot revision having caught up.
fn surface_for_pane<'a>(
    state: &'a State,
    pane_id: &str,
) -> Option<(&'a PaneSurfaceFrame, &'a PaneSurfacePane)> {
    let surface = state.surface.as_ref()?;
    if state.view.boot_id != surface.boot_id {
        return None;
    }
    let pane = surface.panes.iter().find(|pane| pane.pane_id == pane_id)?;
    Some((surface, pane))
}

/// Buffered row: the text and styled runs a frame produced for one line.
#[derive(Clone)]
struct StoredRow {
    text: String,
    runs: String,
}

/// Rows kept for local scrolling; one screen is 24 rows.
const HISTORY_ROWS: i64 = 512;

impl State {
    /// Stores one frame's rows under an absolute id that does not move when the
    /// pane grows: `max_offset - distance_from_bottom`.
    fn remember_rows(&mut self, offset: i64, max: i64, text: &str, style: &str) {
        let (links, rows) = style_parts(style);
        self.history_links = links;
        let lines: Vec<&str> = text.split('\n').collect();
        for (index, line) in lines.iter().enumerate() {
            let from_bottom = offset + (lines.len() as i64 - 1 - index as i64);
            let row = max - from_bottom;
            self.history.insert(
                row,
                StoredRow {
                    text: (*line).to_owned(),
                    runs: rows.get(index).cloned().unwrap_or_default(),
                },
            );
        }
        let newest = max;
        let oldest = (newest - HISTORY_ROWS).max(0);
        self.history
            .retain(|row, _| *row >= oldest && *row <= newest);
    }

    /// The window shown for the current local offset, clamped to the buffer.
    /// The window shown for the current anchor. A fixed anchor keeps the rows
    /// under the finger still while output keeps arriving behind them.
    fn window_at(&mut self, rows: usize, max: i64) -> Option<(String, String)> {
        let oldest = *self.history.keys().next()?;
        // Ids grow with the pane, so the tail window's top is `max - rows + 1`
        // and scrolling back walks it down towards `oldest`.
        let tail_top = (max - rows as i64 + 1).max(oldest);
        let top = self.anchor.unwrap_or(tail_top).clamp(oldest, tail_top);
        self.anchor = (top != tail_top).then_some(top);
        let mut text = String::new();
        let mut styled: Vec<String> = Vec::with_capacity(rows);
        for (index, row) in (top..top + rows as i64).enumerate() {
            let stored = self.history.get(&row)?;
            if index != 0 {
                text.push('\n');
            }
            text.push_str(&stored.text);
            styled.push(stored.runs.clone());
        }
        let style = format!(
            "{{\"links\":{},\"rows\":[{}]}}",
            self.history_links,
            styled.join(",")
        );
        Some((text, style))
    }
}

fn style_parts(style: &str) -> (serde_json::Value, Vec<String>) {
    let Ok(value) = serde_json::from_str::<model::WireSurface>(style) else {
        return (json!([]), Vec::new());
    };
    let Ok(rows) = value.rows.iter().map(serde_json::to_string).collect() else {
        return (json!([]), Vec::new());
    };
    (value.links, rows)
}

fn project(state: &mut State) {
    if state.view.state == "closed" {
        return;
    }
    if let Some(pending) = state.pending_focus.as_ref()
        && pending.acknowledged
        && matching_surface(state, &pending.pane_id).is_some()
    {
        state.view.pane_id.clone_from(&pending.pane_id);
        state.pending_focus = None;
    } else if state.pending_focus.is_none() && state.follow_focus {
        // Our own tab or workspace switch moved the daemon's focused pane;
        // follow it rather than waiting for a pane this client no longer owns.
        if let Some(focused) = focused_pane(state) {
            state.view.pane_id.clone_from(&focused);
            state.follow_focus = matching_surface(state, &state.view.pane_id).is_none();
        }
    }
    // A snapshot and its matching surface arrive as separate messages, so their
    // revisions disagree for a frame or two. Blanking the pane on that skew made
    // every update flash; only a different pane may discard the held text.
    if state.text_pane != state.view.pane_id {
        state.view.text.clear();
        state.view.surface.clear();
        state.text_pane.clone_from(&state.view.pane_id);
    }
    state.view.state = if state.snapshot.is_some() && state.pending_focus.is_none() {
        "ready"
    } else {
        "connecting"
    };
    state.view.status = if state.pending_focus.is_some() {
        "Waiting for pane focus"
    } else {
        "Connected over SSH"
    }
    .into();
    let pane_id = state.view.pane_id.clone();
    // No pane chosen yet means a focus is still resolving; painting then would
    // show a pane the user has not been switched to.
    let projection = (!pane_id.is_empty())
        .then(|| surface_for_pane(state, &pane_id))
        .flatten()
        .map(|(surface, _pane)| {
            (
                surface_text(surface),
                surface_style(surface),
                surface.surface_revision,
            )
        });
    if let Some((text, surface, revision)) = projection {
        state.view.scroll = surface_for_pane(state, &pane_id)
            .and_then(|(_, pane)| pane.scroll)
            .filter(|metrics| metrics.viewport_rows > 0)
            .map(|metrics| Scroll {
                offset: metrics
                    .offset_from_bottom
                    .min(metrics.max_offset_from_bottom),
                max: metrics.max_offset_from_bottom,
                rows: metrics.viewport_rows,
            })
            .unwrap_or_default();
        let rows = state.view.scroll.rows.max(1) as usize;
        let max = state.view.scroll.max as i64;
        state.remember_rows(state.view.scroll.offset as i64, max, &text, &surface);
        state.view.revision = revision;
        if state.anchor.is_none() {
            state.view.text = text;
            state.view.surface = surface;
        } else if let Some((local_text, local_surface)) = state.window_at(rows, max) {
            state.view.text = local_text;
            state.view.surface = local_surface;
        } else {
            state.anchor = None;
            state.view.text = text;
            state.view.surface = surface;
        }
        let tail_top = (max - rows as i64 + 1).max(0);
        let oldest = state.history.keys().next().copied().unwrap_or(max);
        state.view.local_offset = tail_top - state.anchor.unwrap_or(tail_top);
        // Rows of history the buffer holds behind the tail window.
        state.view.buffered_rows = (tail_top - oldest).max(0);
    } else if !state.view.pane_id.is_empty() && state.view.text.is_empty() {
        state.view.state = "connecting";
        state.view.status = "Waiting for the selected pane surface".into();
    }
}

/// The daemon's focused pane from the latest snapshot, when it is still valid.
fn focused_pane(state: &State) -> Option<String> {
    let snapshot = state.snapshot.as_ref()?;
    let focused = snapshot.focused_pane_id.as_deref()?;
    snapshot
        .panes
        .iter()
        .any(|pane| pane.pane_id == focused)
        .then(|| focused.to_owned())
}

fn valid_pane_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b':'))
}
/// Desktop status vocabulary, bounded to what the sidebar paints.
fn agent_status(status: &AgentStatus) -> &'static str {
    match status {
        AgentStatus::Idle => "idle",
        AgentStatus::Working => "working",
        AgentStatus::Blocked => "blocked",
        AgentStatus::Done => "done",
        AgentStatus::Unknown => "unknown",
    }
}
fn valid_geometry(surface: &PaneSurfaceFrame, pane: &PaneSurfacePane) -> bool {
    let rect = pane.inner_rect;
    u32::from(rect.x) + u32::from(rect.width) <= u32::from(surface.frame.width)
        && u32::from(rect.y) + u32::from(rect.height) <= u32::from(surface.frame.height)
}

const SURFACE_LIMIT: usize = 300_000;

/// Only links a browser can open, with no control characters.
fn openable_link(url: &str) -> bool {
    let url = url.trim();
    (url.starts_with("https://") || url.starts_with("http://"))
        && url.len() <= 2048
        && !url.chars().any(char::is_control)
}

fn inside(rect: SurfaceRect, x: u16, y: u16) -> bool {
    u32::from(x) >= u32::from(rect.x)
        && u32::from(x) < u32::from(rect.x) + u32::from(rect.width)
        && u32::from(y) >= u32::from(rect.y)
        && u32::from(y) < u32::from(rect.y) + u32::from(rect.height)
}

fn cell_style(cell: &CellData, cursor: bool, focused_border: bool, frame: bool) -> (u32, u32, u16) {
    let mut modifier = cell.modifier;
    if cursor {
        modifier |= model::REVERSED;
    }
    if focused_border {
        modifier |= model::FOCUS_RING;
    }
    if frame {
        modifier |= model::FRAME;
    }
    (cell.fg, cell.bg, modifier)
}

/// Builds the bounded row/run projection the renderer paints for the whole
/// surface frame, so splits and their borders are painted together. Returns an
/// empty string when the frame cannot be represented within the payload budget,
/// leaving the plain text path authoritative.
fn surface_style(surface: &PaneSurfaceFrame) -> String {
    #[derive(Serialize)]
    struct Surface<'a> {
        links: Vec<&'a str>,
        rows: Vec<model::WireSurfaceRow>,
    }

    let frame = &surface.frame;
    let cursor = frame.cursor.as_ref().filter(|cursor| cursor.visible);
    let mut links: Vec<&str> = Vec::new();
    let mut link_index: Vec<Option<u16>> = vec![None; frame.hyperlinks.len()];
    let focused = surface.panes.iter().find(|pane| pane.focused);
    let mut rows = Vec::new();
    for y in 0..frame.height.min(model::MAX_LINES as u16) {
        let mut text = String::new();
        let mut runs: Vec<[u32; 6]> = Vec::new();
        for x in 0..frame.width {
            let Some(cell) = frame
                .cells
                .get(usize::from(y) * usize::from(frame.width) + usize::from(x))
            else {
                return String::new();
            };
            if cell.skip {
                // A wide glyph's second cell: the renderer advances one cell per
                // glyph, so a blank keeps the next glyph in the daemon's column.
                // The plain text for Copy still omits it.
                if let Some(run) = runs.last_mut()
                    && text.len() < model::MAX_LINE_CHARS * 4
                {
                    text.push(' ');
                    run[1] = text.len() as u32 - run[0];
                }
                continue;
            }
            let start = text.len() as u32;
            for ch in cell.symbol.chars().filter(|ch| !ch.is_control()) {
                if text.len() + ch.len_utf8() > model::MAX_LINE_CHARS * 4 {
                    break;
                }
                text.push(ch);
            }
            if text.len() == start as usize {
                continue;
            }
            let on_cursor = cursor.is_some_and(|cursor| cursor.x == x && cursor.y == y);
            let focused_border = focused
                .is_some_and(|pane| inside(pane.rect, x, y) && !inside(pane.inner_rect, x, y));
            // Borders and scrollbars sit outside every pane's content.
            let frame_cell = !surface
                .panes
                .iter()
                .any(|pane| inside(pane.inner_rect, x, y));
            let (fg, bg, modifier) = cell_style(cell, on_cursor, focused_border, frame_cell);
            let link = cell.hyperlink.and_then(|index| {
                let slot = link_index.get_mut(index as usize)?;
                if slot.is_none() {
                    let url = frame.hyperlinks.get(index as usize)?;
                    if links.len() >= 256 || !openable_link(url) {
                        return None;
                    }
                    links.push(url);
                    *slot = Some((links.len() - 1) as u16);
                }
                *slot
            });
            let link = link.map_or(u32::MAX, u32::from);
            let full = runs.len() >= model::MAX_RUNS_PER_LINE;
            match runs.last_mut() {
                Some(run) if run[2..] == [fg, bg, u32::from(modifier), link] => {
                    run[1] = text.len() as u32 - run[0];
                }
                _ if full => {
                    if let Some(run) = runs.last_mut() {
                        run[1] = text.len() as u32 - run[0];
                    }
                }
                _ => runs.push([
                    start,
                    text.len() as u32 - start,
                    fg,
                    bg,
                    u32::from(modifier),
                    link,
                ]),
            }
        }
        rows.push(model::WireSurfaceRow { text, runs });
    }
    let Ok(json) = serde_json::to_string(&Surface { links, rows }) else {
        return String::new();
    };
    if json.len() > SURFACE_LIMIT {
        String::new()
    } else {
        json
    }
}

/// Plain-text fallback for the same frame the styled projection paints, so the
/// two agree on row count and content.
fn surface_text(surface: &PaneSurfaceFrame) -> String {
    let frame = &surface.frame;
    let mut text = String::new();
    for y in 0..frame.height.min(model::MAX_LINES as u16) {
        // Separators belong between rows. A final separator would make the
        // snapshot renderer invent one extra row and scroll the first away.
        if y != 0 {
            text.push('\n');
        }
        let mut columns = 0;
        for x in 0..frame.width {
            let Some(cell) = frame
                .cells
                .get(usize::from(y) * usize::from(frame.width) + usize::from(x))
            else {
                return text;
            };
            if cell.skip {
                continue;
            }
            for ch in cell.symbol.chars().filter(|ch| !ch.is_control()) {
                if text.len() + ch.len_utf8() + 1 > MAX_DISPLAY || columns >= model::MAX_LINE_CHARS
                {
                    break;
                }
                text.push(ch);
                columns += 1;
            }
            if text.len() + 4 >= MAX_DISPLAY {
                return text;
            }
        }
    }
    text
}

pub fn focus(id: u64, pane_id: &str) -> Result<String> {
    let owner = session(id)?;
    let mut state = lock(&owner.state);
    if state.view.state == "closed" || owner.cancel.is_cancelled() {
        return Err(Error::Closed);
    }
    if state.pending_focus.is_some() {
        return Err(Error::FocusPending);
    }
    if pane_id.is_empty()
        || pane_id.len() > 1024
        || !state
            .snapshot
            .as_ref()
            .is_some_and(|snapshot| snapshot.panes.iter().any(|pane| pane.pane_id == pane_id))
    {
        return Err(Error::StaleTarget);
    }
    let client = state.client.as_ref().ok_or(Error::Closed)?;
    let request_id = client.request(
        &state.view.boot_id,
        Method::PaneFocus,
        json!({"pane_id": pane_id}),
    )?;
    state.pending_focus = Some(PendingFocus {
        request_id,
        pane_id: pane_id.into(),
        acknowledged: false,
        started: Instant::now(),
    });
    state.view.text.clear();
    state.view.state = "connecting";
    state.view.status = "Waiting for pane focus".into();
    Ok(json!({"queued": true}).to_string())
}

/// Session actions the phone may request. Anything else is refused, so the
/// renderer cannot send arbitrary daemon methods.
const SESSION_ACTIONS: [&str; 7] = [
    "tab.new",
    "tab.close",
    "pane.split.right",
    "pane.split.down",
    "pane.close",
    "pane.zoom.on",
    "pane.zoom.off",
];

/// Scrolls the focused pane to an absolute offset from the bottom. Zero returns
/// to the live view; the daemon bounds the request to the pane's scrollback.
/// Window pushed straight to the UI while the client scrolls its own buffer.
pub struct UiPaint<'a> {
    pub generation: i64,
    pub title: &'a str,
    pub status: &'a str,
    pub shortcuts: &'a str,
}

/// Moves the client viewport `delta` rows into its own buffer and paints the
/// result directly, so motion follows the finger instead of the poll timer.
pub fn scroll_local(id: u64, delta: i64, paint: UiPaint<'_>) -> Result<String> {
    let owner = session(id)?;
    let mut state = lock(&owner.state);
    if state.view.state == "closed" || owner.cancel.is_cancelled() {
        return Err(Error::Closed);
    }
    let rows = state.view.scroll.rows.max(1) as i64;
    let max = state.view.scroll.max as i64;
    let oldest = state.history.keys().next().copied().unwrap_or(0);
    let tail_top = (max - rows + 1).max(oldest);
    let current = state.anchor.unwrap_or(tail_top);
    // Dragging down (positive delta) reads older rows, i.e. lower ids.
    let next = (current - delta).clamp(oldest, tail_top);
    state.anchor = (next != tail_top).then_some(next);
    let offset = tail_top - next;
    let limit = (tail_top - oldest).max(0);
    project(&mut state);
    // Prepare the borrowed body while it is still the exact anchored view.
    #[cfg(feature = "android-ui")]
    let snapshot = model::Snapshot::with_surface(
        paint.title,
        &state.view.text,
        if paint.status.is_empty() {
            &state.view.status
        } else {
            paint.status
        },
        paint.shortcuts,
        Some(&state.view.surface),
    );
    drop(state);
    // The surface mailbox is a UI concern; `remote` alone has no window to paint.
    #[cfg(feature = "android-ui")]
    if let Ok(snapshot) = snapshot {
        model::mailbox().set_snapshot(paint.generation, snapshot);
    }
    #[cfg(not(feature = "android-ui"))]
    let _ = paint;
    Ok(json!({
        "offset": offset,
        "limit": limit,
        "rows": rows,
        "at_edge": offset == limit && delta > 0,
    })
    .to_string())
}

pub fn scroll_to(id: u64, offset: i64) -> Result<String> {
    let owner = session(id)?;
    let mut state = lock(&owner.state);
    if state.view.state == "closed" || owner.cancel.is_cancelled() {
        return Err(Error::Closed);
    }
    let pane = state
        .snapshot
        .as_ref()
        .and_then(|snapshot| snapshot.focused_pane_id.clone())
        .filter(|pane| valid_pane_id(pane))
        .ok_or(Error::StaleTarget)?;
    let scroll = state.view.scroll;
    let target = clamp_offset(offset, scroll.max);
    if target == scroll.offset {
        return Ok(json!({"queued": false}).to_string());
    }
    state.client.as_ref().ok_or(Error::Closed)?.request(
        &state.view.boot_id,
        Method::PaneScroll,
        json!({"pane_id": pane, "offset_from_bottom": target}),
    )?;
    state.view.scroll.offset = target;
    if state.pending_offsets.len() < 8 {
        state.pending_offsets.push_back(target as i64);
    }
    Ok(json!({"queued": true, "offset": target, "max": scroll.max}).to_string())
}

/// Runs one allowlisted session action against the daemon's focused workspace,
/// tab and pane, then follows whatever pane the daemon focuses.
pub fn session_action(id: u64, action: &str) -> Result<String> {
    if !SESSION_ACTIONS.contains(&action) {
        return Err(Error::Config);
    }
    let owner = session(id)?;
    let mut state = lock(&owner.state);
    if state.view.state == "closed" || owner.cancel.is_cancelled() {
        return Err(Error::Closed);
    }
    let snapshot = state.snapshot.as_ref().ok_or(Error::StaleTarget)?;
    let pane = snapshot
        .focused_pane_id
        .clone()
        .filter(|pane| valid_pane_id(pane))
        .ok_or(Error::StaleTarget)?;
    let tab = snapshot
        .focused_tab_id
        .clone()
        .filter(|tab| valid_pane_id(tab))
        .ok_or(Error::StaleTarget)?;
    let workspace = snapshot
        .focused_workspace_id
        .clone()
        .filter(|workspace| valid_pane_id(workspace))
        .ok_or(Error::StaleTarget)?;
    let (method, args) = match action {
        "tab.new" => (
            Method::TabCreate,
            json!({"workspace_id": workspace, "focus": true}),
        ),
        "tab.close" => (Method::TabClose, json!({"tab_id": tab})),
        "pane.split.right" => (
            Method::PaneSplit,
            json!({"target_pane_id": pane, "direction": "right", "focus": true}),
        ),
        "pane.split.down" => (
            Method::PaneSplit,
            json!({"target_pane_id": pane, "direction": "down", "focus": true}),
        ),
        "pane.close" => (Method::PaneClose, json!({"pane_id": pane})),
        "pane.zoom.on" => (Method::PaneZoom, json!({"pane_id": pane, "mode": "on"})),
        _ => (Method::PaneZoom, json!({"pane_id": pane, "mode": "off"})),
    };
    state
        .client
        .as_ref()
        .ok_or(Error::Closed)?
        .request(&state.view.boot_id, method, args)?;
    // Creation and close move the daemon's focus; follow it on the next frame.
    state.follow_focus = true;
    state.view.text.clear();
    state.view.surface.clear();
    state.view.state = "connecting";
    state.view.status = "Applying session change".into();
    Ok(json!({"queued": true}).to_string())
}

/// Focus a tab inside the current session. The daemon then moves its focused
/// pane and the next projection follows it.
pub fn focus_tab(id: u64, tab_id: &str) -> Result<String> {
    focus_chrome(id, Method::TabFocus, "tab_id", tab_id, |snapshot| {
        snapshot.tabs.iter().any(|tab| tab.tab_id == tab_id)
    })
}

/// Focus a workspace inside the current session, with the same follow-on
/// projection as a tab switch.
pub fn focus_workspace(id: u64, workspace_id: &str) -> Result<String> {
    focus_chrome(
        id,
        Method::WorkspaceFocus,
        "workspace_id",
        workspace_id,
        |snapshot| {
            snapshot
                .workspaces
                .iter()
                .any(|workspace| workspace.workspace_id == workspace_id)
        },
    )
}

fn focus_chrome(
    id: u64,
    method: Method,
    field: &str,
    value: &str,
    known: impl Fn(&ClientShellSnapshot) -> bool,
) -> Result<String> {
    let owner = session(id)?;
    let mut state = lock(&owner.state);
    if state.view.state == "closed" || owner.cancel.is_cancelled() {
        return Err(Error::Closed);
    }
    if value.is_empty()
        || value.len() > 1024
        || !known(state.snapshot.as_ref().ok_or(Error::StaleTarget)?)
    {
        return Err(Error::StaleTarget);
    }
    state.client.as_ref().ok_or(Error::Closed)?.request(
        &state.view.boot_id,
        method,
        json!({ field: value }),
    )?;
    state.follow_focus = true;
    state.view.text.clear();
    state.view.surface.clear();
    state.view.state = "connecting";
    state.view.status = "Switching session view".into();
    Ok(json!({"queued": true}).to_string())
}

pub fn input(id: u64, pane_id: &str, raw: &str) -> Result<String> {
    let events = command_events(model::parse_command(raw)?)?;
    let owner = session(id)?;
    let state = lock(&owner.state);
    if owner.cancel.is_cancelled() || state.view.state == "closed" {
        return Err(Error::Closed);
    }
    if state.pending_focus.is_some()
        || state.view.pane_id != pane_id
        || matching_surface(&state, pane_id).is_none()
    {
        return Err(Error::StaleTarget);
    }
    state
        .client
        .as_ref()
        .ok_or(Error::Closed)?
        .send_input(&state.view.boot_id, pane_id, events)?;
    Ok(json!({"queued": true}).to_string())
}
fn command_events(command: Command) -> Result<Vec<ClientPaneInputEvent>> {
    match command {
        Command::Text { text } => Ok(vec![ClientPaneInputEvent::TextCommit(text)]),
        Command::Submit { text } => Ok(vec![
            ClientPaneInputEvent::TextCommit(text),
            key_event("enter")?,
        ]),
        Command::Keys { keys } => keys.iter().map(|key| key_event(key)).collect(),
        Command::Close => Err(Error::Input(crate::Error::InvalidCommand)),
    }
}
fn key_event(key: &str) -> Result<ClientPaneInputEvent> {
    let (base, modifiers) = if let Some(base) = key.strip_prefix("ctrl+") {
        (base, 2)
    } else if let Some(base) = key.strip_prefix("alt+") {
        (base, 4)
    } else {
        (key, 0)
    };
    let code = match base {
        "enter" => ClientKeyCode::Enter,
        "esc" => ClientKeyCode::Esc,
        "tab" => ClientKeyCode::Tab,
        "space" => ClientKeyCode::Char(' '),
        "backspace" => ClientKeyCode::Backspace,
        "up" => ClientKeyCode::Up,
        "down" => ClientKeyCode::Down,
        "left" => ClientKeyCode::Left,
        "right" => ClientKeyCode::Right,
        "shift+tab" => ClientKeyCode::BackTab,
        key if key.len() > 1 && key.starts_with('f') => ClientKeyCode::F(
            key[1..]
                .parse()
                .map_err(|_| Error::Input(crate::Error::InvalidKeys))?,
        ),
        key if key.len() == 1 => ClientKeyCode::Char(
            key.chars()
                .next()
                .ok_or(Error::Input(crate::Error::InvalidKeys))?,
        ),
        _ => return Err(Error::Input(crate::Error::InvalidKeys)),
    };
    Ok(ClientPaneInputEvent::Key {
        code,
        modifiers,
        kind: ClientKeyKind::Press,
        repeat_count: 1,
        shifted_codepoint: None,
        generated_text: None,
        tracks_release: false,
        physical_key_id: None,
        windows_record: None,
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests {
    use super::parse_grid;

    type ViewChange = (&'static str, fn(&mut View));

    #[test]
    fn grid_parsing_accepts_the_phone_shape_and_falls_back_otherwise() {
        let parsed = parse_grid("259,125,9,19");
        assert_eq!(parsed.surface_size.cols, 259);
        assert_eq!(parsed.surface_size.rows, 125);
        assert_eq!(parsed.cell_width_px, 9);
        assert_eq!(parsed.cell_height_px, 19);

        // Out of range on either axis falls back rather than guessing.
        let fallback = herdr_client::ConnectOptions::default();
        for bad in [
            "19,125,9,19",
            "401,125,9,19",
            "259,4,9,19",
            "259,201,9,19",
            "259,125,0,19",
            "259,125,65,19",
            "259,125,9",
            "",
            "a,b,c,d",
            "259,125,9,19,extra",
        ] {
            let got = parse_grid(bad);
            assert_eq!(got.surface_size, fallback.surface_size, "{bad}");
        }
    }

    use super::*;

    fn snapshot(boot: &str, revision: u64, focus: &str) -> Arc<ClientShellSnapshot> {
        Arc::new(serde_json::from_value(json!({
            "boot_id": boot, "revision": revision, "config_diagnostic": null,
            "product_announcement": null, "update_available": null,
            "update_install_command": "never execute this", "server_keybindings_toml": null,
            "latest_release_notes_available": false, "integration_updates_available": false,
            "worktree_directory": "", "release_notes": null, "focused_workspace_id": null,
            "focused_tab_id": null, "focused_pane_id": focus, "tab_bar_right": [],
            "tab_bar_right_separator": "", "agent_view_label": null, "agent_order": [],
            "workspaces": [], "tabs": [], "agents": [], "commands": [],
            "panes": [{"pane_id": "p1", "workspace_id": "w", "tab_id": "t", "label": "Terminal", "cwd": null,
                       "foreground_cwd": null, "focused": focus == "p1", "right_click_passthrough": false},
                      {"pane_id": "p2", "workspace_id": "w", "tab_id": "t", "label": "Other", "cwd": null,
                       "foreground_cwd": null, "focused": focus == "p2", "right_click_passthrough": false}]
        })).unwrap())
    }
    fn surface(boot: &str, revision: u64, pane_id: &str) -> Arc<PaneSurfaceFrame> {
        let cells = ["x", "x", "x", "x", "x", "A", "B", "x", "x", "x", "x", "x"]
            .into_iter()
            .map(|symbol| CellData {
                symbol: symbol.into(),
                fg: 0,
                bg: 0,
                modifier: 0,
                skip: false,
                hyperlink: None,
            })
            .collect();
        Arc::new(PaneSurfaceFrame {
            boot_id: boot.into(),
            projection_revision: revision,
            surface_revision: 5,
            frame: FrameData {
                cells,
                width: 4,
                height: 3,
                cursor: None,
                hyperlinks: vec![],
                graphics: vec![],
            },
            panes: vec![PaneSurfacePane {
                pane_id: pane_id.into(),
                content_revision: 1,
                rect: SurfaceRect {
                    x: 0,
                    y: 0,
                    width: 4,
                    height: 3,
                },
                inner_rect: SurfaceRect {
                    x: 1,
                    y: 1,
                    width: 2,
                    height: 1,
                },
                scrollbar_rect: None,
                scroll: None,
                focused: true,
                mouse_reporting: false,
                sgr_pixel_mouse: false,
                alternate_screen_active: false,
                pixel_width: 0,
                pixel_height: 0,
            }],
            splits: vec![],
            popup: None,
            graphics: Default::default(),
        })
    }
    fn pending(state: &mut State, id: &str) {
        state.pending_focus = Some(PendingFocus {
            request_id: "focus-1".into(),
            pane_id: id.into(),
            acknowledged: false,
            started: Instant::now(),
        });
    }

    fn polling_state() -> State {
        State {
            view: View {
                state: "ready",
                boot_id: "boot".into(),
                pane_id: "p1".into(),
                text: "frame".into(),
                panes: vec![Pane {
                    id: "p1".into(),
                    title: "Terminal".into(),
                    workspace_id: "w".into(),
                    tab_id: "t".into(),
                    status: "working",
                }],
                workspaces: vec![Workspace {
                    id: "w".into(),
                    label: "Workspace".into(),
                    focused: true,
                    status: "working",
                }],
                tabs: vec![Tab {
                    id: "t".into(),
                    workspace_id: "w".into(),
                    label: "Tab".into(),
                    focused: true,
                    zoomed: false,
                    status: "working",
                }],
                ..View::default()
            },
            ..State::default()
        }
    }

    #[test]
    fn terminal_navigation_cache_recovers_without_waiting_for_another_frame() {
        let mut state = polling_state();
        let (first, _) = poll_terminal_view(&mut state, 0, 0, "Host", "[]").unwrap();
        let first: serde_json::Value = serde_json::from_str(&first).unwrap();
        assert_eq!(first["panes"][0]["title"], "Terminal");
        assert_eq!(first["workspaces"][0]["label"], "Workspace");
        assert_eq!(first["tabs"][0]["label"], "Tab");
        let since = first["token"].as_i64().unwrap() as u64;
        let chrome = first["chrome_token"].as_i64().unwrap() as u64;

        state.view.text = "new frame".into();
        let (delta, paint) = poll_terminal_view(&mut state, since, chrome, "Host", "[]").unwrap();
        let delta: serde_json::Value = serde_json::from_str(&delta).unwrap();
        for field in ["panes", "workspaces", "tabs"] {
            assert_eq!(delta[field], json!([]));
        }
        assert_eq!(paint.unwrap().title, "Terminal · Host");
        let since = delta["token"].as_i64().unwrap() as u64;

        // Losing the navigation cache must force a complete reply even while
        // the full-view token still matches; a quiet reply cannot repair it.
        let (repair, _) = poll_terminal_view(&mut state, since, 0, "Host", "[]").unwrap();
        let repair: serde_json::Value = serde_json::from_str(&repair).unwrap();
        for field in ["panes", "workspaces", "tabs", "title"] {
            assert_eq!(repair[field], first[field]);
        }

        // A new frontend always receives navigation, regardless of a supplied
        // chrome token. The omitted vectors were never discarded from State.
        let (fresh, _) = poll_terminal_view(&mut state, 0, chrome, "Host", "[]").unwrap();
        let fresh: serde_json::Value = serde_json::from_str(&fresh).unwrap();
        assert_eq!(fresh["panes"], first["panes"]);
        state.view.panes[0].status = "done";
        let (updated, _) = poll_terminal_view(&mut state, since, chrome, "Host", "[]").unwrap();
        let updated: serde_json::Value = serde_json::from_str(&updated).unwrap();
        assert_ne!(updated["chrome_token"], first["chrome_token"]);
        assert_eq!(updated["panes"][0]["status"], "done");
        assert_eq!(updated["workspaces"], first["workspaces"]);
        assert_eq!(updated["tabs"], first["tabs"]);
        let since = updated["token"].as_i64().unwrap() as u64;
        let (stale, _) = poll_terminal_view(&mut state, since, chrome, "Host", "[]").unwrap();
        let stale: serde_json::Value = serde_json::from_str(&stale).unwrap();
        assert_eq!(stale["panes"][0]["status"], "done");
    }

    #[test]
    fn reused_navigation_does_not_suppress_or_repeat_pending_effects() {
        let mut state = polling_state();
        let (first, _) = poll_terminal_view(&mut state, 0, 0, "Host", "[]").unwrap();
        let first: serde_json::Value = serde_json::from_str(&first).unwrap();
        let since = first["token"].as_i64().unwrap() as u64;
        let chrome = first["chrome_token"].as_i64().unwrap() as u64;
        state.view.clipboard = Some("copied once".into());
        state.view.bell = 2;
        state.view.notifications.push(Notice {
            title: "Permission needed".into(),
            body: "Choose in this pane".into(),
            pane_id: "p1".into(),
        });
        let (effects, _) = poll_terminal_view(&mut state, since, chrome, "Host", "[]").unwrap();
        let effects: serde_json::Value = serde_json::from_str(&effects).unwrap();
        assert_eq!(effects["panes"], json!([]));
        assert_eq!(effects["clipboard"], "copied once");
        assert_eq!(effects["bell"], 2);
        assert_eq!(effects["notifications"][0]["pane_id"], "p1");
        let (quiet, no_paint) =
            poll_terminal_view(&mut state, since, chrome, "Host", "[]").unwrap();
        let quiet: serde_json::Value = serde_json::from_str(&quiet).unwrap();
        assert_eq!(quiet["unchanged"], true);
        assert!(no_paint.is_none());
    }

    #[test]
    fn native_presentation_updates_styles_without_changing_plain_text_or_navigation() {
        let mut state = State::default();
        state.view.pane_id = "p1".into();
        apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 1, "p1")));
        let mut changed = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        changed.frame.cells[5].symbol = "é".into();
        changed.frame.cells[5].fg = 0x0100_0001;
        apply_event(&mut state, ClientEvent::Surface(Arc::new(changed.clone())));
        let (first, first_snapshot) = poll_terminal_view(&mut state, 0, 0, "Host", "[]").unwrap();
        let first: serde_json::Value = serde_json::from_str(&first).unwrap();
        let first_snapshot = first_snapshot.unwrap();
        assert_eq!(first["text"], "");
        assert_eq!(first_snapshot.lines[1].as_ref(), "xéBx");
        assert_eq!(
            first_snapshot.styled.as_ref().unwrap()[1].runs[1].fg,
            0x0100_0001
        );

        // The text and daemon revision stay unchanged, but the visible color
        // changes. Java no longer sees styles, so the native token must carry it.
        changed.frame.cells[5].fg = 0x0100_0002;
        apply_event(&mut state, ClientEvent::Surface(Arc::new(changed)));
        let since = first["token"].as_i64().unwrap() as u64;
        let since_chrome = first["chrome_token"].as_i64().unwrap() as u64;
        let (second, second_snapshot) =
            poll_terminal_view(&mut state, since, since_chrome, "Host", "[]").unwrap();
        let second: serde_json::Value = serde_json::from_str(&second).unwrap();
        let second_snapshot = second_snapshot.unwrap();
        assert_eq!(second["chrome_token"], first["chrome_token"]);
        assert_ne!(second["token"], first["token"]);
        assert_eq!(second["panes"], json!([]));
        assert_eq!(second_snapshot.title, first_snapshot.title);
        assert_eq!(second_snapshot.lines, first_snapshot.lines);
        assert_eq!(
            second_snapshot.styled.as_ref().unwrap()[1].runs[1].fg,
            0x0100_0002
        );

        let since = second["token"].as_i64().unwrap() as u64;
        let (quiet, no_snapshot) =
            poll_terminal_view(&mut state, since, since_chrome, "Host", "[]").unwrap();
        assert!(no_snapshot.is_none());
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&quiet).unwrap()["unchanged"],
            true
        );

        pending(&mut state, "p2");
        project(&mut state);
        let (_, waiting) =
            poll_terminal_view(&mut state, since, since_chrome, "Host", "[]").unwrap();
        let waiting = waiting.unwrap();
        assert_eq!(
            waiting
                .lines
                .iter()
                .map(|line| line.as_ref())
                .collect::<Vec<_>>(),
            [""]
        );
        assert!(waiting.styled.is_none());
    }

    #[test]
    fn native_presentation_uses_the_anchored_window_until_returning_live() {
        let mut state = State::default();
        state.view.pane_id = "p1".into();
        apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 1, "p1")));
        let mut frame = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        frame.panes[0].scroll = Some(PaneSurfaceScrollMetrics {
            offset_from_bottom: 0,
            max_offset_from_bottom: 5,
            viewport_rows: 3,
        });
        apply_event(&mut state, ClientEvent::Surface(Arc::new(frame)));
        let old_style = r#"{"links":[],"rows":[{"t":"old0","r":[[0,4,1,0,0,4294967295]]},{"t":"old1","r":[[0,4,0,0,0,4294967295]]},{"t":"old2","r":[[0,4,0,0,0,4294967295]]}]}"#;
        state.remember_rows(3, 5, "old0\nold1\nold2", old_style);
        state.anchor = Some(0);
        project(&mut state);
        let (older, old_snapshot) = poll_terminal_view(&mut state, 0, 0, "Host", "[]").unwrap();
        let older: serde_json::Value = serde_json::from_str(&older).unwrap();
        let old_snapshot = old_snapshot.unwrap();
        assert_eq!(
            old_snapshot
                .lines
                .iter()
                .map(|line| line.as_ref())
                .collect::<Vec<_>>(),
            ["old0", "old1", "old2"]
        );
        assert_eq!(old_snapshot.styled.as_ref().unwrap()[0].runs[0].fg, 1);

        state.anchor = None;
        project(&mut state);
        let since = older["token"].as_i64().unwrap() as u64;
        let since_chrome = older["chrome_token"].as_i64().unwrap() as u64;
        let (live, live_snapshot) =
            poll_terminal_view(&mut state, since, since_chrome, "Host", "[]").unwrap();
        let live: serde_json::Value = serde_json::from_str(&live).unwrap();
        let live_snapshot = live_snapshot.unwrap();
        assert_eq!(live["chrome_token"], older["chrome_token"]);
        assert_ne!(live["token"], older["token"]);
        assert_eq!(
            live_snapshot
                .lines
                .iter()
                .map(|line| line.as_ref())
                .collect::<Vec<_>>(),
            ["xxxx", "xABx", "xxxx"]
        );
        assert_eq!(state.view.local_offset, 0);
    }

    #[test]
    fn terminal_updates_leave_navigation_token_unchanged() {
        let view = View::default();
        let before = view_tokens(&view);
        let changes: &[ViewChange] = &[
            ("text", |view| view.text.push_str("new output")),
            ("surface", |view| {
                view.surface.push_str("{\"links\":[],\"rows\":[]}")
            }),
            ("connection phase", |view| view.state = "ready"),
            ("connection status", |view| view.status.push_str(" updated")),
            ("scroll offset", |view| view.scroll.offset = 1),
            ("scroll limit", |view| view.scroll.max = 1),
            ("viewport rows", |view| view.scroll.rows = 24),
        ];
        for (name, change) in changes {
            let mut changed = view.clone();
            change(&mut changed);
            let after = view_tokens(&changed);
            assert_ne!(after.0, before.0, "{name} must update the terminal");
            assert_eq!(after.1, before.1, "{name} must not rebuild navigation");
        }
    }

    #[test]
    fn navigation_token_tracks_identity_labels_status_and_focus() {
        let view = View {
            boot_id: "boot".into(),
            pane_id: "p1".into(),
            focused_pane: "p1".into(),
            panes: vec![Pane {
                id: "p1".into(),
                title: "Terminal".into(),
                workspace_id: "w1".into(),
                tab_id: "t1".into(),
                status: "working",
            }],
            workspaces: vec![Workspace {
                id: "w1".into(),
                label: "Workspace".into(),
                focused: false,
                status: "working",
            }],
            tabs: vec![Tab {
                id: "t1".into(),
                workspace_id: "w1".into(),
                label: "Tab".into(),
                focused: false,
                zoomed: false,
                status: "working",
            }],
            ..View::default()
        };
        let before = view_tokens(&view);
        let changes: &[ViewChange] = &[
            ("boot", |view| view.boot_id.push('2')),
            ("selected pane", |view| view.pane_id = "p2".into()),
            ("daemon focus", |view| view.focused_pane = "p2".into()),
            ("pane title", |view| view.panes[0].title.push('2')),
            ("pane status", |view| view.panes[0].status = "done"),
            ("pane removal", |view| view.panes.clear()),
            ("workspace label", |view| view.workspaces[0].label.push('2')),
            ("workspace focus", |view| view.workspaces[0].focused = true),
            ("tab label", |view| view.tabs[0].label.push('2')),
            ("tab zoom", |view| view.tabs[0].zoomed = true),
        ];
        for (name, change) in changes {
            let mut changed = view.clone();
            change(&mut changed);
            let after = view_tokens(&changed);
            assert_ne!(after.0, before.0, "{name} must reach the client");
            assert_ne!(after.1, before.1, "{name} must update navigation");
        }
    }

    #[test]
    fn scroll_offsets_stay_inside_the_pane_window() {
        assert_eq!(clamp_offset(-5, 100), 0, "never scroll past the live view");
        assert_eq!(clamp_offset(0, 100), 0);
        assert_eq!(clamp_offset(40, 100), 40);
        assert_eq!(
            clamp_offset(1_000, 100),
            100,
            "bounded by the pane's scrollback"
        );
        assert_eq!(clamp_offset(i64::MAX, 0), 0);
    }
    #[test]
    fn session_actions_are_allowlisted() {
        // The allowlist is checked before any session lookup, so an unknown
        // method can never reach the daemon.
        assert!(matches!(
            session_action(1, "pane.clear"),
            Err(Error::Config)
        ));
        assert!(matches!(
            session_action(1, "command.invoke"),
            Err(Error::Config)
        ));
        assert!(matches!(session_action(1, ""), Err(Error::Config)));
        // A stale handle fails closed for an allowed action.
        assert!(matches!(
            session_action(999_999, "tab.new"),
            Err(Error::Closed)
        ));
    }
    #[test]
    fn notifications_and_bells_are_bounded_and_replaced_per_pane() {
        let notice = |pane: Option<&str>, title: &str| {
            ClientEvent::Message(ServerMessage::SemanticNotification(SemanticNotification {
                kind: SemanticNotificationKind::NeedsAttention,
                title: title.into(),
                body: Some("body".into()),
                sound: None,
                agent: None,
                workspace_id: None,
                tab_id: None,
                pane_id: pane.map(str::to_owned),
                position: None,
            }))
        };
        let mut state = State::default();
        apply_event(&mut state, notice(Some("p1"), "first"));
        apply_event(&mut state, notice(Some("p1"), "second"));
        // One pending notice per pane, newest wins.
        assert_eq!(state.view.notifications.len(), 1);
        assert_eq!(state.view.notifications[0].title, "second");
        assert_eq!(state.view.notifications[0].pane_id, "p1");
        for index in 0..12 {
            apply_event(&mut state, notice(Some(&format!("p{index}")), "many"));
        }
        assert_eq!(
            state.view.notifications.len(),
            MAX_NOTICES,
            "the queue stays bounded"
        );
        assert_eq!(
            state.view.notifications.last().unwrap().title,
            "many".to_owned()
        );
        // An empty title is dropped; a hostile pane id never reaches the activity.
        apply_event(&mut state, notice(Some("p1"), "   "));
        apply_event(&mut state, notice(Some("bad id!"), "hostile"));
        assert_eq!(
            state.view.notifications.last().unwrap().pane_id,
            "",
            "invalid pane ids are dropped"
        );
        apply_event(
            &mut state,
            ClientEvent::Message(ServerMessage::TerminalBell { count: 3 }),
        );
        assert_eq!(state.view.bell, 3);
        apply_event(
            &mut state,
            ClientEvent::Message(ServerMessage::TerminalBell { count: 0 }),
        );
        assert_eq!(state.view.bell, 3, "zero counts are ignored");
    }
    #[test]
    fn osc52_writes_only_bounded_valid_text() {
        // "hello" in standard and url-safe base64, with padding optional.
        assert_eq!(decode_osc52("aGVsbG8=").as_deref(), Some("hello"));
        assert_eq!(decode_osc52("aGVsbG8").as_deref(), Some("hello"));
        // The URL-safe alphabet is accepted, not just the standard one.
        assert!(decode_osc52("aGVsbG8_").is_some());
        assert_eq!(decode_osc52(""), Some(String::new()));
        for rejected in ["not base64!", "a", "aGVsbG8=x", "//79"] {
            assert!(
                decode_osc52(rejected).is_none(),
                "{rejected} must be dropped"
            );
        }
        // Raw bytes that are not UTF-8 never reach the pasteboard.
        assert!(decode_osc52("/w==").is_none(), "0xff is not UTF-8");
        let oversized = "A".repeat(MAX_CLIPBOARD_TEXT.div_ceil(3) * 4 + 8);
        assert!(
            decode_osc52(&oversized).is_none(),
            "oversized payloads are dropped"
        );
    }
    #[test]
    fn clipboard_write_is_delivered_once_and_advances_its_serial() {
        let mut state = State::default();
        apply_event(
            &mut state,
            ClientEvent::Message(ServerMessage::Clipboard {
                data: "aGVsbG8=".into(),
            }),
        );
        assert_eq!(state.view.clipboard.as_deref(), Some("hello"));
        assert_eq!(state.view.clipboard_serial, 1);
        apply_event(
            &mut state,
            ClientEvent::Message(ServerMessage::Clipboard {
                data: "not base64!".into(),
            }),
        );
        assert_eq!(
            state.view.clipboard.as_deref(),
            Some("hello"),
            "invalid writes keep the pending one"
        );
        assert_eq!(
            state.view.clipboard_serial, 1,
            "invalid writes do not advance"
        );
    }
    #[test]
    fn palette_table_is_bounded() {
        assert_eq!(model::PALETTES.len(), 4);
        assert!(
            model::PALETTES
                .iter()
                .all(|palette| palette.ansi.len() == 16)
        );
        // An out-of-range theme never changes the active palette.
        assert!(!model::set_palette(200));
    }
    #[test]
    fn daemon_focus_move_is_followed_without_a_pending_request() {
        let mut state = State::default();
        assert!(
            apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 2, "p1"))).is_none()
        );
        state.view.pane_id = "p1".into();
        // A tab or workspace switch delivers a snapshot and surface focused on another pane.
        state.follow_focus = true;
        apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 2, "p2")));
        apply_event(&mut state, ClientEvent::Surface(surface("boot", 2, "p2")));
        project(&mut state);
        assert_eq!(state.view.pane_id, "p2");
        assert_eq!(state.view.state, "ready");
        assert!(!state.follow_focus, "a matched projection ends the follow");
        // Without a switch of our own, another client's focus move is ignored.
        state.follow_focus = false;
        apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 3, "p1")));
        apply_event(&mut state, ClientEvent::Surface(surface("boot", 3, "p1")));
        project(&mut state);
        assert_eq!(state.view.pane_id, "p2");
        // An in-flight explicit pane request still wins until acknowledged.
        pending(&mut state, "p1");
        apply_event(&mut state, ClientEvent::Surface(surface("boot", 2, "p2")));
        project(&mut state);
        assert_eq!(state.view.pane_id, "p2");
    }
    #[test]
    fn surface_style_emits_raw_cells_and_the_ring_bit() {
        let mut surface = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        // Indexed red fg (0x01000001) on the default background.
        surface.frame.cells[5].fg = 0x0100_0001;
        // Truecolor blue background with a bold, underlined foreground.
        surface.frame.cells[6].bg = 0x0200_0010;
        surface.frame.cells[6].modifier = model::BOLD | model::UNDERLINE;
        surface.frame.cursor = Some(CursorState {
            x: 1,
            y: 1,
            visible: true,
            shape: 0,
        });
        let style = surface_style(&surface);
        let parsed: serde_json::Value = serde_json::from_str(&style).unwrap();
        assert_eq!(parsed["rows"].as_array().unwrap().len(), 3);
        let row = &parsed["rows"][1];
        assert_eq!(row["t"], "xABx");
        let runs = row["r"].as_array().unwrap();
        assert_eq!(runs.len(), 4);
        // Border cells carry raw default colors plus the renderer-only ring bit,
        // so a theme switch repaints without a new surface.
        assert_eq!(runs[0][2], 0);
        assert_ne!(
            runs[0][4].as_u64().unwrap() & u64::from(model::FOCUS_RING),
            0
        );
        // The cursor cell keeps its raw color and gains the reverse bit.
        assert_eq!(runs[1][2], 0x0100_0001);
        assert_ne!(runs[1][4].as_u64().unwrap() & u64::from(model::REVERSED), 0);
        assert_eq!(runs[2][3], 0x0200_0010);
        assert_eq!(
            runs[2][4].as_u64().unwrap() & u64::from(model::BOLD | model::UNDERLINE),
            u64::from(model::BOLD | model::UNDERLINE)
        );
        // The model accepts exactly what the projection produces.
        let (rows, links) = model::parse_surface(&style).unwrap();
        assert_eq!(rows.len(), 3);
        assert!(links.is_empty(), "no hyperlinks in this fixture");
        // Resolution is a render-time step, so a palette changes these colors.
        let default = model::PALETTES[0];
        let latte = model::PALETTES[3];
        let cursor = rows[1].runs[1];
        assert_eq!(
            model::styled_colors(&cursor, &default).0,
            default.background
        );
        assert_eq!(model::styled_colors(&cursor, &default).1, default.ansi[1]);
        assert_eq!(model::styled_colors(&cursor, &latte).0, latte.background);
        let border = rows[1].runs[0];
        assert_eq!(model::styled_colors(&border, &default).0, default.accent);
        assert_eq!(model::styled_colors(&border, &latte).0, latte.accent);
    }
    #[test]
    fn surface_style_keeps_hidden_text_and_marks_split_borders() {
        let mut surface = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        surface.frame.cells[5].modifier = model::HIDDEN;
        let style = surface_style(&surface);
        let parsed: serde_json::Value = serde_json::from_str(&style).unwrap();
        let run = &parsed["rows"][1]["r"][1];
        assert_ne!(run[4].as_u64().unwrap() & u64::from(model::HIDDEN), 0);
        let (rows, _) = model::parse_surface(&style).unwrap();
        let hidden = rows[1].runs[1];
        let (fg, bg) = model::styled_colors(&hidden, &model::PALETTES[0]);
        assert_eq!(fg, bg, "hidden text never draws a visible glyph");
        // Pane geometry no longer decides what the frame projection shows.
        surface.panes[0].inner_rect.width = u16::MAX;
        assert!(!valid_geometry(&surface, &surface.panes[0]));
        assert_eq!(parsed["rows"].as_array().unwrap().len(), 3);
    }
    #[test]
    fn wide_glyphs_keep_later_cells_in_their_columns_without_changing_copy_text() {
        let mut surface = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        surface.frame.cells[5].symbol = "界".into();
        surface.frame.cells[5].fg = 0x0100_0002;
        surface.frame.cells[6].symbol = String::new();
        surface.frame.cells[6].skip = true;
        let style = surface_style(&surface);
        let (rows, _) = model::parse_surface(&style).unwrap();
        // Four cells, four glyph slots: the continuation cell is a blank that
        // carries the wide glyph's own style, so its background still fills.
        assert_eq!(rows[1].text, "x界 x");
        assert_eq!(rows[1].text.chars().count(), 4);
        let wide = rows[1].runs[1];
        assert_eq!(&rows[1].text[wide.start..wide.start + wide.len], "界 ");
        assert_eq!(wide.fg, 0x0100_0002);
        // Copy and the plain projection never gain the placeholder.
        let text = surface_text(&surface);
        assert_eq!(text.lines().nth(1), Some("x界x"));
        let snapshot = model::Snapshot::with_surface("t", &text, "s", "[]", Some(&style)).unwrap();
        assert_eq!(snapshot.lines[1].as_ref(), "x界x");
        assert_eq!(snapshot.widest_line_chars, 4);
    }
    #[test]
    fn surface_style_marks_the_daemon_frame_outside_every_pane_content() {
        // A 4x3 pane whose content is the middle two cells of row 1.
        let surface = surface("boot", 1, "p1");
        let (rows, _) = model::parse_surface(&surface_style(&surface)).unwrap();
        let framed = |row: &model::StyledRow| -> Vec<bool> {
            row.runs
                .iter()
                .flat_map(|run| {
                    let cells = row.text[run.start..run.start + run.len].chars().count();
                    std::iter::repeat_n(run.modifier & model::FRAME != 0, cells)
                })
                .collect()
        };
        assert_eq!(framed(&rows[0]), [true; 4]);
        assert_eq!(framed(&rows[1]), [true, false, false, true]);
        assert_eq!(framed(&rows[2]), [true; 4]);
    }
    #[test]
    fn surface_style_projects_bounded_openable_links() {
        let mut surface = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        surface.frame.hyperlinks =
            vec!["https://example.com/a".into(), "javascript:alert(1)".into()];
        surface.frame.cells[5].hyperlink = Some(0);
        surface.frame.cells[6].hyperlink = Some(1);
        let style = surface_style(&surface);
        let parsed: serde_json::Value = serde_json::from_str(&style).unwrap();
        // Only the http(s) target is exposed, with one shared index.
        assert_eq!(parsed["links"].as_array().unwrap().len(), 1);
        assert_eq!(parsed["links"][0], "https://example.com/a");
        let runs = parsed["rows"][1]["r"].as_array().unwrap();
        assert_eq!(runs[0][5], u32::MAX);
        assert_eq!(runs[1][5], 0);
        assert_eq!(runs[2][5], u32::MAX);
        let (rows, links) = model::parse_surface(&style).unwrap();
        assert_eq!(links, vec!["https://example.com/a".to_owned()]);
        assert_eq!(rows[1].runs[1].link, Some(0));
    }
    #[test]
    fn surface_rows_follow_the_frame_height_not_one_pane() {
        let mut surface = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        surface.panes[0].inner_rect.height = 2;
        let text = surface_text(&surface);
        assert_eq!(text, "xxxx\nxABx\nxxxx");
        let rendered = model::Snapshot::new("title", &text, "ready", "[]").unwrap();
        assert_eq!(rendered.lines.len(), 3);
    }
    #[test]
    fn authentication_policy_diagnostic_survives_client_error_boundary() {
        for transport in [
            herdr_remote_transport::Error::AuthenticationIncomplete,
            herdr_remote_transport::Error::AuthenticationMethodUnavailable {
                method: "publickey",
            },
        ] {
            let expected = transport.to_string();
            let error = herdr_client::Error::from(io::Error::other(Error::Transport(transport)));
            assert!(error.to_string().contains(&expected));
            let mut state = State::default();
            let reason = apply_event(
                &mut state,
                ClientEvent::Disconnected {
                    reason: error.to_string(),
                },
            );
            assert!(reason.unwrap().contains(&expected));
        }
    }

    #[test]
    fn focus_requires_correlated_ack_and_matching_projection() {
        let mut state = State::default();
        assert!(
            apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 1, "p1"))).is_none()
        );
        pending(&mut state, "p2");
        apply_event(
            &mut state,
            ClientEvent::Response {
                request_id: "unrelated".into(),
                response: json!({"result": {}}),
            },
        );
        assert!(!state.pending_focus.as_ref().unwrap().acknowledged);
        apply_event(&mut state, ClientEvent::Surface(surface("boot", 2, "p2")));
        apply_event(
            &mut state,
            ClientEvent::Response {
                request_id: "focus-1".into(),
                response: json!({"result": {"type": "ok"}}),
            },
        );
        assert!(state.view.pane_id.is_empty());
        assert!(state.view.text.is_empty());
        apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 2, "p2")));
        assert!(state.pending_focus.is_none());
        assert_eq!(state.view.pane_id, "p2");
        assert_eq!(state.view.text, "xxxx\nxABx\nxxxx");
        assert_eq!(state.view.state, "ready");
        // The daemon's focus moved elsewhere after our own focus completed.
        // The client stays on the pane it was told to focus and keeps its text,
        // rather than blanking the pane the user is reading.
        let moved = apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 3, "p1")));
        assert!(moved.is_none());
        assert_eq!(state.view.pane_id, "p2");
        assert_eq!(state.view.text, "xxxx\nxABx\nxxxx");
    }
    #[test]
    fn boot_change_and_deleted_pane_fail_closed() {
        let mut state = State::default();
        apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 1, "p1")));
        assert!(
            apply_event(
                &mut state,
                ClientEvent::Snapshot(snapshot("new-boot", 2, "p1"))
            )
            .is_some()
        );
        assert!(
            apply_event(
                &mut state,
                ClientEvent::Surface(surface("new-boot", 1, "p1"))
            )
            .is_some()
        );
        state.view.pane_id = "deleted".into();
        assert!(
            apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 2, "p1"))).is_some()
        );
    }
    #[test]
    fn failed_or_malformed_focus_response_cannot_activate_input() {
        for response in [
            json!({"error":{"message":"no"}}),
            json!({}),
            json!({"result":null}),
        ] {
            let mut state = State::default();
            apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 1, "p1")));
            pending(&mut state, "p1");
            assert!(
                apply_event(
                    &mut state,
                    ClientEvent::Response {
                        request_id: "focus-1".into(),
                        response
                    }
                )
                .is_some()
            );
            assert!(state.view.pane_id.is_empty());
        }
    }
    #[test]
    fn frame_text_filters_escapes_and_skips_continuation_cells() {
        let mut surface = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        surface.frame.cells[5].symbol = "\u{1b}A\n".into();
        surface.frame.cells[6].skip = true;
        assert_eq!(surface_text(&surface), "xxxx\nxAx\nxxxx");
        // Pane geometry no longer decides what the frame projection shows.
        surface.panes[0].inner_rect.width = u16::MAX;
        assert!(!valid_geometry(&surface, &surface.panes[0]));
        assert_eq!(surface_text(&surface), "xxxx\nxAx\nxxxx");
    }
    #[test]
    fn submit_is_one_text_and_enter_batch_and_keys_are_semantic() {
        let batch = command_events(
            model::parse_command(r#"{"type":"submit","text":"git status"}"#).unwrap(),
        )
        .unwrap();
        assert_eq!(batch.len(), 2);
        assert_eq!(
            batch[0],
            ClientPaneInputEvent::TextCommit("git status".into())
        );
        assert!(matches!(
            batch[1],
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::Enter,
                ..
            }
        ));
        assert!(matches!(
            key_event("ctrl+f").unwrap(),
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::Char('f'),
                modifiers: 2,
                ..
            }
        ));
        assert!(matches!(
            key_event("alt+f").unwrap(),
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::Char('f'),
                modifiers: 4,
                ..
            }
        ));
        let keys = command_events(
            model::parse_command(r#"{"type":"keys","keys":["ctrl+c","alt+x","shift+tab","f12"]}"#)
                .unwrap(),
        )
        .unwrap();
        assert!(matches!(
            keys[0],
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::Char('c'),
                modifiers: 2,
                ..
            }
        ));
        assert!(matches!(
            keys[1],
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::Char('x'),
                modifiers: 4,
                ..
            }
        ));
        assert!(matches!(
            keys[2],
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::BackTab,
                ..
            }
        ));
        assert!(matches!(
            keys[3],
            ClientPaneInputEvent::Key {
                code: ClientKeyCode::F(12),
                ..
            }
        ));
        assert!(model::parse_command(r#"{"type":"keys","keys":["\u001b[31m"]}"#).is_err());
    }
    #[test]
    fn credential_schema_has_one_explicit_mode_and_strict_limits() {
        assert!(parse_credentials(r#"{"password":"fixture"}"#).is_ok());
        assert!(parse_credentials(r#"{"private_key":"fixture-key"}"#).is_ok());
        assert!(
            parse_credentials(r#"{"private_key":"fixture-key","passphrase":"fixture"}"#).is_ok()
        );
        for payload in [
            r#"{}"#,
            r#"{"password":""}"#,
            r#"{"private_key":""}"#,
            r#"{"password":"fixture","private_key":"key"}"#,
            r#"{"password":"fixture","passphrase":"unused"}"#,
            r#"{"password":"fixture","unexpected":1}"#,
        ] {
            assert!(parse_credentials(payload).is_err());
        }
        assert!(parse_credentials(&json!({"password": "x".repeat(4097)}).to_string()).is_err());
        assert!(parse_credentials(&json!({"private_key": "x".repeat(65537)}).to_string()).is_err());
    }
    #[test]
    fn maximum_picker_and_surface_remain_inside_jni_state_limit() {
        let view = View {
            panes: (0..MAX_PICKER)
                .map(|_| Pane {
                    id: "p".repeat(128),
                    title: clean(&"\\".repeat(160), 160),
                    workspace_id: "w".repeat(64),
                    tab_id: "t".repeat(64),
                    status: "working",
                })
                .collect(),
            text: "\\".repeat(MAX_DISPLAY),
            workspaces: (0..MAX_CHROME)
                .map(|_| Workspace {
                    id: "w".repeat(64),
                    label: clean(&"\\".repeat(80), 80),
                    focused: false,
                    status: "working",
                })
                .collect(),
            tabs: (0..MAX_CHROME)
                .map(|_| Tab {
                    id: "t".repeat(64),
                    workspace_id: "w".repeat(64),
                    label: clean(&"\\".repeat(80), 80),
                    focused: false,
                    zoomed: false,
                    status: "blocked",
                })
                .collect(),
            ..Default::default()
        };
        assert!(serde_json::to_string(&view).unwrap().len() < 1_048_576);
        assert_eq!(clean("😃😃😃", 9), "😃😃");
    }
    #[test]
    fn config_is_strict_and_does_not_accept_shell_arguments() {
        let valid = r#"{"transport":"tcp","host":"localhost","port":22,"username":"fixture","session":"main","expected_fingerprint":"SHA256:fixture"}"#;
        assert!(parse_config(valid).is_ok());
        assert!(parse_config(&valid.replace("\"main\"", "\"../../escape\"")).is_err());
        assert!(parse_config(&valid.replace("\"port\":22", "\"port\":0")).is_err());
        assert!(
            parse_config(&valid.replace("\"port\":22", "\"port\":22,\"command\":\"unexpected\""))
                .is_err()
        );
        assert!(parse_config(&" ".repeat(MAX_CONFIG + 1)).is_err());
    }
    #[test]
    fn missing_host_pin_returns_handle_then_closes_without_deadlock() {
        let config =
            r#"{"transport":"iroh","endpoint_id":"fixture","username":"fixture","session":"main"}"#;
        let id = connect(config, "fixture-unused-password".into()).unwrap();
        let start = Instant::now();
        loop {
            let state: serde_json::Value =
                serde_json::from_str(&poll(id, 0, false).unwrap()).unwrap();
            if state["state"] == "closed" {
                break;
            }
            assert!(
                start.elapsed() < Duration::from_secs(3),
                "worker did not close"
            );
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(matches!(
            input(id, "p1", r#"{"type":"text","text":"x"}"#),
            Err(Error::Closed)
        ));
        close(id);
        assert!(matches!(poll(id, 0, false), Err(Error::Closed)));
        close(id);
    }

    #[test]
    fn host_theme_tells_herdr_the_colors_cells_are_painted_with() {
        let rgb = |value: u32| {
            let [_, r, g, b] = value.to_be_bytes();
            ClientHostColor { r, g, b }
        };
        for palette in model::PALETTES {
            let theme = host_theme(&palette);
            assert_eq!(theme.foreground, rgb(palette.foreground));
            assert_eq!(theme.background, rgb(palette.background));
            assert_eq!(theme.palette[..16], palette.ansi.map(rgb));
            // xterm's colour cube and grey ramp follow the palette's sixteen.
            assert_eq!(theme.palette[16], rgb(0x00_0000));
            assert_eq!(theme.palette[196], rgb(0xff_0000));
            assert_eq!(theme.palette[231], rgb(0xff_ffff));
            assert_eq!(theme.palette[232], rgb(0x08_0808));
            assert_eq!(theme.palette[255], rgb(0xee_eeee));
            let light = theme.appearance == ClientHostAppearance::Light;
            assert_eq!(light, palette.name == "Latte", "{}", palette.name);
        }
    }
}
