package dev.herdr.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A portable file of host profiles, written and read only when the user asks: each
 * host's address, user, session, extra keys, quick commands and trusted host key.
 * Passwords are never saved and the device SSH key is stored apart from profiles, so
 * neither can be in it. Device-local IDs stay behind; an import makes new profiles.
 */
final class HostFile {
    static final String FORMAT = "remote-for-herdr-hosts";
    static final int VERSION = 1;
    /**
     * Twice the encrypted store's bound: indentation and escaping never make an
     * export of a full store too large to import again.
     */
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final String MIME_TYPE = "application/json";
    static final String FILE_NAME = "remote-for-herdr-hosts.json";
    private static final String NOT_A_HOSTS_FILE = "This is not a Remote for Herdr hosts file";

    private HostFile() { }

    static byte[] write(List<SavedRemoteHost> hosts) throws JSONException {
        JSONArray values = new JSONArray();
        for (SavedRemoteHost host : hosts) values.put(host.toPortableJson());
        String text = new JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("hosts", values).toString(2);
        return (text + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Reads at most one byte past the limit, so an oversized file is refused, not buffered. */
    static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer, 0, (int) Math.min(buffer.length, MAX_BYTES + 1L - out.size()))) > 0) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_BYTES) throw new IllegalArgumentException("This file is too large to be a hosts file");
        }
        return out.toByteArray();
    }

    /** Every host in the file, validated as strictly as a profile typed in by hand. */
    static List<SavedRemoteHost> read(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("This file is too large to be a hosts file");
        JSONObject root;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            root = new JSONObject(text);
        } catch (CharacterCodingException | JSONException notJson) {
            throw new IllegalArgumentException(NOT_A_HOSTS_FILE, notJson);
        }
        Object version = root.opt("version");
        if (!FORMAT.equals(root.opt("format")) || !(version instanceof Integer) || (Integer) version < 1) {
            throw new IllegalArgumentException(NOT_A_HOSTS_FILE);
        }
        if ((Integer) version > VERSION) {
            throw new IllegalArgumentException("This hosts file is from a newer version of the app; update the app first");
        }
        JSONArray values = root.optJSONArray("hosts");
        if (values == null) throw new IllegalArgumentException(NOT_A_HOSTS_FILE);
        if (values.length() > RemoteHostStore.MAX_PROFILES) {
            throw new IllegalArgumentException("A hosts file holds at most " + RemoteHostStore.MAX_PROFILES + " hosts");
        }
        List<SavedRemoteHost> hosts = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            try {
                if (value == null) throw new IllegalArgumentException("it is not a host profile");
                hosts.add(SavedRemoteHost.fromPortableJson(value));
            } catch (JSONException missing) {
                throw new IllegalArgumentException("Host " + (i + 1) + " in the file is missing a setting", missing);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Host " + (i + 1) + " in the file is invalid: " + invalid.getMessage(), invalid);
            }
        }
        return hosts;
    }
}
