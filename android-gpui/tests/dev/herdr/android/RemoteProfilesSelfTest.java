package dev.herdr.android;

import dev.herdr.android.core.QuickCommand;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Host-side profile policy tests. Keystore, Android lifecycle and real SSH require device tests. */
public final class RemoteProfilesSelfTest {
    private static int checks;
    private static final String PIN = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    public static void main(String[] args) {
        List<SavedRemoteHost> values = new ArrayList<>();
        SavedRemoteHost first = RemoteHostStore.replaceProfile(values, null,
                SavedRemoteHost.create("Fixture", "tcp", "fixture.invalid", 22, "", "test", "default"));
        check(first.expectedFingerprint.isEmpty(), "new profiles have no implicit trust");
        check(!first.quickCommands.isEmpty(), "OMP defaults are available for new remote profiles");
        SavedRemoteHost trusted = RemoteHostStore.replaceTrust(values, first, PIN);
        check(PIN.equals(trusted.expectedFingerprint), "explicit trust stores exact key");
        check(!trusted.connectionRevision.equals(first.connectionRevision), "trust rotates revision");
        reject(() -> RemoteHostStore.findExpected(values, first));
        reject(() -> RemoteHostStore.replaceTrust(values, trusted, PIN));
        reject(() -> RemoteHostStore.replaceTrust(values, trusted, "SHA256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"));

        List<QuickCommand> custom = Arrays.asList(new QuickCommand("Inspect", "/help", true));
        SavedRemoteHost commands = RemoteHostStore.replaceQuickCommands(values, trusted, custom);
        SavedRemoteHost keys = RemoteHostStore.replaceShortcuts(values, trusted, "Stop = key:ctrl+c\nHelp = text:/help");
        check(keys.quickCommands.get(0).getText().equals("/help"), "key edit preserves newer OMP command edits");
        check(keys.matchesConnection(commands), "local key edit keeps exact connection identity");
        check(keys.expectedFingerprint.equals(PIN), "local key edit preserves SSH trust");
        reject(() -> RemoteHostStore.replaceShortcuts(values, keys, "Broken = key:not-a-key"));
        check(values.get(0) == keys, "invalid key configuration leaves profile untouched");

        SavedRemoteHost renamed = RemoteHostStore.replaceProfile(values, trusted,
                SavedRemoteHost.create("Renamed", "tcp", "fixture.invalid", 22, "", "test", "default"));
        check(renamed.expectedFingerprint.equals(PIN), "name-only edit keeps trust");
        check(renamed.connectionRevision.equals(keys.connectionRevision), "name-only edit keeps revision");
        check(renamed.shortcuts.equals(keys.shortcuts), "profile form preserves concurrent key edits");
        check(renamed.quickCommands.get(0).getText().equals("/help"), "profile form preserves OMP commands");
        SavedRemoteHost changed = RemoteHostStore.replaceProfile(values, renamed,
                SavedRemoteHost.create("Retargeted", "tcp", "other.invalid", 2200, "", "test", "default"));
        check(changed.expectedFingerprint.isEmpty(), "changing SSH address clears trust");
        check(!changed.connectionRevision.equals(renamed.connectionRevision), "changing SSH address rotates revision");
        reject(() -> RemoteHostStore.replaceTrust(values, renamed, PIN));
        reject(() -> RemoteHostStore.replaceQuickCommands(values, renamed, custom));
        reject(() -> RemoteHostStore.replaceShortcuts(values, renamed, "Stop = key:ctrl+c"));
        reject(() -> RemoteHostStore.replaceProfile(values, renamed, first));
        values.clear();
        reject(() -> RemoteHostStore.replaceTrust(values, changed, PIN));
        reject(() -> RemoteHostStore.replaceProfile(values, changed, first));

        reject(() -> SavedRemoteHost.create("Bad", "tcp", "https://fixture.invalid", 22, "", "test", "default"));
        reject(() -> SavedRemoteHost.create("Bad", "tcp", "fixture.invalid", 0, "", "test", "default"));
        reject(() -> SavedRemoteHost.create("Bad", "tcp", "fixture.invalid", 65536, "", "test", "default"));
        reject(() -> SavedRemoteHost.create("Bad", "tcp", "fixture.invalid", 22, "", "test name", "default"));
        reject(() -> SavedRemoteHost.create("Bad", "tcp", "fixture.invalid", 22, "", "test", "default; echo unsafe"));
        reject(() -> SavedRemoteHost.fingerprint("MD5:not-sha256"));
        SavedRemoteHost peer = SavedRemoteHost.create("Iroh fixture", "iroh", "", 22, "abc123", "test", "default");
        check(peer.host.isEmpty() && peer.transport.equals("iroh"), "Iroh remains a distinct profile transport");
        System.out.println("Remote profile policy: " + checks + " checks passed");
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
        checks++;
    }

    private static void reject(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Expected invalid or stale operation to be rejected");
    }
}
