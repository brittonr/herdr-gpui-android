package dev.herdr.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import dev.herdr.android.core.QuickCommand;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class HostFileTest {
    private static final String PIN = "SHA256:4fFfVsbJWG6Esa7Cq5Nr4KRGFobxDXCQdn9gVkicEgA";
    private static final String ENDPOINT = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    private static final String TICKET = "endpointadlvvgabqkyqvn6vjp7nhslea45a5yls6pnkmizfv4bbu2hxa5iruaqac5uhi5dqom5c6l3smvwgc6jomv4gc3lqnrss4lybadaaaaqb3a3a";
    private static final String NOT_A_HOSTS_FILE = "not a Remote for Herdr hosts file";

    private static SavedRemoteHost workstation() {
        SavedRemoteHost host = SavedRemoteHost.create("Workstation", "tcp", "example.org", 2222, "", "", "",
                "britton", "demo");
        return host.copy(host.name, PIN, "Esc = key:esc\nEnter = key:enter", Arrays.asList(
                new QuickCommand("Status", "git status", true), new QuickCommand("Log", "git log -5", false)), null);
    }

    private static SavedRemoteHost laptop() {
        return SavedRemoteHost.create("Laptop", "iroh", "", 22, ENDPOINT, TICKET, "https://relay.example.org/",
                "britton", "main");
    }

    @Test public void exportedHostsComeBackWithEverySettingAsNewProfiles() throws Exception {
        List<SavedRemoteHost> original = Arrays.asList(workstation(), laptop());
        byte[] file = HostFile.write(original);
        List<SavedRemoteHost> imported = HostFile.read(file);
        assertEquals(2, imported.size());
        for (int i = 0; i < original.size(); i++) {
            SavedRemoteHost before = original.get(i);
            SavedRemoteHost after = imported.get(i);
            assertEquals(before.name, after.name);
            assertTrue(before.sameIdentity(after));
            assertEquals(before.endpointTicket, after.endpointTicket);
            assertEquals(before.relayUrl, after.relayUrl);
            assertEquals(before.expectedFingerprint, after.expectedFingerprint);
            assertEquals(before.shortcuts, after.shortcuts);
            assertEquals(commands(before), commands(after));
            // Device-local identifiers stay behind: an import makes new profiles.
            assertNotEquals(before.id, UUID.fromString(after.id).toString());
            assertNotEquals(before.connectionRevision, UUID.fromString(after.connectionRevision).toString());
        }
        JSONObject root = new JSONObject(new String(file, StandardCharsets.UTF_8));
        assertEquals(HostFile.FORMAT, root.getString("format"));
        assertEquals(HostFile.VERSION, root.getInt("version"));
        JSONObject written = root.getJSONArray("hosts").getJSONObject(0);
        assertFalse(written.has("id"));
        assertFalse(written.has("connectionRevision"));
    }

    @Test public void filesThatAreNotHostFilesAreRefusedWithTheirReason() throws Exception {
        assertRefused("not json".getBytes(StandardCharsets.UTF_8), NOT_A_HOSTS_FILE);
        assertRefused(new byte[] {(byte) 0xff, '{', '}'}, NOT_A_HOSTS_FILE);
        assertRefused(file("other-app-hosts", 1, new JSONArray()), NOT_A_HOSTS_FILE);
        assertRefused(file(HostFile.FORMAT, 2, new JSONArray()), "newer version");
        JSONArray tooMany = new JSONArray();
        for (int i = 0; i <= RemoteHostStore.MAX_PROFILES; i++) tooMany.put(workstation().toPortableJson());
        assertRefused(file(HostFile.FORMAT, 1, tooMany), "at most 32 hosts");
        JSONArray badPort = new JSONArray().put(laptop().toPortableJson())
                .put(workstation().toPortableJson().put("port", 0));
        assertRefused(file(HostFile.FORMAT, 1, badPort), "Host 2 in the file is invalid");
        JSONObject noUser = workstation().toPortableJson();
        noUser.remove("username");
        assertRefused(file(HostFile.FORMAT, 1, new JSONArray().put(noUser)), "Host 1 in the file is missing a setting");
    }

    @Test public void filesPastTheSizeLimitAreRefused() throws Exception {
        byte[] largest = new byte[HostFile.MAX_BYTES];
        assertEquals(largest.length, HostFile.readBounded(new ByteArrayInputStream(largest)).length);
        try {
            HostFile.readBounded(new ByteArrayInputStream(new byte[HostFile.MAX_BYTES + 1]));
            fail("a file past the size limit was read");
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("too large"));
        }
    }

    private static List<String> commands(SavedRemoteHost host) {
        List<String> out = new ArrayList<>();
        for (QuickCommand command : host.quickCommands) {
            out.add(command.getLabel() + "|" + command.getText() + "|" + command.isPinned());
        }
        return out;
    }

    private static byte[] file(String format, int version, JSONArray hosts) throws Exception {
        return new JSONObject().put("format", format).put("version", version).put("hosts", hosts)
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void assertRefused(byte[] bytes, String reason) {
        try {
            HostFile.read(bytes);
            fail("accepted a file that should be refused: " + reason);
        } catch (IllegalArgumentException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains(reason));
        }
    }
}
