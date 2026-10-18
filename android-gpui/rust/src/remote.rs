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
}
#[derive(Clone, Serialize)]
pub struct View {
    state: &'static str,
    status: String,
    boot_id: String,
    panes: Vec<Pane>,
    pane_id: String,
    text: String,
    revision: u64,
}
impl Default for View {
    fn default() -> Self {
        Self {
            state: "connecting",
            status: "Connecting over SSH".into(),
            boot_id: String::new(),
            panes: Vec::new(),
            pane_id: String::new(),
            text: String::new(),
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
    let json = serde_json::to_string(&lock(&session(id)?.state).view)?;
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
            state.view.panes = snapshot
                .panes
                .iter()
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
    if let Some((surface, pane)) = matching_surface(state, &state.view.pane_id) {
        let text = surface_text(surface, pane);
        let revision = surface.surface_revision;
        state.view.text = text;
        state.view.revision = revision;
    } else if !state.view.pane_id.is_empty() {
        state.view.state = "connecting";
        state.view.status = "Waiting for the selected pane surface".into();
    }
}

fn valid_pane_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'-' | b':'))
}
fn valid_geometry(surface: &PaneSurfaceFrame, pane: &PaneSurfacePane) -> bool {
    let rect = pane.inner_rect;
    u32::from(rect.x) + u32::from(rect.width) <= u32::from(surface.frame.width)
        && u32::from(rect.y) + u32::from(rect.height) <= u32::from(surface.frame.height)
}
fn surface_text(surface: &PaneSurfaceFrame, pane: &PaneSurfacePane) -> String {
    let rect = pane.inner_rect;
    let frame = &surface.frame;
    if u32::from(rect.x) + u32::from(rect.width) > u32::from(frame.width)
        || u32::from(rect.y) + u32::from(rect.height) > u32::from(frame.height)
    {
        return String::new();
    }
    let mut text = String::new();
    for y in rect.y
        ..rect
            .y
            .saturating_add(rect.height)
            .min(rect.y.saturating_add(model::MAX_LINES as u16))
    {
        // Separators belong between rows. A final separator would make the
        // snapshot renderer invent one extra row and scroll the first away.
        if y != rect.y {
            text.push('\n');
        }
        let mut columns = 0;
        for x in rect.x..rect.x.saturating_add(rect.width) {
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
        || !state.view.panes.iter().any(|pane| pane.id == pane_id)
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
        assert_eq!(state.view.text, "AB");
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
    fn only_inner_pane_cells_reach_bounded_plain_text() {
        let mut surface = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        surface.frame.cells[5].symbol = "\u{1b}A\n".into();
        surface.frame.cells[6].skip = true;
        assert_eq!(surface_text(&surface, &surface.panes[0]), "A");
        surface.panes[0].inner_rect.width = u16::MAX;
        assert!(!valid_geometry(&surface, &surface.panes[0]));
        assert_eq!(surface_text(&surface, &surface.panes[0]), "");
    }
    #[test]
    fn surface_rows_do_not_add_a_phantom_renderer_row() {
        let mut surface = Arc::unwrap_or_clone(surface("boot", 1, "p1"));
        surface.panes[0].inner_rect.height = 2;
        surface.frame.cells[9].symbol = " ".into();
        surface.frame.cells[10].symbol = " ".into();
        let text = surface_text(&surface, &surface.panes[0]);
        assert_eq!(text, "AB\n  ");
        let rendered = model::Snapshot::new("title", &text, "ready", "[]").unwrap();
        assert_eq!(rendered.lines.len(), 2);
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
            panes: (0..MAX_PANES)
                .map(|_| Pane {
                    id: "p".repeat(128),
                    title: clean(&"\\".repeat(160), 160),
                })
                .collect(),
            text: "\\".repeat(MAX_DISPLAY),
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
