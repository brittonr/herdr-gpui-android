//! Spawning a connection worker. Setup, handshake, and teardown all happen off
//! the caller's thread; failures arrive as events rather than as a return value.

use crate::{
    ConnectTarget, Result, catalog,
    discovery::session_socket,
    event::{ClientEvent, deliver},
    handle::{Client, ClientHandle, HandleInner},
    limits::{COMMAND_CAPACITY, EVENT_CAPACITY},
    options::{ConnectOptions, validate_options},
    queue,
    session::run_connection,
    ssh,
    transport::Stream,
};
use crossbeam_channel::bounded;
use std::{
    io,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    thread,
};

/// Returns immediately after spawning. Connection/handshake errors arrive as events.
pub fn connect(target: ConnectTarget, options: ConnectOptions) -> Result<Client> {
    connect_with_surface_active(target, options, true)
}

/// Connect without changing `ConnectOptions` literals. Inactive connections require
/// negotiated surface interest and presentation-effect fencing. SSH additionally
/// requires endpoint health checks. All transport work runs off the caller thread.
pub fn connect_with_surface_active(
    target: ConnectTarget,
    options: ConnectOptions,
    surface_active: bool,
) -> Result<Client> {
    connect_with_connector(target, options, surface_active, |target, _| {
        let path = target
            .socket_path()
            .map_err(|error| io::Error::new(error.kind(), error))?;
        Stream::connect(path)
    })
}

/// Connect using application-specific local socket setup on the I/O worker.
/// SSH targets always use the remote bridge, never the local connector.
/// The connector should observe `stop` during waits so detach cancels setup.
pub fn connect_with_connector(
    target: ConnectTarget,
    options: ConnectOptions,
    surface_active: bool,
    connector: impl FnOnce(&ConnectTarget, &AtomicBool) -> io::Result<Stream> + Send + 'static,
) -> Result<Client> {
    validate_options(options)?;
    if let ConnectTarget::Ssh { target, session } = &target {
        catalog::validate_target(target)?;
        session_socket(std::path::Path::new(""), session)?;
    }
    let remote = matches!(target, ConnectTarget::Ssh { .. });
    spawn_connection(options, surface_active, remote, move |stop| match &target {
        ConnectTarget::Ssh { target, session } => {
            let (stream, child) = ssh::connect(target, session, stop)?;
            Ok((stream, Some(child)))
        }
        _ => Ok((connector(&target, stop)?, None)),
    })
}

/// Connect an application-owned remote transport without spawning an SSH CLI.
///
/// The connector runs on the I/O worker and must return a stream carrying only
/// the binary endpoint protocol, after any bridge prelude. A private socket pair
/// can adapt an embedded SSH channel while preserving socket deadlines and
/// command wakeups. The application owns that relay and must stop it when this
/// endpoint closes; setup must observe `stop` during waits.
///
/// Remote surface-interest and health capabilities are required exactly as for
/// `ConnectTarget::Ssh`. Connection, handshake, and protocol errors arrive through
/// `Client::events`; success here means only that the worker was spawned.
pub fn connect_with_remote_connector(
    options: ConnectOptions,
    surface_active: bool,
    connector: impl FnOnce(&AtomicBool) -> io::Result<Stream> + Send + 'static,
) -> Result<Client> {
    validate_options(options)?;
    spawn_connection(options, surface_active, true, move |stop| {
        Ok((connector(stop)?, ()))
    })
}

fn spawn_connection<Guard>(
    options: ConnectOptions,
    surface_active: bool,
    remote: bool,
    connector: impl FnOnce(&AtomicBool) -> Result<(Stream, Guard)> + Send + 'static,
) -> Result<Client> {
    let (commands, rx) = queue::channel(COMMAND_CAPACITY)?;
    let (tx, events) = bounded(EVENT_CAPACITY);
    let stop = Arc::new(AtomicBool::new(false));
    let worker_stop = stop.clone();
    thread::Builder::new()
        .name("herdr-client-io".into())
        .spawn(move || {
            let transport = if remote { "remote" } else { "local" };
            let span = tracing::info_span!("connection", transport);
            let _entered = span.enter();
            tracing::info!(transport, "connection starting");
            let result = (|| {
                let (stream, _guard) = connector(&worker_stop)?;
                run_connection(
                    stream,
                    options,
                    surface_active,
                    remote,
                    rx,
                    &tx,
                    &worker_stop,
                )
                // Any transport guard drops before delivering a disconnect event.
            })();
            if worker_stop.load(Ordering::Acquire) {
                tracing::debug!("connection cancelled");
            } else if let Err(error) = &result {
                tracing::warn!(kind = ?error.kind(), "connection ended with transport or protocol failure");
            } else {
                tracing::info!("connection ended");
            }
            if !worker_stop.load(Ordering::Acquire) {
                let reason = result
                    .err()
                    .map(|e| {
                        e.to_string()
                            .chars()
                            .filter(|c| !c.is_control())
                            .take(1024)
                            .collect()
                    })
                    .unwrap_or_else(|| "server disconnected".into());
                let _ = deliver(&tx, ClientEvent::Disconnected { reason }, &worker_stop);
            }
            worker_stop.store(true, Ordering::Release);
        })?;
    Ok(Client {
        handle: ClientHandle {
            inner: Arc::new(HandleInner {
                commands,
                stop,
                next_request: AtomicU64::new(1),
                image_busy: Arc::new(AtomicBool::new(false)),
                last_queued_theme: Default::default(),
            }),
        },
        events,
    })
}
