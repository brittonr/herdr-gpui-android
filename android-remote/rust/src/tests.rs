#![allow(clippy::unwrap_used, clippy::expect_used)]

use super::*;
use russh::{Channel, ChannelId, server};
use std::sync::atomic::{AtomicUsize, Ordering};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[derive(Clone, Copy)]
enum TestAuth {
    Normal,
    PublicKeyOnly,
    KeyboardInteractiveOnly,
}

struct Fixture {
    config: Config,
    password_attempts: Arc<AtomicUsize>,
    publickey_attempts: Arc<AtomicUsize>,
    disconnected: Arc<tokio::sync::Notify>,
    stop: CancellationToken,
    task: tokio::task::JoinHandle<()>,
}
impl Fixture {
    async fn new() -> Self {
        Self::with_options("127.0.0.1", TestAuth::Normal).await
    }
    async fn with_options(address: &str, auth: TestAuth) -> Self {
        let key =
            russh::keys::PrivateKey::random(&mut rand::rng(), russh::keys::Algorithm::Ed25519)
                .unwrap();
        let fingerprint = key.public_key().fingerprint(HashAlg::Sha256).to_string();
        let server_config = Arc::new(server::Config {
            keys: vec![key],
            auth_rejection_time: Duration::ZERO,
            auth_rejection_time_initial: Some(Duration::ZERO),
            inactivity_timeout: Some(Duration::from_secs(10)),
            ..Default::default()
        });
        let listener = tokio::net::TcpListener::bind((address, 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let password_attempts = Arc::new(AtomicUsize::new(0));
        let publickey_attempts = Arc::new(AtomicUsize::new(0));
        let disconnected = Arc::new(tokio::sync::Notify::new());
        let stop = CancellationToken::new();
        let task_stop = stop.clone();
        let attempts = password_attempts.clone();
        let key_attempts = publickey_attempts.clone();
        let ended = disconnected.clone();
        let task = tokio::spawn(async move {
            let mut sessions = tokio::task::JoinSet::new();
            loop {
                tokio::select! {
                    biased;
                    _ = task_stop.cancelled() => break,
                    connection = listener.accept() => {
                        let Ok((stream, _)) = connection else { break };
                        let handler = TestServer { password_attempts: attempts.clone(), publickey_attempts: key_attempts.clone(), disconnected: ended.clone(), echo: false, auth };
                        let server_config = server_config.clone();
                        let connection_stop = task_stop.clone();
                        sessions.spawn(async move {
                            if let Ok(running) = server::run_stream(server_config, stream, handler).await {
                                let handle = running.handle();
                                tokio::select! {
                                    _ = connection_stop.cancelled() => { let _ = handle.disconnect(russh::Disconnect::ByApplication, "fixture finished".into(), "".into()).await; },
                                    _ = running => {},
                                }
                            }
                        });
                    }
                }
            }
            while sessions.join_next().await.is_some() {}
        });
        Self {
            config: Config {
                transport: Transport::Tcp {
                    host: address.into(),
                    port,
                },
                username: "fixture".into(),
                expected_fingerprint: Some(fingerprint),
            },
            password_attempts,
            publickey_attempts,
            disconnected,
            stop,
            task,
        }
    }
    async fn session(&self) -> Session {
        connect(
            &self.config,
            Credentials::password("public-test-password".into()),
            CancellationToken::new(),
        )
        .await
        .unwrap()
    }
    async fn finish(self) {
        self.stop.cancel();
        tokio::time::timeout(Duration::from_secs(2), self.task)
            .await
            .unwrap()
            .unwrap();
    }
}
struct TestServer {
    auth: TestAuth,
    password_attempts: Arc<AtomicUsize>,
    publickey_attempts: Arc<AtomicUsize>,
    disconnected: Arc<tokio::sync::Notify>,
    echo: bool,
}
impl Drop for TestServer {
    fn drop(&mut self) {
        self.disconnected.notify_one();
    }
}
impl server::Handler for TestServer {
    type Error = russh::Error;
    async fn auth_password(
        &mut self,
        user: &str,
        password: &str,
    ) -> std::result::Result<server::Auth, Self::Error> {
        self.password_attempts.fetch_add(1, Ordering::SeqCst);
        match self.auth {
            TestAuth::PublicKeyOnly => {
                return Ok(server::Auth::Reject {
                    proceed_with_methods: Some([MethodKind::PublicKey].as_slice().into()),
                    partial_success: false,
                });
            }
            TestAuth::KeyboardInteractiveOnly => {
                return Ok(server::Auth::Reject {
                    proceed_with_methods: Some([MethodKind::KeyboardInteractive].as_slice().into()),
                    partial_success: false,
                });
            }
            TestAuth::Normal => {}
        }
        Ok(if user == "fixture" && password == "public-test-password" {
            server::Auth::Accept
        } else {
            // Keep password available after a wrong password, as OpenSSH does.
            server::Auth::Reject {
                proceed_with_methods: Some(
                    [MethodKind::Password, MethodKind::PublicKey]
                        .as_slice()
                        .into(),
                ),
                partial_success: false,
            }
        })
    }
    async fn auth_publickey(
        &mut self,
        user: &str,
        _key: &russh::keys::PublicKey,
    ) -> std::result::Result<server::Auth, Self::Error> {
        self.publickey_attempts.fetch_add(1, Ordering::SeqCst);
        Ok(if user == "fixture" {
            server::Auth::Accept
        } else {
            server::Auth::reject()
        })
    }
    async fn channel_open_session(
        &mut self,
        _channel: Channel<server::Msg>,
        reply: server::ChannelOpenHandle,
        _session: &mut server::Session,
    ) -> std::result::Result<(), Self::Error> {
        reply.accept().await;
        Ok(())
    }
    async fn exec_request(
        &mut self,
        channel: ChannelId,
        command: &[u8],
        session: &mut server::Session,
    ) -> std::result::Result<(), Self::Error> {
        if command == b"reject" {
            session.channel_failure(channel)?;
            return Ok(());
        }
        session.channel_success(channel)?;
        match command {
            b"echo" => {
                self.echo = true;
                session.extended_data(channel, 1, b"not protocol data".as_slice())?;
            }
            b"hang" => {}
            b"overflow" => {
                session.data(channel, vec![b'x'; 1024])?;
            }
            b"stderr-overflow" => {
                session.extended_data(channel, 1, vec![b'x'; 1024])?;
            }
            b"no-status" => {
                session.eof(channel)?;
                session.close(channel)?;
            }
            _ => {
                session.data(channel, b"hello".as_slice())?;
                session.extended_data(channel, 1, b"warning".as_slice())?;
                session.exit_status_request(channel, 7)?;
                session.eof(channel)?;
                session.close(channel)?;
            }
        }
        Ok(())
    }
    async fn data(
        &mut self,
        channel: ChannelId,
        data: &[u8],
        session: &mut server::Session,
    ) -> std::result::Result<(), Self::Error> {
        if self.echo {
            session.data(channel, data.to_vec())?;
        }
        Ok(())
    }
}

#[tokio::test]
async fn inspection_never_authenticates() {
    let fixture = Fixture::new().await;
    let fingerprint = inspect_host_key(&fixture.config, CancellationToken::new())
        .await
        .unwrap();
    assert_eq!(Some(fingerprint), fixture.config.expected_fingerprint);
    assert_eq!(fixture.password_attempts.load(Ordering::SeqCst), 0);
    fixture.finish().await;
}

#[tokio::test]
async fn changed_key_is_rejected_before_authentication() {
    let fixture = Fixture::new().await;
    let mut config = fixture.config.clone();
    config.expected_fingerprint = Some("SHA256:wrong-key".into());
    let result = connect(
        &config,
        Credentials::password("public-test-password".into()),
        CancellationToken::new(),
    )
    .await;
    assert!(matches!(result, Err(Error::HostKeyMismatch)));
    assert_eq!(fixture.password_attempts.load(Ordering::SeqCst), 0);
    fixture.finish().await;
}

#[tokio::test]
async fn missing_pin_is_rejected_before_network_or_authentication() {
    let config = Config {
        transport: Transport::Tcp {
            host: "127.0.0.1".into(),
            port: 1,
        },
        username: "fixture".into(),
        expected_fingerprint: None,
    };
    let result = connect(
        &config,
        Credentials::password("public-test-password".into()),
        CancellationToken::new(),
    )
    .await;
    assert!(matches!(result, Err(Error::MissingFingerprint)));
}

#[tokio::test]
async fn bounded_exec_preserves_streams_and_status() {
    let fixture = Fixture::new().await;
    let mut session = fixture.session().await;
    let output = session
        .exec_bounded("output", ExecLimits::default())
        .await
        .unwrap();
    assert_eq!(output.stdout, b"hello");
    assert_eq!(output.stderr, b"warning");
    assert_eq!(output.exit_status, 7);
    drop(session);
    fixture.finish().await;
}

#[tokio::test]
async fn oversized_streams_cancel_session() {
    for (command, stream) in [("overflow", "stdout"), ("stderr-overflow", "stderr")] {
        let fixture = Fixture::new().await;
        let mut session = fixture.session().await;
        let result = session
            .exec_bounded(
                command,
                ExecLimits {
                    max_stdout_bytes: 16,
                    max_stderr_bytes: 16,
                    ..Default::default()
                },
            )
            .await;
        assert!(
            matches!(result, Err(Error::OutputLimit { stream: actual, limit: 16 }) if actual == stream)
        );
        assert!(session.guard.0.is_cancelled());
        drop(session);
        fixture.finish().await;
    }
}

#[tokio::test]
async fn exec_timeout_closes_owned_transport() {
    let fixture = Fixture::new().await;
    let mut session = fixture.session().await;
    let result = session
        .exec_bounded(
            "hang",
            ExecLimits {
                timeout: Duration::from_millis(50),
                ..Default::default()
            },
        )
        .await;
    assert!(matches!(result, Err(Error::Timeout)));
    tokio::time::timeout(Duration::from_secs(2), fixture.disconnected.notified())
        .await
        .unwrap();
    fixture.finish().await;
}

#[tokio::test]
async fn cancel_is_observed_without_replaying_commands() {
    let fixture = Fixture::new().await;
    let token = CancellationToken::new();
    let mut session = connect(
        &fixture.config,
        Credentials::password("public-test-password".into()),
        token.clone(),
    )
    .await
    .unwrap();
    token.cancel();
    assert!(matches!(
        session.exec_bounded("output", ExecLimits::default()).await,
        Err(Error::Cancelled)
    ));
    fixture.finish().await;
}

#[tokio::test]
async fn rejected_command_and_missing_status_are_errors() {
    for (command, rejected) in [("reject", true), ("no-status", false)] {
        let fixture = Fixture::new().await;
        let mut session = fixture.session().await;
        let result = session.exec_bounded(command, ExecLimits::default()).await;
        if rejected {
            assert!(matches!(result, Err(Error::CommandRejected)));
        } else {
            assert!(matches!(result, Err(Error::MissingExitStatus)));
        }
        fixture.finish().await;
    }
}

#[cfg(unix)]
#[tokio::test]
async fn bridge_forwards_binary_bytes_and_guard_drop_disconnects() {
    let fixture = Fixture::new().await;
    let bridge = fixture.session().await.into_bridge("echo").await.unwrap();
    let (socket, guard) = bridge.into_parts();
    socket.set_nonblocking(true).unwrap();
    let mut socket = tokio::net::UnixStream::from_std(socket).unwrap();
    let payload = [0, 255, 1, 13, 10, 0, 127];
    socket.write_all(&payload).await.unwrap();
    let mut output = [0; 7];
    tokio::time::timeout(Duration::from_secs(2), socket.read_exact(&mut output))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(output, payload);
    drop(guard);
    let mut remaining = Vec::new();
    tokio::time::timeout(Duration::from_secs(2), socket.read_to_end(&mut remaining))
        .await
        .unwrap()
        .unwrap();
    assert!(remaining.is_empty());
    tokio::time::timeout(Duration::from_secs(2), fixture.disconnected.notified())
        .await
        .unwrap();
    fixture.finish().await;
}

#[test]
fn output_limit_never_partially_appends_or_overflows() {
    let mut target = vec![1, 2];
    assert!(matches!(
        append_bounded(&mut target, &[3, 4], 3, "stdout"),
        Err(Error::OutputLimit { .. })
    ));
    assert_eq!(target, [1, 2]);
    assert!(append_bounded(&mut target, &[3], usize::MAX, "stdout").is_ok());
    assert!(matches!(
        append_bounded(&mut target, &[], 0, "stdout"),
        Ok(())
    ));
}

#[tokio::test]
async fn private_key_authentication_uses_in_memory_key() {
    let fixture = Fixture::new().await;
    let key =
        russh::keys::PrivateKey::random(&mut rand::rng(), russh::keys::Algorithm::Ed25519).unwrap();
    let encoded = key
        .to_openssh(russh::keys::ssh_key::LineEnding::LF)
        .unwrap()
        .to_string();
    let mut session = connect(
        &fixture.config,
        Credentials::private_key(encoded, None),
        CancellationToken::new(),
    )
    .await
    .unwrap();
    let output = session
        .exec_bounded("output", ExecLimits::default())
        .await
        .unwrap();
    assert_eq!(output.stdout, b"hello");
    assert_eq!(fixture.password_attempts.load(Ordering::SeqCst), 0);
    drop(session);
    fixture.finish().await;
}

#[tokio::test]
async fn wrong_password_is_typed_and_does_not_start_a_session() {
    let fixture = Fixture::new().await;
    let result = connect(
        &fixture.config,
        Credentials::password("wrong-public-test-password".into()),
        CancellationToken::new(),
    )
    .await;
    assert!(matches!(result, Err(Error::AuthenticationRejected)));
    assert_eq!(fixture.password_attempts.load(Ordering::SeqCst), 1);
    fixture.finish().await;
}

#[tokio::test]
async fn invalid_iroh_id_is_rejected_before_network() {
    let config = Config {
        transport: Transport::Iroh {
            endpoint_id: "fixture".into(),
        },
        username: "fixture".into(),
        expected_fingerprint: Some("SHA256:fixture".into()),
    };
    assert!(matches!(
        inspect_host_key(&config, CancellationToken::new()).await,
        Err(Error::InvalidConfig(_))
    ));
    assert!(matches!(
        connect(
            &config,
            Credentials::password("public-test-password".into()),
            CancellationToken::new()
        )
        .await,
        Err(Error::InvalidConfig(_))
    ));
}

mod iroh_transport;

#[test]
fn tcp_ipv6_brackets_are_normalized_without_reinterpreting_other_addresses() {
    for (input, expected) in [
        ("[::1]", "::1"),
        ("[2001:db8::1]", "2001:db8::1"),
        ("::1", "::1"),
        ("localhost", "localhost"),
        ("127.0.0.1", "127.0.0.1"),
    ] {
        assert_eq!(tcp_host(input).unwrap(), expected);
    }
    for bad in [
        "[localhost]",
        "[127.0.0.1]",
        "[::1]:22",
        "[::1",
        "::1]",
        "https://[::1]",
    ] {
        assert!(matches!(tcp_host(bad), Err(Error::InvalidConfig(_))));
    }
}

#[tokio::test]
async fn bracketed_ipv6_supports_pinned_ssh_inspection_and_exec() {
    let mut fixture = Fixture::with_options("::1", TestAuth::Normal).await;
    if let Transport::Tcp { host, .. } = &mut fixture.config.transport {
        *host = "[::1]".into();
    }
    assert_eq!(
        inspect_host_key(&fixture.config, CancellationToken::new())
            .await
            .unwrap(),
        fixture.config.expected_fingerprint.clone().unwrap()
    );
    assert_eq!(fixture.password_attempts.load(Ordering::SeqCst), 0);
    let mut session = fixture.session().await;
    assert_eq!(
        session
            .exec_bounded("output", ExecLimits::default())
            .await
            .unwrap()
            .stdout,
        b"hello"
    );
    session.shutdown().await;
    fixture.finish().await;
}

#[tokio::test]
async fn tcp_auth_policy_failure_never_retries_or_falls_back() {
    for mode in [TestAuth::PublicKeyOnly, TestAuth::KeyboardInteractiveOnly] {
        let fixture = Fixture::with_options("127.0.0.1", mode).await;
        let result = connect(
            &fixture.config,
            Credentials::password("public-test-password".into()),
            CancellationToken::new(),
        )
        .await;
        match mode {
            TestAuth::PublicKeyOnly => assert!(matches!(
                result,
                Err(Error::AuthenticationMethodUnavailable { method: "password" })
            )),
            TestAuth::KeyboardInteractiveOnly => {
                assert!(matches!(
                    result,
                    Err(Error::AuthenticationMethodUnavailable { method: "password" })
                ))
            }
            _ => unreachable!(),
        }
        assert_eq!(fixture.password_attempts.load(Ordering::SeqCst), 1);
        assert_eq!(fixture.publickey_attempts.load(Ordering::SeqCst), 0);
        fixture.finish().await;
    }
}

#[test]
fn auth_failure_classification_preserves_partial_success_and_unknown_policy() {
    for attempted in [MethodKind::Password, MethodKind::PublicKey] {
        assert!(authentication_result(client::AuthResult::Success, attempted).is_ok());
        for methods in [russh::MethodSet::empty(), [attempted].as_slice().into()] {
            assert!(matches!(
                authentication_result(
                    client::AuthResult::Failure {
                        remaining_methods: methods,
                        partial_success: false,
                    },
                    attempted
                ),
                Err(Error::AuthenticationRejected)
            ));
        }
        let error = authentication_result(
            client::AuthResult::Failure {
                remaining_methods: [attempted].as_slice().into(),
                partial_success: true,
            },
            attempted,
        )
        .unwrap_err();
        assert!(matches!(error, Error::AuthenticationIncomplete));
        assert!(error.to_string().contains("additional authentication"));
    }
}

#[test]
fn unavailable_public_key_method_is_reported_without_server_text() {
    let error = authentication_result(
        client::AuthResult::Failure {
            remaining_methods: [MethodKind::KeyboardInteractive].as_slice().into(),
            partial_success: false,
        },
        MethodKind::PublicKey,
    )
    .unwrap_err();
    assert!(matches!(
        error,
        Error::AuthenticationMethodUnavailable {
            method: "publickey"
        }
    ));
    assert!(
        error
            .to_string()
            .contains("publickey authentication at this stage")
    );
}
