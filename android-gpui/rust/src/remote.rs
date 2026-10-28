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

const MAX_HANDLES: usize = 4;
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
fn parse_config(raw: &str) -> Result<(Config, String)> {
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

#[derive(Clone, Serialize)]
pub struct Pane {
    id: String,
    title: String,
    workspace_id: String,
    tab_id: String,
    status: &'static str,
}
#[derive(Clone, Serialize)]
pub struct Workspace {
    id: String,
    label: String,
    focused: bool,
    status: &'static str,
}
#[derive(Clone, Serialize)]
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
            text: String::new(),
            surface: String::new(),
            clipboard: None,
            clipboard_serial: 0,
            notifications: Vec::new(),
            bell: 0,
            scroll: Scroll::default(),
            revision: 0,
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

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WireCredentials {
    password: Option<Zeroizing<String>>,
    private_key: Option<Zeroizing<String>>,
    passphrase: Option<Zeroizing<String>>,
}
fn parse_credentials(raw: &str) -> Result<Credentials> {
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
    begin(raw, parse_credentials(credential_json)?)
}
fn begin(raw: &str, credentials: Credentials) -> Result<u64> {
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
            if let Err(error) = run(&worker, config, credentials, name) {
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
pub fn poll(id: u64) -> Result<String> {
    let json = {
        let owner = session(id)?;
        let mut state = lock(&owner.state);
        let json = serde_json::to_string(&state.view)?;
        // Clipboard writes, notifications and bells are delivered exactly once,
        // so they never inflate every later poll with the same payload.
        state.view.clipboard = None;
        state.view.notifications.clear();
        state.view.bell = 0;
        json
    };
    if json.len() > 1_048_576 {
        return Err(Error::StateLimit);
    }
    Ok(json)
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

fn run(owner: &Arc<Session>, config: Config, credentials: Credentials, name: String) -> Result<()> {
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
        herdr_client::connect_with_remote_connector(Default::default(), true, move |stop| {
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
    while !owner.cancel.is_cancelled() && !client.handle.is_disconnected() {
        if let Ok(event) = client.events.recv_timeout(Duration::from_millis(25)) {
            let failure = apply_event(&mut lock(&owner.state), event);
            if let Some(reason) = failure {
                finish(owner, &reason);
                break;
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
                state.view.notifications.retain(|notice| notice.pane_id != pane_id);
            }
            if state.view.notifications.len() >= MAX_NOTICES {
                state.view.notifications.remove(0);
            }
            state.view.notifications.push(Notice { title, body, pane_id });
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
    state.view.text.clear();
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
    let projection = matching_surface(state, &pane_id).map(|(surface, _pane)| {
        (
            surface_text(surface),
            surface_style(surface),
            surface.surface_revision,
        )
    });
    if let Some((text, surface, revision)) = projection {
        state.view.text = text;
        state.view.surface = surface;
        state.view.revision = revision;
        state.view.scroll = matching_surface(state, &pane_id)
            .and_then(|(_, pane)| pane.scroll)
            .filter(|metrics| metrics.viewport_rows > 0)
            .map(|metrics| Scroll {
                offset: metrics.offset_from_bottom.min(metrics.max_offset_from_bottom),
                max: metrics.max_offset_from_bottom,
                rows: metrics.viewport_rows,
            })
            .unwrap_or_default();
    } else if !state.view.pane_id.is_empty() {
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

fn cell_style(cell: &CellData, cursor: bool, focused_border: bool) -> (u32, u32, u16) {
    let mut modifier = cell.modifier;
    if cursor {
        modifier |= model::REVERSED;
    }
    if focused_border {
        modifier |= model::FOCUS_RING;
    }
    (cell.fg, cell.bg, modifier)
}

/// Builds the bounded row/run projection the renderer paints for the whole
/// surface frame, so splits and their borders are painted together. Returns an
/// empty string when the frame cannot be represented within the payload budget,
/// leaving the plain text path authoritative.
fn surface_style(surface: &PaneSurfaceFrame) -> String {
    let frame = &surface.frame;
    let cursor = frame.cursor.as_ref().filter(|cursor| cursor.visible);
    let mut links: Vec<&str> = Vec::new();
    let mut link_index: Vec<Option<u16>> = vec![None; frame.hyperlinks.len()];
    let focused = surface.panes.iter().find(|pane| pane.focused);
    let mut rows = Vec::new();
    for y in 0..frame.height.min(model::MAX_LINES as u16) {
        let mut text = String::new();
        let mut runs: Vec<(usize, usize, u32, u32, u16, u32)> = Vec::new();
        for x in 0..frame.width {
            let Some(cell) = frame
                .cells
                .get(usize::from(y) * usize::from(frame.width) + usize::from(x))
            else {
                return String::new();
            };
            if cell.skip {
                continue;
            }
            let start = text.len();
            for ch in cell.symbol.chars().filter(|ch| !ch.is_control()) {
                if text.len() + ch.len_utf8() > model::MAX_LINE_CHARS * 4 {
                    break;
                }
                text.push(ch);
            }
            if text.len() == start {
                continue;
            }
            let on_cursor = cursor.is_some_and(|cursor| cursor.x == x && cursor.y == y);
            let focused_border = focused
                .is_some_and(|pane| inside(pane.rect, x, y) && !inside(pane.inner_rect, x, y));
            let (fg, bg, modifier) = cell_style(cell, on_cursor, focused_border);
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
            let full = runs.len() >= model::MAX_RUNS_PER_LINE;
            match runs.last_mut() {
                Some(run) if run.2 == fg && run.3 == bg && run.4 == modifier && run.5 == link.map_or(u32::MAX, u32::from) => {
                    run.1 = text.len() - run.0;
                }
                _ if full => {
                    if let Some(run) = runs.last_mut() {
                        run.1 = text.len() - run.0;
                    }
                }
                _ => runs.push((start, text.len() - start, fg, bg, modifier, link.map_or(u32::MAX, u32::from))),
            }
        }
        rows.push((text, runs));
    }
    let json = json!({
        "links": links,
        "rows": rows
            .iter()
            .map(|(text, runs)| json!({
                "t": text,
                "r": runs.iter().map(|run| json!([run.0, run.1, run.2, run.3, run.4, run.5])).collect::<Vec<_>>(),
            }))
            .collect::<Vec<_>>(),
    })
    .to_string();
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
        "tab.new" => (Method::TabCreate, json!({"workspace_id": workspace, "focus": true})),
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
    if value.is_empty() || value.len() > 1024 || !known(state.snapshot.as_ref().ok_or(Error::StaleTarget)?)
    {
        return Err(Error::StaleTarget);
    }
    state
        .client
        .as_ref()
        .ok_or(Error::Closed)?
        .request(&state.view.boot_id, method, json!({ field: value }))?;
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
    #[test]
    fn scroll_offsets_stay_inside_the_pane_window() {
        assert_eq!(clamp_offset(-5, 100), 0, "never scroll past the live view");
        assert_eq!(clamp_offset(0, 100), 0);
        assert_eq!(clamp_offset(40, 100), 40);
        assert_eq!(clamp_offset(1_000, 100), 100, "bounded by the pane's scrollback");
        assert_eq!(clamp_offset(i64::MAX, 0), 0);
    }
    #[test]
    fn session_actions_are_allowlisted() {
        // The allowlist is checked before any session lookup, so an unknown
        // method can never reach the daemon.
        assert!(matches!(session_action(1, "pane.clear"), Err(Error::Config)));
        assert!(matches!(session_action(1, "command.invoke"), Err(Error::Config)));
        assert!(matches!(session_action(1, ""), Err(Error::Config)));
        // A stale handle fails closed for an allowed action.
        assert!(matches!(session_action(999_999, "tab.new"), Err(Error::Closed)));
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
        assert_eq!(state.view.notifications.len(), MAX_NOTICES, "the queue stays bounded");
        assert_eq!(
            state.view.notifications.last().unwrap().title,
            "many".to_owned()
        );
        // An empty title is dropped; a hostile pane id never reaches the activity.
        apply_event(&mut state, notice(Some("p1"), "   "));
        apply_event(&mut state, notice(Some("bad id!"), "hostile"));
        assert_eq!(state.view.notifications.last().unwrap().pane_id, "", "invalid pane ids are dropped");
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
            assert!(decode_osc52(rejected).is_none(), "{rejected} must be dropped");
        }
        // Raw bytes that are not UTF-8 never reach the pasteboard.
        assert!(decode_osc52("/w==").is_none(), "0xff is not UTF-8");
        let oversized = "A".repeat(MAX_CLIPBOARD_TEXT.div_ceil(3) * 4 + 8);
        assert!(decode_osc52(&oversized).is_none(), "oversized payloads are dropped");
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
        assert_eq!(state.view.clipboard.as_deref(), Some("hello"), "invalid writes keep the pending one");
        assert_eq!(state.view.clipboard_serial, 1, "invalid writes do not advance");
    }
    #[test]
    fn palette_table_is_bounded() {
        assert_eq!(model::PALETTES.len(), 4);
        assert!(model::PALETTES.iter().all(|palette| palette.ansi.len() == 16));
        // An out-of-range theme never changes the active palette.
        assert!(!model::set_palette(200));
    }
    #[test]
    fn daemon_focus_move_is_followed_without_a_pending_request() {
        let mut state = State::default();
        assert!(apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 2, "p1"))).is_none());
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
        assert_ne!(runs[0][4].as_u64().unwrap() & u64::from(model::FOCUS_RING), 0);
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
        assert_eq!(model::styled_colors(&cursor, &default).0, default.background);
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
    fn surface_style_projects_bounded_openable_links() {
        let mut surface = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        surface.frame.hyperlinks = vec![
            "https://example.com/a".into(),
            "javascript:alert(1)".into(),
        ];
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
        apply_event(&mut state, ClientEvent::Snapshot(snapshot("boot", 3, "p1")));
        assert!(state.view.text.is_empty());
        assert_eq!(state.view.state, "connecting");
        assert!(matching_surface(&state, "p2").is_none());
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
            let state: serde_json::Value = serde_json::from_str(&poll(id).unwrap()).unwrap();
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
        assert!(matches!(poll(id), Err(Error::Closed)));
        close(id);
    }
}
