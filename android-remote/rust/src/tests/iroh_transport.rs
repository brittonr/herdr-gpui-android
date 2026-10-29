//! A direct loopback proxy with the iroh-ssh 0.2.12 raw-stream contract.
//! No fixture contacts discovery, relay services, or an existing SSH server.

use super::*;

async fn local_endpoint() -> Endpoint {
    Endpoint::builder(presets::Minimal)
        .relay_mode(iroh::RelayMode::Disabled)
        .clear_address_lookup()
        .portmapper_config(iroh::endpoint::PortmapperConfig::Disabled)
        .net_report_config(iroh::endpoint::NetReportConfig::minimal())
        .clear_ip_transports()
        .bind_addr("127.0.0.1:0")
        .unwrap()
        .alpns(vec![IROH_SSH_ALPN.to_vec()])
        .bind()
        .await
        .unwrap()
}

struct IrohFixture {
    ssh: Fixture,
    endpoint: Endpoint,
    address: EndpointAddr,
    connections: Arc<AtomicUsize>,
    streams: Arc<AtomicUsize>,
    task: tokio::task::JoinHandle<()>,
}

impl IrohFixture {
    async fn new() -> Self {
        Self::with_auth(TestAuth::Normal).await
    }

    async fn with_auth(auth: TestAuth) -> Self {
        let ssh = Fixture::with_options("127.0.0.1", auth).await;
        let Transport::Tcp { port, .. } = ssh.config.transport else {
            unreachable!()
        };
        let endpoint = local_endpoint().await;
        let socket = endpoint.bound_sockets().into_iter().next().unwrap();
        assert!(socket.ip().is_loopback());
        let address = EndpointAddr::new(endpoint.id()).with_ip_addr(socket);
        let accepting = endpoint.clone();
        let connections = Arc::new(AtomicUsize::new(0));
        let streams = Arc::new(AtomicUsize::new(0));
        let connection_count = connections.clone();
        let stream_count = streams.clone();
        let task = tokio::spawn(async move {
            let mut sessions = tokio::task::JoinSet::new();
            while let Some(incoming) = accepting.accept().await {
                let connections = connection_count.clone();
                let streams = stream_count.clone();
                sessions.spawn(async move {
                    let connection = incoming.await.unwrap();
                    assert_eq!(connection.alpn(), IROH_SSH_ALPN);
                    connections.fetch_add(1, Ordering::SeqCst);
                    let (send, mut recv) = connection.accept_bi().await.unwrap();
                    streams.fetch_add(1, Ordering::SeqCst);
                    // The client must open the stream by sending SSH immediately,
                    // without a length prefix, JSON header, or target negotiation.
                    let mut banner = [0; 8];
                    tokio::time::timeout(Duration::from_secs(2), recv.read_exact(&mut banner))
                        .await
                        .unwrap()
                        .unwrap();
                    assert_eq!(&banner, b"SSH-2.0-");
                    let mut tcp = tokio::net::TcpStream::connect(("127.0.0.1", port))
                        .await
                        .unwrap();
                    tcp.write_all(&banner).await.unwrap();
                    let mut stream = tokio::io::join(recv, send);
                    tokio::select! {
                        _ = tokio::io::copy_bidirectional(&mut tcp, &mut stream) => {},
                        extra = connection.accept_bi() => {
                            assert!(extra.is_err(), "client opened more than one Iroh stream");
                        }
                    }
                });
            }
            while let Some(result) = sessions.join_next().await {
                result.unwrap();
            }
        });
        Self {
            ssh,
            endpoint,
            address,
            connections,
            streams,
            task,
        }
    }

    async fn target(&self) -> Result<DialTarget> {
        Ok(DialTarget::Iroh {
            endpoint: local_endpoint().await,
            address: self.address.clone(),
        })
    }

    async fn connect(
        &self,
        config: &Config,
        credentials: Credentials,
        token: CancellationToken,
    ) -> Result<Session> {
        connect_with_target(config, credentials, token, self.target()).await
    }

    async fn session(&self) -> Session {
        self.connect(
            &self.ssh.config,
            Credentials::password("public-test-password".into()),
            CancellationToken::new(),
        )
        .await
        .unwrap()
    }

    async fn finish(self) {
        self.endpoint.close().await;
        tokio::time::timeout(Duration::from_secs(2), self.task)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(self.connections.load(Ordering::SeqCst), 1);
        assert_eq!(self.streams.load(Ordering::SeqCst), 1);
        self.ssh.finish().await;
    }
}

#[tokio::test]
async fn iroh_raw_ssh_exec_uses_one_stream_with_exact_alpn() {
    let fixture = IrohFixture::new().await;
    let mut session = fixture.session().await;
    let output = session
        .exec_bounded("output", ExecLimits::default())
        .await
        .unwrap();
    assert_eq!(output.stdout, b"hello");
    assert_eq!(output.stderr, b"warning");
    assert_eq!(output.exit_status, 7);
    assert_eq!(fixture.ssh.password_attempts.load(Ordering::SeqCst), 1);
    drop(session);
    fixture.finish().await;
}

#[tokio::test]
async fn iroh_inspection_never_sends_credentials() {
    let fixture = IrohFixture::new().await;
    let fingerprint = inspect_with_target(fixture.target(), CancellationToken::new())
        .await
        .unwrap();
    assert_eq!(Some(fingerprint), fixture.ssh.config.expected_fingerprint);
    assert_eq!(fixture.ssh.password_attempts.load(Ordering::SeqCst), 0);
    assert_eq!(fixture.ssh.publickey_attempts.load(Ordering::SeqCst), 0);
    fixture.finish().await;
}

#[tokio::test]
async fn iroh_changed_pin_rejects_password_and_key_before_auth() {
    for credentials in [
        Credentials::password("public-test-password".into()),
        Credentials::private_key("must never be decoded".into(), None),
    ] {
        let fixture = IrohFixture::new().await;
        let mut config = fixture.ssh.config.clone();
        config.expected_fingerprint = Some("SHA256:wrong-key".into());
        let result = fixture
            .connect(&config, credentials, CancellationToken::new())
            .await;
        assert!(matches!(result, Err(Error::HostKeyMismatch)));
        assert_eq!(fixture.ssh.password_attempts.load(Ordering::SeqCst), 0);
        assert_eq!(fixture.ssh.publickey_attempts.load(Ordering::SeqCst), 0);
        fixture.finish().await;
    }
}

#[cfg(unix)]
#[tokio::test]
async fn iroh_bridge_preserves_binary_bytes_and_closes_on_drop() {
    let fixture = IrohFixture::new().await;
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
    tokio::time::timeout(Duration::from_secs(2), fixture.ssh.disconnected.notified())
        .await
        .unwrap();
    fixture.finish().await;
}

#[tokio::test]
async fn iroh_limits_and_cancellation_close_without_reconnecting() {
    for command in ["overflow", "stderr-overflow", "hang"] {
        let fixture = IrohFixture::new().await;
        let mut session = fixture.session().await;
        let result = session
            .exec_bounded(
                command,
                ExecLimits {
                    timeout: if command == "hang" {
                        Duration::from_millis(100)
                    } else {
                        Duration::from_secs(2)
                    },
                    max_stdout_bytes: 16,
                    max_stderr_bytes: 16,
                },
            )
            .await;
        if command == "hang" {
            assert!(matches!(result, Err(Error::Timeout)));
        } else {
            assert!(matches!(result, Err(Error::OutputLimit { .. })));
        }
        assert!(session.guard.0.is_cancelled());
        drop(session);
        tokio::time::timeout(Duration::from_secs(2), fixture.ssh.disconnected.notified())
            .await
            .unwrap();
        fixture.finish().await;
    }
}

#[tokio::test]
async fn iroh_private_key_authentication_uses_ephemeral_key() {
    let fixture = IrohFixture::new().await;
    let key =
        russh::keys::PrivateKey::random(&mut rand::rng(), russh::keys::Algorithm::Ed25519).unwrap();
    let encoded = key
        .to_openssh(russh::keys::ssh_key::LineEnding::LF)
        .unwrap()
        .to_string();
    let mut session = fixture
        .connect(
            &fixture.ssh.config,
            Credentials::private_key(encoded, None),
            CancellationToken::new(),
        )
        .await
        .unwrap();
    assert_eq!(
        session
            .exec_bounded("output", ExecLimits::default())
            .await
            .unwrap()
            .stdout,
        b"hello"
    );
    assert_eq!(fixture.ssh.password_attempts.load(Ordering::SeqCst), 0);
    assert_eq!(fixture.ssh.publickey_attempts.load(Ordering::SeqCst), 1);
    drop(session);
    fixture.finish().await;
}

#[tokio::test]
async fn iroh_external_cancellation_closes_even_while_session_is_retained() {
    let fixture = IrohFixture::new().await;
    let token = CancellationToken::new();
    let mut session = fixture
        .connect(
            &fixture.ssh.config,
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
    tokio::time::timeout(Duration::from_secs(2), fixture.ssh.disconnected.notified())
        .await
        .unwrap();
    drop(session);
    fixture.finish().await;
}

#[tokio::test]
async fn missing_pin_or_prior_cancellation_never_starts_iroh_discovery() {
    let config = Config {
        transport: Transport::Iroh {
            target: ENDPOINT.parse().unwrap(),
        },
        username: "fixture".into(),
        expected_fingerprint: None,
    };
    let target = async { panic!("network future must never be polled") };
    assert!(matches!(
        connect_with_target(
            &config,
            Credentials::password("unused".into()),
            CancellationToken::new(),
            target
        )
        .await,
        Err(Error::MissingFingerprint)
    ));
    let token = CancellationToken::new();
    token.cancel();
    let target = async { panic!("network future must never be polled") };
    assert!(matches!(
        inspect_with_target(target, token).await,
        Err(Error::Cancelled)
    ));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn iroh_inspection_drains_before_its_dedicated_runtime_is_destroyed() {
    let fixture = IrohFixture::new().await;
    let address = fixture.address.clone();
    let fingerprint = tokio::task::spawn_blocking(move || {
        // This runtime belongs only to the client and is dropped immediately.
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime
            .block_on(inspect_with_target(
                async {
                    Ok(DialTarget::Iroh {
                        endpoint: local_endpoint().await,
                        address,
                    })
                },
                CancellationToken::new(),
            ))
            .unwrap()
    })
    .await
    .unwrap();
    assert_eq!(Some(fingerprint), fixture.ssh.config.expected_fingerprint);
    tokio::time::timeout(Duration::from_secs(2), fixture.ssh.disconnected.notified())
        .await
        .unwrap();
    fixture.finish().await;
}

#[cfg(unix)]
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn iroh_bridge_shutdown_drains_before_its_dedicated_runtime_is_destroyed() {
    let fixture = IrohFixture::new().await;
    let address = fixture.address.clone();
    let config = fixture.ssh.config.clone();
    tokio::task::spawn_blocking(move || {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let session = connect_with_target(
                &config,
                Credentials::password("public-test-password".into()),
                CancellationToken::new(),
                async {
                    Ok(DialTarget::Iroh {
                        endpoint: local_endpoint().await,
                        address,
                    })
                },
            )
            .await
            .unwrap();
            let (_socket, guard) = session.into_bridge("echo").await.unwrap().into_parts();
            guard.shutdown().await;
        });
    })
    .await
    .unwrap();
    tokio::time::timeout(Duration::from_secs(2), fixture.ssh.disconnected.notified())
        .await
        .unwrap();
    fixture.finish().await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn iroh_session_shutdown_drains_before_its_dedicated_runtime_is_destroyed() {
    let fixture = IrohFixture::new().await;
    let address = fixture.address.clone();
    let config = fixture.ssh.config.clone();
    tokio::task::spawn_blocking(move || {
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let session = connect_with_target(
                &config,
                Credentials::password("public-test-password".into()),
                CancellationToken::new(),
                async {
                    Ok(DialTarget::Iroh {
                        endpoint: local_endpoint().await,
                        address,
                    })
                },
            )
            .await
            .unwrap();
            session.shutdown().await;
        });
    })
    .await
    .unwrap();
    tokio::time::timeout(Duration::from_secs(2), fixture.ssh.disconnected.notified())
        .await
        .unwrap();
    fixture.finish().await;
}

#[tokio::test]
async fn iroh_auth_policy_failure_never_retries_or_falls_back() {
    for mode in [TestAuth::PublicKeyOnly, TestAuth::KeyboardInteractiveOnly] {
        let fixture = IrohFixture::with_auth(mode).await;
        let result = fixture
            .connect(
                &fixture.ssh.config,
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
        assert_eq!(fixture.ssh.password_attempts.load(Ordering::SeqCst), 1);
        assert_eq!(fixture.ssh.publickey_attempts.load(Ordering::SeqCst), 0);
        fixture.finish().await;
    }
}
