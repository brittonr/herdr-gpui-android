#![allow(clippy::unwrap_used, clippy::expect_used)]
use herdr_gpui_android::remote;
use herdr_protocol::{ClientKeyCode, ClientKeyKind, ClientPaneInputEvent};
use herdr_synthetic_ssh_fixture::{Fixture, PANE, PASSWORD, Record};
use serde_json::{Value, json};
use std::time::Duration;

async fn view_until(id: u64, predicate: impl Fn(&Value) -> bool) -> Value {
    tokio::time::timeout(Duration::from_secs(8), async {
        loop {
            let view: Value = serde_json::from_str(&remote::poll(id).unwrap()).unwrap();
            assert_ne!(view["state"], "closed", "adapter closed: {view}");
            if predicate(&view) {
                return view;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("adapter state deadline")
}
struct Session(u64);
impl Drop for Session {
    fn drop(&mut self) {
        remote::close(self.0);
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn real_loopback_ssh_drives_adapter_focus_and_semantic_input() {
    let fixture = Fixture::start(0).await.unwrap();
    let config = fixture.info.adapter_config();
    let inspect_config = config.clone();
    let inspected = tokio::task::spawn_blocking(move || remote::inspect(&inspect_config))
        .await
        .unwrap()
        .unwrap();
    assert_eq!(
        serde_json::from_str::<Value>(&inspected).unwrap()["fingerprint"],
        fixture.info.expected_fingerprint
    );
    assert!(
        fixture.records().is_empty(),
        "inspection must not execute probe/bridge"
    );

    let session = Session(remote::connect(&config, PASSWORD.into()).unwrap());
    let initial = view_until(session.0, |v| v["panes"][0]["id"] == PANE).await;
    assert_eq!(initial["pane_id"], "");
    assert!(matches!(
        remote::input(session.0, PANE, r#"{"type":"text","text":"too early"}"#),
        Err(remote::Error::StaleTarget)
    ));
    remote::focus(session.0, PANE).unwrap();
    let focused = view_until(session.0, |v| {
        v["pane_id"] == PANE
            && v["text"]
                .as_str()
                .unwrap_or_default()
                .contains("SYNTHETIC SSH FIXTURE")
    })
    .await;
    assert_eq!(focused["state"], "ready");
    assert_eq!(focused["boot_id"], "synthetic-ssh-fixture-boot");
    assert_eq!(focused["revision"], 2);

    remote::input(
        session.0,
        PANE,
        &json!({"type":"text","text":"IME café ✅"}).to_string(),
    )
    .unwrap();
    remote::input(
        session.0,
        PANE,
        r#"{"type":"submit","text":"palette submit"}"#,
    )
    .unwrap();
    remote::input(session.0, PANE, r#"{"type":"keys","keys":["ctrl+c","up"]}"#).unwrap();
    let updated = view_until(session.0, |v| {
        v["text"]
            .as_str()
            .unwrap_or_default()
            .contains("Key: Up modifiers=0 Press")
    })
    .await;
    let text = updated["text"].as_str().unwrap();
    assert!(text.contains("Text: IME café ✅"));
    assert!(text.contains("Text: palette submit"));
    assert!(text.contains("Key: Enter modifiers=0 Press"));
    assert!(text.contains("Key: Char('c') modifiers=2 Press"));
    assert_eq!(updated["revision"], 5);
    let records = fixture.records();
    assert_eq!(
        &records[..3],
        &[Record::Probe, Record::Bridge, Record::Hello]
    );
    assert!(matches!(records[3], Record::Focus(_)));
    let inputs: Vec<_> = records
        .iter()
        .filter_map(|r| match r {
            Record::Input(events) => Some(events.clone()),
            _ => None,
        })
        .collect();
    assert_eq!(inputs.len(), 3, "no pre-focus input reached the wire");
    assert_eq!(
        inputs[0],
        vec![ClientPaneInputEvent::TextCommit("IME café ✅".into())]
    );
    assert_eq!(
        inputs[1][0],
        ClientPaneInputEvent::TextCommit("palette submit".into())
    );
    assert!(matches!(
        &inputs[1][1],
        ClientPaneInputEvent::Key {
            code: ClientKeyCode::Enter,
            kind: ClientKeyKind::Press,
            ..
        }
    ));
    assert!(matches!(
        &inputs[2][0],
        ClientPaneInputEvent::Key {
            code: ClientKeyCode::Char('c'),
            modifiers: 2,
            ..
        }
    ));
    // The real client's idle timer must traverse the SSH bridge and get a pong.
    tokio::time::timeout(Duration::from_secs(7), async {
        while !fixture.records().contains(&Record::HealthPing) {
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    })
    .await
    .expect("health ping deadline");
    assert_eq!(
        serde_json::from_str::<Value>(&remote::poll(session.0).unwrap()).unwrap()["state"],
        "ready"
    );
    remote::close(session.0);
    assert!(matches!(
        remote::poll(session.0),
        Err(remote::Error::Closed)
    ));
    assert!(matches!(
        remote::input(session.0, PANE, r#"{"type":"text","text":"after close"}"#),
        Err(remote::Error::Closed)
    ));
    let reopened = Session(remote::connect(&config, PASSWORD.into()).unwrap());
    view_until(reopened.0, |v| v["panes"][0]["id"] == PANE).await;
    remote::focus(reopened.0, PANE).unwrap();
    let fresh = view_until(reopened.0, |v| {
        v["pane_id"] == PANE && v["state"] == "ready"
    })
    .await;
    assert!(!fresh["text"].as_str().unwrap().contains("palette submit"));
    assert_eq!(
        fixture
            .records()
            .iter()
            .filter(|r| matches!(r, Record::Input(_)))
            .count(),
        3,
        "reopening cannot replay semantic input from a closed handle"
    );
    remote::close(reopened.0);
    fixture.shutdown().await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pin_mismatch_never_executes_fixture_commands() {
    let fixture = Fixture::start(0).await.unwrap();
    let mut config: Value = serde_json::from_str(&fixture.info.adapter_config()).unwrap();
    config["expected_fingerprint"] = json!("SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    let session = Session(remote::connect(&config.to_string(), PASSWORD.into()).unwrap());
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            let view: Value = serde_json::from_str(&remote::poll(session.0).unwrap()).unwrap();
            if view["state"] == "closed" {
                assert!(view["status"].as_str().unwrap().contains("host key"));
                break;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    assert!(fixture.records().is_empty());
    fixture.shutdown().await.unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn close_during_bridge_negotiation_closes_owned_ssh_without_input() {
    use herdr_synthetic_ssh_fixture::Scenario;
    let fixture = Fixture::start_with_scenario(0, Scenario::StallBridge)
        .await
        .unwrap();
    let session =
        Session(remote::connect(&fixture.info.adapter_config(), PASSWORD.into()).unwrap());
    tokio::time::timeout(Duration::from_secs(5), async {
        while !fixture.records().contains(&Record::Bridge) {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("bridge exec deadline");
    assert_eq!(fixture.active_connections(), 1);
    remote::close(session.0);
    assert!(matches!(
        remote::poll(session.0),
        Err(remote::Error::Closed)
    ));
    tokio::time::timeout(Duration::from_secs(3), async {
        while fixture.active_connections() != 0 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("Close must cancel the actual SSH socket during negotiation");
    assert_eq!(fixture.records(), vec![Record::Probe, Record::Bridge]);
    fixture.shutdown().await.unwrap();
}
