//! Pasted Iroh text: endpoint IDs, canonical tickets, and their bounds.

use super::*;
use iroh::EndpointId;
use iroh_tickets::{ParseError, Ticket, endpoint::EndpointTicket};
use std::net::SocketAddr;

const ENDPOINT_BASE32: &str = "25njqamcweflpvkl73j4szahhihoc4xt3ktcgjnpaingr5yhkena";
/// RFC 8032 test vector 2's public key.
const OTHER_ENDPOINT: &str = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";

fn id(hex: &str) -> EndpointId {
    hex.parse().unwrap()
}

fn ticket(address: EndpointAddr) -> String {
    EndpointTicket::new(address).to_string()
}

fn reachable(hex: &str) -> EndpointAddr {
    EndpointAddr::new(id(hex))
        .with_relay_url("https://relay.example./".parse().unwrap())
        .with_ip_addr("192.0.2.1:7000".parse().unwrap())
        .with_ip_addr("[2001:db8::1]:7000".parse().unwrap())
}

fn parse(text: &str) -> std::result::Result<IrohTarget, IrohTargetError> {
    text.parse()
}

/// Arbitrary bytes in ticket form, for tickets no encoder would produce.
struct RawTicket(Vec<u8>);

impl Ticket for RawTicket {
    const KIND: &'static str = "endpoint";

    fn encode_bytes(&self) -> Vec<u8> {
        self.0.clone()
    }

    fn decode_bytes(bytes: &[u8]) -> std::result::Result<Self, ParseError> {
        Ok(Self(bytes.to_vec()))
    }
}

#[test]
fn endpoint_ids_in_either_form_become_lowercase_hex_without_a_ticket() {
    let padded = format!(" {ENDPOINT}\n");
    for text in [ENDPOINT, ENDPOINT_BASE32, padded.as_str()] {
        let target = parse(text).unwrap();
        assert_eq!(target.endpoint_id(), ENDPOINT);
        assert_eq!(target.ticket(), None);
        assert_eq!(target.relay_url(), None);
        assert_eq!(target.address(), EndpointAddr::new(id(ENDPOINT)));
    }
}

#[test]
fn tickets_keep_their_relay_and_ip_addresses() {
    let text = ticket(reachable(ENDPOINT));
    let target = parse(&text).unwrap();
    assert_eq!(target.address(), reachable(ENDPOINT));
    assert_eq!(target.endpoint_id(), ENDPOINT);
    assert_eq!(target.ticket().as_deref(), Some(text.as_str()));
    // A ticket naming only the endpoint adds nothing, so a profile keeps just the ID.
    let bare = parse(&ticket(EndpointAddr::new(id(ENDPOINT)))).unwrap();
    assert_eq!(bare.endpoint_id(), ENDPOINT);
    assert_eq!(bare.ticket(), None);
}

#[test]
fn unrecognized_damaged_and_noncanonical_text_is_refused() {
    for text in [
        "",
        "fixture",
        "https://relay.example./",
        &ENDPOINT[1..],
        "blobaaaa",
    ] {
        let error = parse(text).unwrap_err();
        assert!(matches!(error, IrohTargetError::Unrecognized(_)), "{text}");
        assert!(std::error::Error::source(&error).is_some());
    }
    let valid = ticket(reachable(ENDPOINT));
    for text in [&valid[..valid.len() - 4], "endpoint!!!!"] {
        assert!(
            matches!(parse(text), Err(IrohTargetError::Damaged(_))),
            "{text}"
        );
    }
    // Decoding ignores trailing bytes; the canonical check must not.
    let mut bytes = RawTicket::decode_string(&valid).unwrap().0;
    bytes.push(0);
    assert!(matches!(
        parse(&RawTicket(bytes).encode_string()),
        Err(IrohTargetError::NonCanonical)
    ));
}

#[test]
fn ticket_length_and_address_count_are_bounded() {
    let at_limit = format!("endpoint{}", "a".repeat(MAX_IROH_TARGET_LEN - 8));
    // Zero bytes decode as an empty ticket followed by ignored trailing bytes.
    assert!(matches!(
        parse(&at_limit),
        Err(IrohTargetError::NonCanonical)
    ));
    let over = format!("{at_limit}a");
    assert!(matches!(parse(&over), Err(IrohTargetError::TooLong)));

    let with = |count: u16| {
        ticket(
            (0..count).fold(EndpointAddr::new(id(ENDPOINT)), |address, port| {
                address.with_ip_addr(SocketAddr::from(([192, 0, 2, 1], 1000 + port)))
            }),
        )
    };
    let most = u16::try_from(MAX_IROH_ADDRESSES).unwrap();
    assert!(with(most).len() <= MAX_IROH_TARGET_LEN);
    let target = parse(&with(most)).unwrap();
    assert_eq!(target.address().addrs.len(), MAX_IROH_ADDRESSES);
    assert!(matches!(
        parse(&with(most + 1)),
        Err(IrohTargetError::TooManyAddresses { count }) if count == MAX_IROH_ADDRESSES + 1
    ));
}

#[test]
fn only_ip_and_http_relay_addresses_are_accepted() {
    let relay =
        |url: &str| ticket(EndpointAddr::new(id(ENDPOINT)).with_relay_url(url.parse().unwrap()));
    assert!(parse(&relay("http://relay.example./")).is_ok());
    for url in ["ftp://relay.example./", "file:///relay"] {
        assert!(matches!(
            parse(&relay(url)),
            Err(IrohTargetError::RelayScheme)
        ));
    }
    // Custom transport 1 with the one address byte `x`, replacing the empty set.
    let mut bytes = RawTicket::decode_string(&ticket(EndpointAddr::new(id(ENDPOINT))))
        .unwrap()
        .0;
    assert_eq!(
        bytes.pop(),
        Some(0),
        "an ID-only ticket ends with no addresses"
    );
    bytes.extend([1, 2, 1, 1, b'x']);
    assert!(matches!(
        parse(&RawTicket(bytes).encode_string()),
        Err(IrohTargetError::UnsupportedAddress)
    ));
}

#[test]
fn a_saved_ticket_must_belong_to_the_saved_endpoint() {
    let text = ticket(reachable(ENDPOINT));
    let target = IrohTarget::from_profile(ENDPOINT, &text, "").unwrap();
    assert_eq!(target.address(), reachable(ENDPOINT));
    let bare = IrohTarget::from_profile(ENDPOINT_BASE32, "", "").unwrap();
    assert_eq!(bare.address(), EndpointAddr::new(id(ENDPOINT)));
    assert!(matches!(
        IrohTarget::from_profile(OTHER_ENDPOINT, &text, ""),
        Err(IrohTargetError::EndpointMismatch)
    ));
    // The ID field holds only an ID; a ticket there is refused rather than dialled.
    assert!(matches!(
        IrohTarget::from_profile(&text, "", ""),
        Err(IrohTargetError::Unrecognized(_))
    ));
    assert!(matches!(
        IrohTarget::from_profile(ENDPOINT, "endpoint!!!!", ""),
        Err(IrohTargetError::Damaged(_))
    ));
}

#[test]
fn the_users_relay_replaces_ticket_relays_but_keeps_direct_addresses_and_the_ticket() {
    let text = ticket(reachable(ENDPOINT));
    let target =
        IrohTarget::from_profile(ENDPOINT, &text, " https://Relay.Example.org:8443 ").unwrap();
    let own = "https://relay.example.org:8443/";
    assert_eq!(target.relay_url().as_deref(), Some(own));
    assert_eq!(
        target.relay().map(ToString::to_string).as_deref(),
        Some(own)
    );
    let dialled = target.address();
    assert_eq!(dialled.id, id(ENDPOINT));
    assert_eq!(
        dialled
            .relay_urls()
            .map(ToString::to_string)
            .collect::<Vec<_>>(),
        [own]
    );
    assert_eq!(
        dialled.ip_addrs().copied().collect::<Vec<_>>(),
        reachable(ENDPOINT).ip_addrs().copied().collect::<Vec<_>>()
    );
    // The saved ticket is unchanged, so clearing the relay restores its relay.
    assert_eq!(target.ticket().as_deref(), Some(text.as_str()));
    let restored = target.with_relay("").unwrap();
    assert_eq!(restored.relay_url(), None);
    assert_eq!(restored.address(), reachable(ENDPOINT));
    // With only an ID, the user's relay is the one way to reach the receiver.
    let bare = parse(ENDPOINT)
        .unwrap()
        .with_relay("http://192.0.2.7:3340")
        .unwrap();
    assert_eq!(
        bare.address(),
        EndpointAddr::new(id(ENDPOINT)).with_relay_url("http://192.0.2.7:3340".parse().unwrap())
    );
}

#[test]
fn relay_urls_must_be_bounded_http_without_credentials() {
    let relay = |text: &str| parse(ENDPOINT).unwrap().with_relay(text);
    for text in ["relay.example.org", "https://", "https://exa mple.org"] {
        let error = relay(text).unwrap_err();
        assert!(matches!(error, IrohTargetError::RelayUrl(_)), "{text}");
        assert!(std::error::Error::source(&error).is_some());
    }
    for text in [
        "ftp://relay.example.org",
        "wss://relay.example.org",
        "file:///relay",
    ] {
        assert!(
            matches!(relay(text), Err(IrohTargetError::RelayUrlScheme)),
            "{text}"
        );
    }
    for text in [
        "https://user@relay.example.org",
        "https://:secret@relay.example.org",
    ] {
        assert!(
            matches!(relay(text), Err(IrohTargetError::RelayUrlCredentials)),
            "{text}"
        );
    }
    let prefix = "https://relay.example.org/";
    let longest = format!("{prefix}{}", "a".repeat(MAX_IROH_RELAY_LEN - prefix.len()));
    assert!(relay(&longest).is_ok());
    assert!(matches!(
        relay(&format!("{longest}a")),
        Err(IrohTargetError::RelayUrlTooLong)
    ));
    assert!(matches!(
        IrohTarget::from_profile(ENDPOINT, "", "ftp://relay.example.org"),
        Err(IrohTargetError::RelayUrlScheme)
    ));
}
