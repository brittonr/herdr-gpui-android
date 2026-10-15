//! Loopback-only synthetic Herdr protocol peer. Never executes commands.
//! It shares the production wire schema and supports bounded adapter testing.
use herdr_protocol::{endpoint::*, *};
use russh::{Channel, ChannelId, keys::HashAlg, server};
use serde::Serialize;
use serde_json::json;
use std::{
    collections::{HashMap, HashSet},
    future::Future,
    io,
    pin::Pin,
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
    task::{Context, Poll},
    time::{Duration, Instant},
};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio_util::sync::CancellationToken;

pub const SESSION: &str = "android-test";
pub const USERNAME: &str = "synthetic-fixture";
/// Public test data, never an account credential.
pub const PASSWORD: &str = "public-synthetic-test-password";
pub const PANE: &str = "w1:p1";
pub const BOOT: &str = "synthetic-ssh-fixture-boot";
const MAX_CONNECTIONS: usize = 8;
const MAX_CHANNELS: usize = 4;
const MAX_RECORDS: usize = 64;
const MAX_INPUT_BYTES: usize = 65_536;
const WELCOME: &str =
    include_str!("../../../crates/herdr-protocol/tests/fixtures/endpoint-welcome-v1.json");
const SNAPSHOT: &str =
    include_str!("../../../crates/herdr-protocol/tests/fixtures/endpoint-snapshot-v1.json");

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error(transparent)]
    Io(#[from] io::Error),
    #[error(transparent)]
    Ssh(#[from] russh::Error),
    #[error(transparent)]
    Key(#[from] russh::keys::ssh_key::Error),
    #[error(transparent)]
    Protocol(#[from] herdr_protocol::Error),
    #[error(transparent)]
    Client(#[from] herdr_client::Error),
    #[error(transparent)]
    Json(#[from] serde_json::Error),
    #[error(transparent)]
    Join(#[from] tokio::task::JoinError),
    #[error("synthetic fixture shutdown timed out")]
    Shutdown,
    #[error("synthetic fixture rejected invalid or oversized protocol input")]
    InvalidInput,
}
pub type Result<T> = std::result::Result<T, Error>;

/// Fixture-only diagnostics: bounded static classifications, never wire payloads,
/// usernames, credentials, keys, remote error text, or shell commands.
#[derive(Clone)]
struct Diagnostics(Arc<AtomicUsize>);
impl Diagnostics {
    fn emit(
        &self,
        connection: usize,
        started: Instant,
        phase: &'static str,
        error: Option<&Error>,
    ) {
        if self.0.fetch_add(1, Ordering::Relaxed) >= 256 {
            return;
        }
        let mut message = json!({"synthetic": true, "connection": connection,
            "elapsed_ms": started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64,
            "phase": phase});
        if let Some(error) = error {
            message["error"] = error_category(error);
        }
        eprintln!("{message}");
    }
}
fn error_category(error: &Error) -> serde_json::Value {
    match error {
        Error::Ssh(error) => {
            use russh::Error as Ssh;
            let category = match error {
                Ssh::CouldNotReadKey => "CouldNotReadKey",
                Ssh::KexInit => "KexInit",
                Ssh::UnknownAlgo => "UnknownAlgo",
                Ssh::NoCommonAlgo { kind, .. } => {
                    return json!({"class":"NoCommonAlgo", "algorithm_kind": format!("{kind:?}")});
                }
                Ssh::Version => "Version",
                Ssh::Kex => "Kex",
                Ssh::PacketAuth => "PacketAuth",
                Ssh::Inconsistent => "Inconsistent",
                Ssh::NotAuthenticated => "NotAuthenticated",
                Ssh::UnsupportedAuthMethod => "UnsupportedAuthMethod",
                Ssh::IndexOutOfBounds => "IndexOutOfBounds",
                Ssh::UnknownKey => "UnknownKey",
                Ssh::WrongServerSig => "WrongServerSig",
                Ssh::PacketSize(size) => return json!({"class":"PacketSize", "size": size}),
                Ssh::WrongChannel => "WrongChannel",
                Ssh::ChannelOpenFailure(_) => "ChannelOpenFailure",
                Ssh::Disconnect => "Disconnect",
                Ssh::NoHomeDir => "NoHomeDir",
                Ssh::KeyChanged { .. } => "KeyChanged",
                Ssh::HUP => "HUP",
                Ssh::ConnectionTimeout => "ConnectionTimeout",
                Ssh::KeepaliveTimeout => "KeepaliveTimeout",
                Ssh::InactivityTimeout => "InactivityTimeout",
                Ssh::NoAuthMethod => "NoAuthMethod",
                Ssh::SendError => "SendError",
                Ssh::Pending => "Pending",
                Ssh::DecryptionError => "DecryptionError",
                Ssh::RequestDenied => "RequestDenied",
                Ssh::Keys(_) => "Keys",
                Ssh::IO(error) => return io_category(error),
                Ssh::Utf8(_) => "Utf8",
                Ssh::Join(_) => "Join",
                Ssh::Elapsed(_) => "Elapsed",
                Ssh::StrictKeyExchangeViolation {
                    message_type,
                    sequence_number,
                } => {
                    return json!({
                    "class":"StrictKeyExchangeViolation", "message_type":message_type, "sequence_number":sequence_number});
                }
                Ssh::Signature(_) => "Signature",
                Ssh::SshKey(_) => "SshKey",
                Ssh::SshEncoding(_) => "SshEncoding",
                Ssh::InvalidConfig(_) => "InvalidConfig",
                Ssh::RecvError => "RecvError",
            };
            json!({"class":category})
        }
        Error::Io(error) => io_category(error),
        Error::Key(_) => json!({"class":"FixtureKey"}),
        Error::Protocol(_) => json!({"class":"FixtureProtocol"}),
        Error::Client(_) => json!({"class":"FixtureClient"}),
        Error::Json(_) => json!({"class":"FixtureJson"}),
        Error::Join(_) => json!({"class":"FixtureJoin"}),
        Error::Shutdown => json!({"class":"FixtureShutdown"}),
        Error::InvalidInput => json!({"class":"FixtureInvalidInput"}),
    }
}
fn io_category(error: &io::Error) -> serde_json::Value {
    json!({"class":"Io", "kind":format!("{:?}", error.kind()), "os_code":error.raw_os_error()})
}

#[derive(Clone, Debug, Serialize)]
pub struct ConnectionInfo {
    pub synthetic: bool,
    pub transport: &'static str,
    pub host: &'static str,
    pub port: u16,
    pub expected_fingerprint: String,
    pub username: &'static str,
    pub password: &'static str,
    pub session: &'static str,
}
impl ConnectionInfo {
    /// The adapter's strict configuration excludes the synthetic metadata and password.
    pub fn adapter_config(&self) -> String {
        json!({"transport": self.transport, "host": self.host, "port": self.port,
            "expected_fingerprint": self.expected_fingerprint, "username": self.username,
            "session": self.session})
        .to_string()
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Record {
    Probe,
    Bridge,
    Hello,
    Focus(String),
    Input(Vec<ClientPaneInputEvent>),
    HealthPing,
    RejectedCommand,
}
fn push(records: &Mutex<Vec<Record>>, value: Record) {
    let mut records = records
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if records.len() == MAX_RECORDS {
        records.remove(0);
    }
    records.push(value);
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Scenario {
    #[default]
    Normal,
    /// Accept SSH exec but withhold bridge readiness until the client cancels.
    StallBridge,
}
pub struct Fixture {
    pub info: ConnectionInfo,
    records: Arc<Mutex<Vec<Record>>>,
    active: Arc<AtomicUsize>,
    stop: CancellationToken,
    task: Option<tokio::task::JoinHandle<()>>,
}
impl Fixture {
    pub async fn start(port: u16) -> Result<Self> {
        Self::start_with_scenario(port, Scenario::Normal).await
    }
    pub async fn start_with_scenario(port: u16, scenario: Scenario) -> Result<Self> {
        let key =
            russh::keys::PrivateKey::random(&mut rand::rng(), russh::keys::Algorithm::Ed25519)?;
        let fingerprint = key.public_key().fingerprint(HashAlg::Sha256).to_string();
        let config = Arc::new(server::Config {
            keys: vec![key],
            auth_rejection_time: Duration::ZERO,
            auth_rejection_time_initial: Some(Duration::ZERO),
            inactivity_timeout: Some(Duration::from_secs(60)),
            ..Default::default()
        });
        let listener = tokio::net::TcpListener::bind(("127.0.0.1", port)).await?;
        let port = listener.local_addr()?.port();
        let commands = Arc::new(Commands {
            probe: herdr_client::remote_probe_command(SESSION)?,
            bridge: herdr_client::remote_bridge_command(SESSION)?,
        });
        let records = Arc::new(Mutex::new(Vec::new()));
        let task_records = records.clone();
        let active = Arc::new(AtomicUsize::new(0));
        let task_active = active.clone();
        let stop = CancellationToken::new();
        let task_stop = stop.clone();
        let diagnostics = Diagnostics(Arc::new(AtomicUsize::new(0)));
        let task = tokio::spawn(async move {
            let mut connection_id = 0usize;
            let mut sessions = tokio::task::JoinSet::new();
            loop {
                tokio::select! {
                    biased;
                    _ = task_stop.cancelled() => break,
                    _ = sessions.join_next(), if !sessions.is_empty() => {},
                    accepted = listener.accept() => {
                        let Ok((stream, _)) = accepted else { break };
                        if sessions.len() >= MAX_CONNECTIONS { continue; }
                        task_active.fetch_add(1, Ordering::SeqCst);
                        connection_id = connection_id.saturating_add(1);
                        let connection = connection_id;
                        let started = Instant::now();
                        let diagnostics = diagnostics.clone();
                        diagnostics.emit(connection, started, "tcp_accepted", None);
                        let handler = Peer { active: task_active.clone(), scenario, commands: commands.clone(), records: task_records.clone(), channels: HashMap::new(), open: HashSet::new() };
                        let config = config.clone();
                        let stop = task_stop.clone();
                        sessions.spawn(async move {
                            let stream = CancelStream { stream, cancelled: Box::pin(stop.clone().cancelled_owned()) };
                            let mut running = match server::run_stream(config, stream, handler).await {
                                Ok(running) => running,
                                Err(error) => {
                                    diagnostics.emit(connection, started, "ssh_banner_failed", Some(&error));
                                    return;
                                },
                            };
                            diagnostics.emit(connection, started, "ssh_banner_accepted", None);
                            {
                                let handle = running.handle();
                                tokio::select! {
                                    _ = stop.cancelled() => {
                                        let _ = handle.disconnect(russh::Disconnect::ByApplication, "synthetic fixture finished".into(), "".into()).await;
                                        let _ = tokio::time::timeout(Duration::from_secs(1), &mut running).await;
                                    },
                                    _ = tokio::time::sleep(Duration::from_secs(3600)) => {
                                        let _ = handle.disconnect(russh::Disconnect::ByApplication, "synthetic fixture session limit".into(), "".into()).await;
                                    },
                                    result = &mut running => {
                                        diagnostics.emit(connection, started, "ssh_session_ended", result.as_ref().err());
                                    },
                                }
                            }
                        });
                    }
                }
            }
            // Only these accepted tasks and this listener are owned by the fixture.
            drop(listener);
            while sessions.join_next().await.is_some() {}
        });
        Ok(Self {
            info: ConnectionInfo {
                synthetic: true,
                transport: "tcp",
                host: "127.0.0.1",
                port,
                expected_fingerprint: fingerprint,
                username: USERNAME,
                password: PASSWORD,
                session: SESSION,
            },
            records,
            active,
            stop,
            task: Some(task),
        })
    }
    pub fn active_connections(&self) -> usize {
        self.active.load(Ordering::SeqCst)
    }
    pub fn records(&self) -> Vec<Record> {
        self.records
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone()
    }
    pub async fn shutdown(mut self) -> Result<()> {
        self.stop.cancel();
        if let Some(task) = self.task.take() {
            tokio::time::timeout(Duration::from_secs(2), task)
                .await
                .map_err(|_| Error::Shutdown)??;
        }
        Ok(())
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        self.stop.cancel();
    }
}
struct Commands {
    probe: String,
    bridge: String,
}
struct Peer {
    active: Arc<AtomicUsize>,
    scenario: Scenario,
    commands: Arc<Commands>,
    records: Arc<Mutex<Vec<Record>>>,
    channels: HashMap<ChannelId, Wire>,
    open: HashSet<ChannelId>,
}
impl Drop for Peer {
    fn drop(&mut self) {
        self.active.fetch_sub(1, Ordering::SeqCst);
    }
}
// russh owns its background task, so cancellation must reach the actual socket.
struct CancelStream {
    stream: tokio::net::TcpStream,
    cancelled: Pin<Box<dyn Future<Output = ()> + Send>>,
}
impl AsyncRead for CancelStream {
    fn poll_read(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        if self.cancelled.as_mut().poll(cx).is_ready() {
            return Poll::Ready(Ok(()));
        }
        Pin::new(&mut self.stream).poll_read(cx, buf)
    }
}
impl AsyncWrite for CancelStream {
    fn poll_write(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bytes: &[u8],
    ) -> Poll<io::Result<usize>> {
        if self.cancelled.as_mut().poll(cx).is_ready() {
            return Poll::Ready(Err(io::ErrorKind::BrokenPipe.into()));
        }
        Pin::new(&mut self.stream).poll_write(cx, bytes)
    }
    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_flush(cx)
    }
    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.stream).poll_shutdown(cx)
    }
}
#[derive(PartialEq, Eq)]
enum Phase {
    Choice,
    Hello,
    Active,
}
struct Wire {
    phase: Phase,
    buffer: Vec<u8>,
    projection: u64,
    surface: u64,
    focused: bool,
    lines: Vec<String>,
}
impl Default for Wire {
    fn default() -> Self {
        Self {
            phase: Phase::Choice,
            buffer: Vec::new(),
            projection: 7,
            surface: 1,
            focused: false,
            lines: vec![
                "SYNTHETIC SSH FIXTURE".into(),
                "Generation 1 protocol test peer".into(),
                "Select this pane to test semantic input".into(),
            ],
        }
    }
}
fn status() -> String {
    json!({"synthetic":true, "endpoint_protocol_generation":1,
        "endpoint_capabilities":["surface_interest","presentation_effects_fence","health_check"],
        "remote_bridge_idle_timeout":true})
    .to_string()
}
fn send(session: &mut server::Session, channel: ChannelId, message: ServerMessage) -> Result<()> {
    session.data(channel, encode_message(&message, MAX_FRAME_SIZE)?)?;
    Ok(())
}
fn snapshot(revision: u64) -> Result<ClientShellSnapshot> {
    let mut snapshot: ClientShellSnapshot = serde_json::from_str(SNAPSHOT)?;
    snapshot.boot_id = BOOT.into();
    snapshot.revision = revision;
    snapshot.config_diagnostic = Some("SYNTHETIC SSH FIXTURE: not a real Herdr daemon".into());
    snapshot.product_announcement = None;
    snapshot.update_available = None;
    snapshot.update_install_command.clear();
    snapshot.latest_release_notes_available = false;
    snapshot.integration_updates_available = false;
    snapshot.release_notes = None;
    snapshot.commands.clear();
    snapshot.agents.clear();
    snapshot.panes[0].label = Some("SYNTHETIC SSH FIXTURE".into());
    snapshot.workspaces[0].label = "SYNTHETIC SSH FIXTURE".into();
    Ok(snapshot)
}
impl Wire {
    fn send_snapshot(&self, session: &mut server::Session, channel: ChannelId) -> Result<()> {
        send(
            session,
            channel,
            ServerMessage::EndpointControl {
                kind: ENDPOINT_SNAPSHOT_KIND.into(),
                data: serde_json::to_string(&snapshot(self.projection)?)?,
            },
        )
    }
    fn send_surface(&self, session: &mut server::Session, channel: ChannelId) -> Result<()> {
        const WIDTH: u16 = 80;
        const HEIGHT: u16 = 16;
        let mut cells = vec![
            CellData {
                symbol: " ".into(),
                fg: 7,
                bg: 0,
                modifier: 0,
                skip: false,
                hyperlink: None
            };
            usize::from(WIDTH) * usize::from(HEIGHT)
        ];
        for (y, line) in self.lines.iter().take(usize::from(HEIGHT)).enumerate() {
            for (x, ch) in line
                .chars()
                .filter(|c| !c.is_control())
                .take(usize::from(WIDTH))
                .enumerate()
            {
                cells[y * usize::from(WIDTH) + x].symbol = ch.to_string();
            }
        }
        let rect = SurfaceRect {
            x: 0,
            y: 0,
            width: WIDTH,
            height: HEIGHT,
        };
        send(
            session,
            channel,
            ServerMessage::PaneSurface(PaneSurfaceFrame {
                boot_id: BOOT.into(),
                projection_revision: self.projection,
                surface_revision: self.surface,
                frame: FrameData {
                    cells,
                    width: WIDTH,
                    height: HEIGHT,
                    cursor: None,
                    hyperlinks: vec![],
                    graphics: vec![],
                },
                panes: vec![PaneSurfacePane {
                    pane_id: PANE.into(),
                    content_revision: self.surface,
                    rect,
                    inner_rect: rect,
                    scrollbar_rect: None,
                    scroll: None,
                    focused: true,
                    mouse_reporting: false,
                    sgr_pixel_mouse: false,
                    alternate_screen_active: false,
                    pixel_width: 640,
                    pixel_height: 256,
                }],
                splits: vec![],
                popup: None,
                graphics: SurfaceGraphicsScene::default(),
            }),
        )
    }
    fn message(
        &mut self,
        message: ClientMessage,
        session: &mut server::Session,
        channel: ChannelId,
        records: &Mutex<Vec<Record>>,
    ) -> Result<()> {
        if self.phase == Phase::Hello {
            let ClientMessage::EndpointControl { kind, data } = message else {
                return Err(Error::InvalidInput);
            };
            if kind != ENDPOINT_HELLO_KIND {
                return Err(Error::InvalidInput);
            }
            let hello: EndpointClientHello = serde_json::from_str(&data)?;
            if hello.generation != ENDPOINT_PROTOCOL_GENERATION
                || !hello.surface_active
                || hello.direct_graphics
            {
                return Err(Error::InvalidInput);
            }
            let mut welcome: EndpointServerWelcome = serde_json::from_str(WELCOME)?;
            welcome.server_version = "synthetic-fixture-1".into();
            welcome.methods.push("client_shell.surface.set".into());
            welcome.capabilities = [
                "surface_interest",
                "presentation_effects_fence",
                "health_check",
            ]
            .map(String::from)
            .to_vec();
            send(
                session,
                channel,
                ServerMessage::EndpointControl {
                    kind: ENDPOINT_WELCOME_KIND.into(),
                    data: serde_json::to_string(&welcome)?,
                },
            )?;
            self.send_snapshot(session, channel)?;
            self.send_surface(session, channel)?;
            self.phase = Phase::Active;
            push(records, Record::Hello);
            return Ok(());
        }
        match message {
            ClientMessage::EndpointControl { kind, data }
                if kind == "endpoint.health.ping.v1" && data.len() <= 1024 =>
            {
                send(
                    session,
                    channel,
                    ServerMessage::EndpointControl {
                        kind: "endpoint.health.pong.v1".into(),
                        data,
                    },
                )?;
                push(records, Record::HealthPing);
            }
            ClientMessage::ClientShellEndpointRequest { boot_id, request }
                if boot_id == BOOT && request.len() <= 8192 =>
            {
                let request: serde_json::Value = serde_json::from_str(&request)?;
                let id = request["id"]
                    .as_str()
                    .filter(|id| !id.is_empty() && id.len() <= 128)
                    .ok_or(Error::InvalidInput)?;
                let is_focus =
                    request["method"] == "pane.focus" && request["params"]["pane_id"] == PANE;
                let response = if is_focus {
                    json!({"id": id, "result": {"type":"pane_info","pane":{"pane_id":PANE,"focused":true}}})
                } else if request["method"] == "client_shell.surface.set" {
                    json!({"id":id, "result":{"active":true}})
                } else {
                    return Err(Error::InvalidInput);
                };
                send(
                    session,
                    channel,
                    ServerMessage::ClientShellEndpointResponseChunk {
                        boot_id: BOOT.into(),
                        request_id: id.into(),
                        final_chunk: true,
                        data: serde_json::to_vec(&response)?,
                    },
                )?;
                if is_focus {
                    self.projection += 1;
                    self.surface += 1;
                    self.focused = true;
                    self.send_snapshot(session, channel)?;
                    self.send_surface(session, channel)?;
                    push(records, Record::Focus(id.into()));
                }
            }
            ClientMessage::ClientShellPaneInput { pane_id, events }
                if pane_id == PANE && self.focused =>
            {
                if events.is_empty() || events.len() > 64 {
                    return Err(Error::InvalidInput);
                }
                encode_message(&events, MAX_INPUT_BYTES)?;
                for event in &events {
                    let line = match event {
                        ClientPaneInputEvent::TextCommit(text) if text.len() <= MAX_INPUT_BYTES => {
                            format!("Text: {}", text.chars().take(72).collect::<String>())
                        }
                        ClientPaneInputEvent::Key {
                            code,
                            modifiers,
                            kind,
                            ..
                        } => format!("Key: {code:?} modifiers={modifiers} {kind:?}"),
                        _ => return Err(Error::InvalidInput),
                    };
                    if self.lines.len() == 16 {
                        self.lines.remove(3);
                    }
                    self.lines.push(line);
                }
                self.surface += 1;
                self.send_surface(session, channel)?;
                push(records, Record::Input(events));
            }
            ClientMessage::ClientShellHostTheme { .. }
            | ClientMessage::ClientShellFocus { .. }
            | ClientMessage::ClientShellResize { .. }
            | ClientMessage::ClientShellMouseCapture { .. } => {}
            ClientMessage::Detach => {
                session.eof(channel)?;
                session.close(channel)?;
            }
            _ => return Err(Error::InvalidInput),
        }
        Ok(())
    }
    fn data(
        &mut self,
        data: &[u8],
        session: &mut server::Session,
        channel: ChannelId,
        records: &Mutex<Vec<Record>>,
    ) -> Result<()> {
        if self.buffer.len().saturating_add(data.len()) > MAX_FRAME_SIZE + 4 {
            return Err(Error::InvalidInput);
        }
        self.buffer.extend_from_slice(data);
        if self.phase == Phase::Choice {
            let Some(newline) = self.buffer.iter().position(|b| *b == b'\n') else {
                return if self.buffer.len() <= 12 {
                    Ok(())
                } else {
                    Err(Error::InvalidInput)
                };
            };
            if &self.buffer[..=newline] != b"accept-idle\n" {
                return Err(Error::InvalidInput);
            }
            self.buffer.drain(..=newline);
            self.phase = Phase::Hello;
        }
        while self.buffer.len() >= 4 {
            let length = u32::from_le_bytes([
                self.buffer[0],
                self.buffer[1],
                self.buffer[2],
                self.buffer[3],
            ]) as usize;
            if length == 0 || length > MAX_FRAME_SIZE {
                return Err(Error::InvalidInput);
            }
            if self.buffer.len() < length + 4 {
                break;
            }
            let message = decode_payload::<ClientMessage>(&self.buffer[4..length + 4])?;
            self.buffer.drain(..length + 4);
            self.message(message, session, channel, records)?;
        }
        Ok(())
    }
}
impl server::Handler for Peer {
    type Error = Error;
    async fn auth_password(&mut self, user: &str, password: &str) -> Result<server::Auth> {
        Ok(if user == USERNAME && password == PASSWORD {
            server::Auth::Accept
        } else {
            server::Auth::reject()
        })
    }
    async fn channel_open_session(
        &mut self,
        channel: Channel<server::Msg>,
        reply: server::ChannelOpenHandle,
        _session: &mut server::Session,
    ) -> Result<()> {
        if self.open.len() >= MAX_CHANNELS {
            return Err(Error::InvalidInput);
        }
        self.open.insert(channel.id());
        reply.accept().await;
        Ok(())
    }
    async fn exec_request(
        &mut self,
        channel: ChannelId,
        command: &[u8],
        session: &mut server::Session,
    ) -> Result<()> {
        if command == self.commands.probe.as_bytes() {
            session.channel_success(channel)?;
            session.data(channel, format!("herdr-probe:candidate\n{}\n{{\"running\":true,\"synthetic\":true}}\nherdr-probe:done\n", status()).into_bytes())?;
            session.exit_status_request(channel, 0)?;
            session.eof(channel)?;
            session.close(channel)?;
            push(&self.records, Record::Probe);
        } else if command == self.commands.bridge.as_bytes()
            && self.channels.len() < MAX_CHANNELS
            && !self.channels.contains_key(&channel)
        {
            session.channel_success(channel)?;
            if self.scenario == Scenario::Normal {
                session.data(
                    channel,
                    format!("{}\n\nherdr-remote-output-ready:1\n", status()).into_bytes(),
                )?;
            }
            self.channels.insert(channel, Wire::default());
            push(&self.records, Record::Bridge);
        } else {
            session.channel_failure(channel)?;
            session.close(channel)?;
            push(&self.records, Record::RejectedCommand);
        }
        Ok(())
    }
    async fn data(
        &mut self,
        channel: ChannelId,
        data: &[u8],
        session: &mut server::Session,
    ) -> Result<()> {
        self.channels
            .get_mut(&channel)
            .ok_or(Error::InvalidInput)?
            .data(data, session, channel, &self.records)
    }
    async fn channel_close(
        &mut self,
        channel: ChannelId,
        _session: &mut server::Session,
    ) -> Result<()> {
        self.channels.remove(&channel);
        self.open.remove(&channel);
        Ok(())
    }
}
