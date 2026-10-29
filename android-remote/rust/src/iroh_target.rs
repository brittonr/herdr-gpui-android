//! Where to reach an Iroh SSH receiver: an endpoint ID, or an `endpoint…`
//! ticket that also names the relay and IP addresses the receiver published,
//! and optionally the user's own relay.

use iroh::{
    EndpointAddr, EndpointId, KeyParsingError, RelayUrl, RelayUrlParseError, TransportAddr,
};
use iroh_tickets::endpoint::EndpointTicket;
use std::str::FromStr;

/// Longer text is refused before decoding.
pub const MAX_IROH_TARGET_LEN: usize = 4096;
/// Every address in a ticket is dialled, so a ticket may name only this many.
pub const MAX_IROH_ADDRESSES: usize = 32;
/// Longer relay URLs are refused before parsing.
pub const MAX_IROH_RELAY_LEN: usize = 512;
const TICKET_PREFIX: &str = "endpoint";

#[derive(Debug, thiserror::Error)]
pub enum IrohTargetError {
    #[error("Enter an Iroh endpoint ID or an endpoint ticket")]
    Unrecognized(#[source] KeyParsingError),
    #[error("The Iroh ticket is longer than {} characters", MAX_IROH_TARGET_LEN)]
    TooLong,
    #[error("The Iroh ticket is damaged or incomplete")]
    Damaged(#[source] iroh_tickets::ParseError),
    #[error("The Iroh ticket is damaged or incomplete")]
    NonCanonical,
    #[error(
        "The Iroh ticket lists {count} addresses; at most {} are supported",
        MAX_IROH_ADDRESSES
    )]
    TooManyAddresses { count: usize },
    #[error("The Iroh ticket's relay must be an http or https URL")]
    RelayScheme,
    #[error("The Iroh ticket names a transport this app cannot use")]
    UnsupportedAddress,
    #[error("The saved Iroh ticket is for a different endpoint")]
    EndpointMismatch,
    #[error("Enter the relay as an http or https URL")]
    RelayUrl(#[source] RelayUrlParseError),
    #[error("Enter the relay as an http or https URL")]
    RelayUrlScheme,
    #[error("The relay URL is longer than {} characters", MAX_IROH_RELAY_LEN)]
    RelayUrlTooLong,
    #[error("The relay URL cannot include a user name or password")]
    RelayUrlCredentials,
}

/// An Iroh receiver's endpoint ID plus any relay and IP addresses from its
/// ticket. Only the endpoint ID identifies the receiver; addresses and relays
/// are routing hints, so changing them never changes whom the app reaches.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IrohTarget {
    /// The endpoint and the addresses its ticket published, as saved.
    published: EndpointAddr,
    /// The user's own relay. It replaces N0's relays and address lookup and the
    /// ticket's relays, so no other relay service learns of the connection.
    relay: Option<RelayUrl>,
}

impl IrohTarget {
    /// A saved profile: an endpoint ID, an optional ticket for that endpoint,
    /// and an optional relay.
    pub fn from_profile(
        endpoint_id: &str,
        ticket: &str,
        relay: &str,
    ) -> Result<Self, IrohTargetError> {
        let id = EndpointId::from_str(endpoint_id).map_err(IrohTargetError::Unrecognized)?;
        let target = if ticket.is_empty() {
            Self::published(EndpointAddr::new(id))
        } else {
            let target = Self::from_ticket(ticket)?;
            if target.published.id != id {
                return Err(IrohTargetError::EndpointMismatch);
            }
            target
        };
        target.with_relay(relay)
    }

    /// Reaches the receiver only through `relay`; empty text keeps Iroh's
    /// public relays and address lookup.
    pub fn with_relay(mut self, relay: &str) -> Result<Self, IrohTargetError> {
        self.relay = parse_relay(relay)?;
        Ok(self)
    }

    fn published(published: EndpointAddr) -> Self {
        Self {
            published,
            relay: None,
        }
    }

    fn from_ticket(text: &str) -> Result<Self, IrohTargetError> {
        if text.len() > MAX_IROH_TARGET_LEN {
            return Err(IrohTargetError::TooLong);
        }
        let ticket = EndpointTicket::from_str(text).map_err(IrohTargetError::Damaged)?;
        // Decoding ignores trailing bytes, and the address set sorts and
        // deduplicates, so only the exact canonical encoding is accepted.
        if !ticket.to_string().eq_ignore_ascii_case(text) {
            return Err(IrohTargetError::NonCanonical);
        }
        let address = EndpointAddr::from(ticket);
        if address.addrs.len() > MAX_IROH_ADDRESSES {
            return Err(IrohTargetError::TooManyAddresses {
                count: address.addrs.len(),
            });
        }
        for transport in &address.addrs {
            match transport {
                TransportAddr::Ip(_) => {}
                TransportAddr::Relay(url) if matches!(url.scheme(), "http" | "https") => {}
                TransportAddr::Relay(_) => return Err(IrohTargetError::RelayScheme),
                _ => return Err(IrohTargetError::UnsupportedAddress),
            }
        }
        Ok(Self::published(address))
    }

    /// Lowercase hex, the form that keys SSH trust for the profile.
    pub fn endpoint_id(&self) -> String {
        self.published.id.to_string()
    }

    /// The canonical ticket, or `None` when it would add nothing to the ID.
    pub fn ticket(&self) -> Option<String> {
        (!self.published.addrs.is_empty())
            .then(|| EndpointTicket::new(self.published.clone()).to_string())
    }

    /// The user's relay in canonical form, or `None` for Iroh's public relays.
    pub fn relay_url(&self) -> Option<String> {
        self.relay.as_ref().map(RelayUrl::to_string)
    }

    pub(crate) fn relay(&self) -> Option<&RelayUrl> {
        self.relay.as_ref()
    }

    /// What to dial. With the user's relay, a ticket contributes only its IP
    /// addresses: Iroh connects to any relay an address names.
    pub(crate) fn address(&self) -> EndpointAddr {
        let Some(relay) = &self.relay else {
            return self.published.clone();
        };
        let direct = self
            .published
            .addrs
            .iter()
            .filter(|addr| addr.is_ip())
            .cloned();
        EndpointAddr::from_parts(
            self.published.id,
            direct.chain([TransportAddr::Relay(relay.clone())]),
        )
    }
}

fn parse_relay(text: &str) -> Result<Option<RelayUrl>, IrohTargetError> {
    let text = text.trim();
    if text.is_empty() {
        return Ok(None);
    }
    if text.len() > MAX_IROH_RELAY_LEN {
        return Err(IrohTargetError::RelayUrlTooLong);
    }
    let url = RelayUrl::from_str(text).map_err(IrohTargetError::RelayUrl)?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err(IrohTargetError::RelayUrlScheme);
    }
    // Profiles never hold passwords; the relay client would not send them anyway.
    if !url.username().is_empty() || url.password().is_some() {
        return Err(IrohTargetError::RelayUrlCredentials);
    }
    Ok(Some(url))
}

/// Pasted text: an endpoint ID in either of Iroh's forms, or a ticket.
impl FromStr for IrohTarget {
    type Err = IrohTargetError;

    fn from_str(text: &str) -> Result<Self, Self::Err> {
        let text = text.trim();
        if text.len() > MAX_IROH_TARGET_LEN {
            return Err(IrohTargetError::TooLong);
        }
        match EndpointId::from_str(text) {
            Ok(id) => Ok(Self::published(EndpointAddr::new(id))),
            Err(_) if text.starts_with(TICKET_PREFIX) => Self::from_ticket(text),
            Err(source) => Err(IrohTargetError::Unrecognized(source)),
        }
    }
}
