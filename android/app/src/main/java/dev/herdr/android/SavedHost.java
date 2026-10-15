package dev.herdr.android;

import dev.herdr.android.core.HostProfile;
import dev.herdr.android.core.OmpQuickCommands;
import dev.herdr.android.core.QuickCommand;
import dev.herdr.android.core.Shortcut;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Local identity is separate from the network origin; edits never retarget queued requests. */
final class SavedHost {
    static final String DEFAULT_KEYS = "Esc = key:esc\nTab = key:tab\nCtrl-C = key:ctrl+c\nCtrl-D = key:ctrl+d\nCtrl-L = key:ctrl+l\n↑ = key:up\n↓ = key:down\n← = key:left\n→ = key:right\nEnter = key:enter";
    final String id;
    final HostProfile profile;
    final String shortcuts;
    final List<QuickCommand> quickCommands;

    SavedHost(String id, HostProfile profile, String shortcuts) {
        this(id, profile, shortcuts, OmpQuickCommands.defaults());
    }

    SavedHost(String id, HostProfile profile, String shortcuts, List<QuickCommand> quickCommands) {
        this.id = id == null ? UUID.randomUUID().toString() : id;
        this.profile = profile;
        Shortcut.parseConfiguration(shortcuts);
        this.shortcuts = shortcuts;
        this.quickCommands = QuickCommand.validatedCopy(quickCommands);
    }

    JSONObject toJson() throws JSONException {
        JSONArray commands = new JSONArray();
        for (QuickCommand command : quickCommands) {
            commands.put(new JSONObject().put("label", command.getLabel()).put("text", command.getText())
                .put("pinned", command.isPinned()));
        }
        return new JSONObject().put("id", id).put("name", profile.getName())
            .put("url", profile.getBaseUrl()).put("token", profile.getToken()).put("shortcuts", shortcuts)
            .put("quickCommands", commands);
    }

    static SavedHost fromJson(JSONObject value) throws JSONException {
        List<QuickCommand> commands = OmpQuickCommands.defaults();
        // Missing is the legacy format. A present, malformed value must not erase saved commands.
        if (value.has("quickCommands")) {
            commands = new ArrayList<>();
            JSONArray values = value.getJSONArray("quickCommands");
            if (values.length() > QuickCommand.MAX_COMMANDS) throw new IllegalArgumentException("Too many quick commands");
            for (int i = 0; i < values.length(); i++) {
                JSONObject command = values.getJSONObject(i);
                Object label = command.get("label"), text = command.get("text");
                if (!(label instanceof String) || !(text instanceof String)) {
                    throw new IllegalArgumentException("Quick command label and text must be strings");
                }
                Object pinned = command.has("pinned") ? command.get("pinned") : Boolean.FALSE;
                if (!(pinned instanceof Boolean)) throw new IllegalArgumentException("Quick command pinned must be a boolean");
                commands.add(new QuickCommand((String) label, (String) text, (Boolean) pinned));
            }
        }
        return new SavedHost(value.getString("id"),
            new HostProfile(value.getString("name"), value.getString("url"), value.getString("token")),
            value.optString("shortcuts", DEFAULT_KEYS), commands);
    }

    boolean matchesConnection(String expectedId, HostProfile expectedProfile) {
        return id.equals(expectedId) && expectedProfile != null
            && profile.getBaseUrl().equals(expectedProfile.getBaseUrl())
            && profile.getToken().equals(expectedProfile.getToken());
    }
}
