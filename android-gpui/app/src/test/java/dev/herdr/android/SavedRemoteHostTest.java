package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class SavedRemoteHostTest {
    private static final String ENDPOINT = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String OTHER = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c";
    private static final String TICKET = "endpointadlvvgabqkyqvn6vjp7nhslea45a5yls6pnkmizfv4bbu2hxa5iruaqac5uhi5dqom5c6l3smvwgc6jomv4gc3lqnrss4lybadaaaaqb3a3a";

    private static SavedRemoteHost iroh(String endpoint, String ticket) {
        return iroh(endpoint, ticket, "");
    }

    private static SavedRemoteHost iroh(String endpoint, String ticket, String relay) {
        return SavedRemoteHost.create("Host", "iroh", "", 22, endpoint, ticket, relay, "user", "main");
    }

    @Test public void aNewTicketOrRelayForTheSameEndpointKeepsIdentityAndSurvivesCopies() {
        SavedRemoteHost bare = iroh(ENDPOINT, "");
        SavedRemoteHost ticketed = iroh(ENDPOINT, TICKET, "https://relay.example.org/");
        assertTrue(bare.sameIdentity(ticketed));
        assertFalse(bare.sameIdentity(iroh(OTHER, "")));
        SavedRemoteHost trusted = ticketed.copy("Renamed", "", ticketed.shortcuts, ticketed.quickCommands, null);
        assertEquals(TICKET, trusted.endpointTicket);
        assertEquals("https://relay.example.org/", trusted.relayUrl);
    }

    @Test public void directTcpProfilesCarryNoIrohRouting() {
        SavedRemoteHost tcp = SavedRemoteHost.create("Host", "tcp", "example.org", 22, ENDPOINT, TICKET,
                "https://relay.example.org/", "user", "main");
        assertEquals("", tcp.endpointId);
        assertEquals("", tcp.endpointTicket);
        assertEquals("", tcp.relayUrl);
    }

    @Test public void onlyBoundedCanonicalTicketsAreStored() {
        StringBuilder longest = new StringBuilder("endpoint");
        while (longest.length() < SavedRemoteHost.MAX_TICKET) longest.append('a');
        assertEquals(longest.toString(), iroh(ENDPOINT, longest.toString()).endpointTicket);
        for (String invalid : new String[] {
                longest + "a", TICKET.toUpperCase(java.util.Locale.ROOT), "https://relay.example./",
                "endpoint", TICKET + "\n", null}) {
            try {
                iroh(ENDPOINT, invalid);
                fail("Accepted " + invalid);
            } catch (IllegalArgumentException expected) {
                // Every other field is valid, so only the ticket was refused.
            }
        }
    }

    @Test public void onlyBoundedHttpRelayUrlsAreStored() {
        StringBuilder longest = new StringBuilder("https://relay.example.org/");
        while (longest.length() < SavedRemoteHost.MAX_RELAY) longest.append('a');
        assertEquals(longest.toString(), iroh(ENDPOINT, "", longest.toString()).relayUrl);
        assertEquals("http://192.0.2.7:3340/", iroh(ENDPOINT, "", "http://192.0.2.7:3340/").relayUrl);
        for (String invalid : new String[] {
                longest + "a", "relay.example.org", "ftp://relay.example.org/",
                "https://relay example.org/", "https://", null}) {
            try {
                iroh(ENDPOINT, "", invalid);
                fail("Accepted " + invalid);
            } catch (IllegalArgumentException expected) {
                // Every other field is valid, so only the relay was refused.
            }
        }
    }
}
