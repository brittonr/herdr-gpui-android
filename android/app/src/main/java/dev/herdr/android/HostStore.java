package dev.herdr.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import dev.herdr.android.core.HostProfile;
import dev.herdr.android.core.QuickCommand;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** All profiles, including bearer tokens, are authenticated-encrypted and excluded from backup. */
final class HostStore {
    private static final String ALIAS = "herdr.hosts.v1";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    // Activities have independent HostStore instances but share one encrypted file.
    private static final Object LOCK = new Object();
    private final AtomicFile file;

    HostStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "hosts-v1.enc"));
    }

    List<SavedHost> read() throws Exception {
        synchronized (LOCK) { return readLocked(); }
    }

    void write(List<SavedHost> hosts) throws Exception {
        synchronized (LOCK) { writeLocked(hosts); }
    }

    private List<SavedHost> readLocked() throws Exception {
        // AtomicFile.openRead restores an interrupted write's backup before opening.
        // Checking only the base path first would lose recoverable profiles on Android 8.
        byte[] bytes;
        try (FileInputStream stream = file.openRead()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (count > MAX_BYTES - out.size()) throw new IllegalStateException("Saved host file is too large");
                out.write(buffer, 0, count);
            }
            bytes = out.toByteArray();
        } catch (FileNotFoundException missing) {
            File base = file.getBaseFile();
            if (base.exists() || new File(base.getPath() + ".bak").exists() || new File(base.getPath() + ".new").exists()) throw missing;
            return new ArrayList<>();
        }
        if (bytes.length < 30 || bytes[0] != 1) throw new IllegalStateException("Saved host format is not supported");
        byte[] iv = new byte[12];
        System.arraycopy(bytes, 1, iv, 0, iv.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
        cipher.updateAAD("herdr-hosts-v1".getBytes(StandardCharsets.UTF_8));
        String json = new String(cipher.doFinal(bytes, 13, bytes.length - 13), StandardCharsets.UTF_8);
        JSONArray values = new JSONObject(json).getJSONArray("hosts");
        if (values.length() > 32) throw new IllegalStateException("Too many saved hosts");
        List<SavedHost> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) result.add(SavedHost.fromJson(values.getJSONObject(i)));
        return result;
    }

    List<SavedHost> saveProfile(SavedHost expected, HostProfile profile) throws Exception {
        synchronized (LOCK) {
            List<SavedHost> hosts = readLocked();
            if (expected == null) {
                hosts.add(new SavedHost(null, profile, SavedHost.DEFAULT_KEYS));
            } else {
                int index = findExpectedHost(hosts, expected.id, expected.profile);
                SavedHost current = hosts.get(index);
                hosts.set(index, new SavedHost(current.id, profile, current.shortcuts, current.quickCommands));
            }
            writeLocked(hosts);
            return hosts;
        }
    }

    List<SavedHost> updateShortcuts(SavedHost expected, String shortcuts) throws Exception {
        synchronized (LOCK) {
            List<SavedHost> hosts = readLocked();
            int index = findExpectedHost(hosts, expected.id, expected.profile);
            SavedHost current = hosts.get(index);
            hosts.set(index, new SavedHost(current.id, current.profile, shortcuts, current.quickCommands));
            writeLocked(hosts);
            return hosts;
        }
    }

    List<SavedHost> deleteHost(SavedHost expected) throws Exception {
        synchronized (LOCK) {
            List<SavedHost> hosts = readLocked();
            hosts.remove(findExpectedHost(hosts, expected.id, expected.profile));
            writeLocked(hosts);
            return hosts;
        }
    }

    SavedHost updateQuickCommands(String expectedHostId, HostProfile expectedProfile,
            List<QuickCommand> commands) throws Exception {
        List<QuickCommand> validated = QuickCommand.validatedCopy(commands);
        synchronized (LOCK) {
            List<SavedHost> hosts = readLocked();
            SavedHost updated = replaceQuickCommands(hosts, expectedHostId, expectedProfile, validated);
            writeLocked(hosts);
            return updated;
        }
    }

    static SavedHost replaceQuickCommands(List<SavedHost> hosts, String expectedHostId,
            HostProfile expectedProfile, List<QuickCommand> commands) {
        int index = findExpectedHost(hosts, expectedHostId, expectedProfile);
        SavedHost current = hosts.get(index);
        SavedHost updated = new SavedHost(current.id, current.profile, current.shortcuts, commands);
        hosts.set(index, updated);
        return updated;
    }

    private static int findExpectedHost(List<SavedHost> hosts, String expectedHostId, HostProfile expectedProfile) {
        for (int i = 0; i < hosts.size(); i++) {
            SavedHost current = hosts.get(i);
            if (current.id.equals(expectedHostId)) {
                if (!current.matchesConnection(expectedHostId, expectedProfile)) {
                    throw new IllegalStateException("Saved host connection changed; reopen this host before editing commands");
                }
                return i;
            }
        }
        throw new IllegalStateException("Saved host no longer exists; reopen the host list");
    }

    private void writeLocked(List<SavedHost> hosts) throws Exception {
        if (hosts.size() > 32) throw new IllegalArgumentException("At most 32 hosts can be saved");
        JSONArray values = new JSONArray();
        for (SavedHost host : hosts) values.put(host.toJson());
        byte[] cleartext = new JSONObject().put("version", 1).put("hosts", values).toString().getBytes(StandardCharsets.UTF_8);
        if (cleartext.length > MAX_BYTES - 32) throw new IllegalArgumentException("Host settings are too large");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD("herdr-hosts-v1".getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(cleartext);
        ByteBuffer payload = ByteBuffer.allocate(1 + 12 + encrypted.length);
        payload.put((byte) 1).put(cipher.getIV()).put(encrypted);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(payload.array());
            file.finishWrite(out);
        } catch (Exception error) {
            if (out != null) file.failWrite(out);
            throw error;
        } finally {
            java.util.Arrays.fill(cleartext, (byte) 0);
        }
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException("The encryption key is unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
}
