package dev.herdr.android;

import dev.herdr.android.core.HostProfile;
import dev.herdr.android.core.OmpQuickCommands;
import dev.herdr.android.core.QuickCommand;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Host-side storage schema and edit-fencing tests. No keystore or user host is accessed. */
public final class HostStoreSelfTest {
    private static int checks;
    private static final HostProfile PROFILE = new HostProfile("Fixture", "https://fixture.invalid/base", "fixture-token");

    public static void main(String[] args) throws Exception {
        migration(); roundTrip(); malformed(); identityFencing();
        System.out.println("HostStoreSelfTest passed (" + checks + " checks)");
    }

    private static void migration() throws Exception {
        JSONObject legacy = legacy();
        SavedHost loaded = SavedHost.fromJson(legacy);
        check(loaded.quickCommands.size() == OmpQuickCommands.defaults().size(), "legacy hosts receive versioned OMP presets");
        check(SavedHost.DEFAULT_KEYS.equals(loaded.shortcuts), "legacy shortcuts retain defaults");
        check(loaded.toJson().getJSONArray("quickCommands").length() == loaded.quickCommands.size(), "migration writes an explicit list");
        check(SavedHost.fromJson(legacy().put("quickCommands", new JSONArray())).quickCommands.isEmpty(), "explicit empty list never restores presets");
        JSONObject value = legacy().put("quickCommands", new JSONArray().put(new JSONObject().put("label", "Help").put("text", "/help")));
        check(!SavedHost.fromJson(value).quickCommands.get(0).isPinned(), "missing pinned is backwards compatible");
        check(new SavedHost("old-call", PROFILE, SavedHost.DEFAULT_KEYS).quickCommands.size() == OmpQuickCommands.defaults().size(), "three-argument constructor supplies defaults");
    }

    private static void roundTrip() throws Exception {
        List<QuickCommand> source = new ArrayList<>();
        source.add(new QuickCommand("Help", "  /help λ猫😀\\n  ", true));
        source.add(new QuickCommand("Status", "git status"));
        SavedHost host = new SavedHost("one", PROFILE, "Stop = key:ctrl+c", source);
        source.clear();
        SavedHost copy = SavedHost.fromJson(new JSONObject(host.toJson().toString()));
        check(copy.quickCommands.size() == 2, "defensive copy survives serialization");
        check(copy.quickCommands.get(0).isPinned() && !copy.quickCommands.get(1).isPinned(), "pinned state round trips");
        check(copy.quickCommands.get(0).getText().equals("  /help λ猫😀\\n  "), "exact text is retained without implicit Enter");
        check(copy.id.equals(host.id) && copy.shortcuts.equals(host.shortcuts), "host identity and shortcuts round trip");
        check(copy.matchesConnection(host.id, host.profile), "network identity round trips");
        try { copy.quickCommands.clear(); throw new AssertionError("Saved list is mutable"); }
        catch (UnsupportedOperationException expected) { checks++; }
        check(new SavedHost("two", PROFILE, SavedHost.DEFAULT_KEYS).quickCommands.get(0).getText()
            .equals(OmpQuickCommands.defaults().get(0).getText()), "commands are per host");
    }

    private static void malformed() throws Exception {
        for (Object value : Arrays.asList(JSONObject.NULL, "not-an-array", new JSONObject(), 4, true)) {
            reject(() -> SavedHost.fromJson(legacy().put("quickCommands", value)));
        }
        for (Object value : Arrays.asList(JSONObject.NULL, "not-an-object", 4, true)) {
            reject(() -> SavedHost.fromJson(legacy().put("quickCommands", new JSONArray().put(value))));
        }
        reject(() -> SavedHost.fromJson(legacy().put("quickCommands", new JSONArray().put(new JSONObject()))));
        reject(() -> SavedHost.fromJson(legacy().put("quickCommands", new JSONArray().put(new JSONObject().put("label", "Help")))));
        for (Object value : Arrays.asList(JSONObject.NULL, 12, false, new JSONArray())) {
            reject(() -> SavedHost.fromJson(withCommand(value, "/help", false)));
            reject(() -> SavedHost.fromJson(withCommand("Help", value, false)));
        }
        for (Object value : Arrays.asList(JSONObject.NULL, "true", 1, new JSONArray())) {
            reject(() -> SavedHost.fromJson(withCommand("Help", "/help", value)));
        }
        for (String value : Arrays.asList("", "next\nrun", "next\r", "next\t", "next\u2028", "next\u2029", "\ud800")) {
            reject(() -> SavedHost.fromJson(withCommand("Help", value, false)));
        }
        JSONObject duplicate = legacy().put("quickCommands", new JSONArray()
            .put(new JSONObject().put("label", "Help").put("text", "/help"))
            .put(new JSONObject().put("label", " Help ").put("text", "/status")));
        reject(() -> SavedHost.fromJson(duplicate));
        JSONArray many = new JSONArray();
        for (int i = 0; i < 25; i++) many.put(new JSONObject().put("label", "C" + i).put("text", "/help"));
        reject(() -> SavedHost.fromJson(legacy().put("quickCommands", many)));
    }

    private static void identityFencing() throws Exception {
        SavedHost original = new SavedHost("one", PROFILE, SavedHost.DEFAULT_KEYS, Collections.<QuickCommand>emptyList());
        SavedHost latest = new SavedHost("one", new HostProfile("Renamed", PROFILE.getBaseUrl(), PROFILE.getToken()),
            "New key = key:enter", Collections.singletonList(new QuickCommand("Existing", "old")));
        SavedHost other = new SavedHost("other", new HostProfile("Other", "https://other.invalid", "other-fixture"),
            SavedHost.DEFAULT_KEYS, Collections.singletonList(new QuickCommand("Other", "other-command")));
        List<SavedHost> latestHosts = new ArrayList<>(Arrays.asList(other, latest));
        SavedHost changed = HostStore.replaceQuickCommands(latestHosts, original.id, original.profile,
            Collections.singletonList(new QuickCommand("Help", "/help", true)));
        check(latestHosts.get(0) == other, "unrelated latest host is preserved");
        check(latestHosts.get(1) == changed, "only matching host is replaced");
        check(changed.profile == latest.profile && changed.shortcuts.equals(latest.shortcuts), "latest profile name and shortcuts are preserved");
        check(changed.quickCommands.get(0).getText().equals("/help"), "new commands are saved");
        check(original.quickCommands.isEmpty() && latest.quickCommands.get(0).getText().equals("old"), "active immutable snapshots are unchanged");

        reject(() -> HostStore.replaceQuickCommands(latestHosts, "deleted", PROFILE, Collections.<QuickCommand>emptyList()));
        reject(() -> HostStore.replaceQuickCommands(latestHosts, "one", new HostProfile("Fixture", "https://elsewhere.invalid", PROFILE.getToken()), Collections.<QuickCommand>emptyList()));
        reject(() -> HostStore.replaceQuickCommands(latestHosts, "one", new HostProfile("Fixture", PROFILE.getBaseUrl(), "replaced-token"), Collections.<QuickCommand>emptyList()));
        reject(() -> HostStore.replaceQuickCommands(latestHosts, "one", new HostProfile("Fixture", "https://fixture.invalid/other", PROFILE.getToken()), Collections.<QuickCommand>emptyList()));
        reject(() -> HostStore.replaceQuickCommands(latestHosts, "one", null, Collections.<QuickCommand>emptyList()));
        reject(() -> HostStore.replaceQuickCommands(latestHosts, "one", PROFILE, Arrays.asList(new QuickCommand("Help", "x"), new QuickCommand("Help", "y"))));
        check(latestHosts.get(0) == other && latestHosts.get(1) == changed, "failed edits never mutate the list or resurrect a host");
        SavedHost cleared = HostStore.replaceQuickCommands(latestHosts, "one", PROFILE, Collections.<QuickCommand>emptyList());
        check(cleared.quickCommands.isEmpty(), "explicit empty list clears commands without defaults");
    }

    private static JSONObject legacy() throws Exception {
        return new JSONObject().put("id", "one").put("name", PROFILE.getName())
            .put("url", PROFILE.getBaseUrl()).put("token", PROFILE.getToken());
    }

    private static JSONObject withCommand(Object label, Object text, Object pinned) throws Exception {
        return legacy().put("quickCommands", new JSONArray().put(
            new JSONObject().put("label", label).put("text", text).put("pinned", pinned)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void reject(Action action) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | JSONException expected) { checks++; return; }
        throw new AssertionError("Invalid or stale saved command was accepted");
    }

    private interface Action { void run() throws Exception; }
}
