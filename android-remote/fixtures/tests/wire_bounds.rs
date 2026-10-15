#![allow(clippy::unwrap_used, clippy::expect_used)]
use herdr_protocol::{endpoint::*, *};
use herdr_remote_transport::{CancellationToken, Config, Credentials, Transport};
use herdr_synthetic_ssh_fixture::{Fixture, PASSWORD, Record, SESSION};
use std::{
    io::{Read, Write},
    sync::atomic::AtomicBool,
    time::Duration,
};
use tokio::io::{AsyncBufReadExt, AsyncReadExt};

fn config(fixture: &Fixture) -> Config {
    Config {
        transport: Transport::Tcp {
            host: "127.0.0.1".into(),
            port: fixture.info.port,
        },
        username: fixture.info.username.into(),
        expected_fingerprint: Some(fixture.info.expected_fingerprint.clone()),
    }
}
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn rejects_every_exec_except_exact_generated_commands() {
    let fixture = Fixture::start(0).await.unwrap();
    let mut ssh = herdr_remote_transport::connect(
        &config(&fixture),
        Credentials::password(PASSWORD.into()),
        CancellationToken::new(),
    )
    .await
    .unwrap();
    assert!(matches!(
        ssh.exec_bounded("arbitrary-command-never-run", Default::default())
            .await,
        Err(herdr_remote_transport::Error::CommandRejected)
    ));
    assert_eq!(fixture.records(), vec![Record::RejectedCommand]);
    fixture.shutdown().await.unwrap();
}
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn fragmented_shared_schema_hello_then_oversized_prefix_closes_only_peer() {
    let fixture = Fixture::start(0).await.unwrap();
    let ssh = herdr_remote_transport::connect(
        &config(&fixture),
        Credentials::password(PASSWORD.into()),
        CancellationToken::new(),
    )
    .await
    .unwrap();
    let bridge = ssh
        .into_bridge(&herdr_client::remote_bridge_command(SESSION).unwrap())
        .await
        .unwrap();
    tokio::task::spawn_blocking(move || {
        let (mut stream, _guard) = bridge.into_parts();
        stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        stream.set_write_timeout(Some(Duration::from_secs(2))).unwrap();
        herdr_client::negotiate_remote_bridge(&mut stream, &AtomicBool::new(false)).unwrap();
        let hello: EndpointClientHello = serde_json::from_str(include_str!("../../../crates/herdr-protocol/tests/fixtures/endpoint-hello-v1.json")).unwrap();
        let bytes = encode_message(&ClientMessage::EndpointControl { kind: ENDPOINT_HELLO_KIND.into(), data: serde_json::to_string(&hello).unwrap() }, MAX_FRAME_SIZE).unwrap();
        // Every byte, including length-prefix bytes, can arrive in separate data callbacks.
        for byte in &bytes { stream.write_all(&[*byte]).unwrap(); }
        assert!(matches!(read_message::<_, ServerMessage>(&mut stream, MAX_FRAME_SIZE).unwrap(), ServerMessage::EndpointControl { kind, .. } if kind == ENDPOINT_WELCOME_KIND));
        assert!(matches!(read_message::<_, ServerMessage>(&mut stream, MAX_FRAME_SIZE).unwrap(), ServerMessage::EndpointControl { kind, .. } if kind == ENDPOINT_SNAPSHOT_KIND));
        let ServerMessage::PaneSurface(frame) = read_message(&mut stream, MAX_FRAME_SIZE).unwrap() else { panic!("expected shared-schema surface") };
        frame.frame.validate().unwrap();
        stream.write_all(&((MAX_FRAME_SIZE as u32) + 1).to_le_bytes()).unwrap();
        let mut byte = [0];
        match stream.read(&mut byte) {
            Ok(0) => {},
            Err(e) if matches!(e.kind(), std::io::ErrorKind::ConnectionReset | std::io::ErrorKind::BrokenPipe) => {},
            other => panic!("oversized peer must close promptly: {other:?}"),
        }
    }).await.unwrap();
    assert_eq!(fixture.records(), vec![Record::Bridge, Record::Hello]);
    // A protocol error on one connection does not close the fixture's listener.
    let mut ssh = herdr_remote_transport::connect(
        &config(&fixture),
        Credentials::password(PASSWORD.into()),
        CancellationToken::new(),
    )
    .await
    .unwrap();
    let probe = ssh
        .exec_bounded(
            &herdr_client::remote_probe_command(SESSION).unwrap(),
            Default::default(),
        )
        .await
        .unwrap();
    assert_eq!(
        herdr_client::classify_remote_probe(&probe.stdout).unwrap(),
        herdr_client::HostProbe::Running
    );
    fixture.shutdown().await.unwrap();
}
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn shutdown_cancels_unfinished_ssh_handshake_and_releases_own_listener() {
    let fixture = Fixture::start(0).await.unwrap();
    let port = fixture.info.port;
    let mut stream = tokio::io::BufReader::new(
        tokio::net::TcpStream::connect(("127.0.0.1", port))
            .await
            .unwrap(),
    );
    let mut banner = String::new();
    tokio::time::timeout(Duration::from_secs(2), stream.read_line(&mut banner))
        .await
        .unwrap()
        .unwrap();
    assert!(banner.starts_with("SSH-2.0-"));
    fixture.shutdown().await.unwrap();
    let mut byte = [0];
    assert_eq!(
        tokio::time::timeout(Duration::from_secs(2), stream.read(&mut byte))
            .await
            .unwrap()
            .unwrap(),
        0
    );
    let rebound = tokio::net::TcpListener::bind(("127.0.0.1", port))
        .await
        .unwrap();
    drop(rebound);
}
