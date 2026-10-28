package dev.herdr.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-wide encrypted OpenSSH/PEM identity. The key is never part of a host profile. */
final class RemoteIdentityStore {
    private static final String ALIAS = "herdr.remote-identity.v1";
    private static final byte[] AAD = "herdr-remote-identity-v1".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_BYTES = 64 * 1024;
    private static final int HEADER_BYTES = 13;
    private static final String[] BEGIN = {
        "-----BEGIN OPENSSH PRIVATE KEY-----",
        "-----BEGIN PRIVATE KEY-----",
        "-----BEGIN ENCRYPTED PRIVATE KEY-----",
        "-----BEGIN RSA PRIVATE KEY-----",
        "-----BEGIN EC PRIVATE KEY-----",
        "-----BEGIN DSA PRIVATE KEY-----"
    };
    private static final String[] END = {
        "-----END OPENSSH PRIVATE KEY-----",
        "-----END PRIVATE KEY-----",
        "-----END ENCRYPTED PRIVATE KEY-----",
        "-----END RSA PRIVATE KEY-----",
        "-----END EC PRIVATE KEY-----",
        "-----END DSA PRIVATE KEY-----"
    };
    private static final Object LOCK = new Object();
    private final AtomicFile file;

    RemoteIdentityStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "remote-identity-v1.enc"));
    }

    void save(char[] identity) throws Exception {
        synchronized (LOCK) {
            byte[] plaintext = encodeAndValidate(identity);
            byte[] payload = null;
            byte[] encrypted = null;
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key(true));
                cipher.updateAAD(AAD);
                encrypted = cipher.doFinal(plaintext);
                if (cipher.getIV().length != 12) throw new IllegalStateException("Unsupported identity encryption IV");
                payload = ByteBuffer.allocate(HEADER_BYTES + encrypted.length).put((byte) 1)
                        .put(cipher.getIV()).put(encrypted).array();
                FileOutputStream out = null;
                try {
                    out = file.startWrite();
                    out.write(payload);
                    file.finishWrite(out);
                } catch (Exception failure) {
                    if (out != null) file.failWrite(out);
                    throw failure;
                }
            } finally {
                Arrays.fill(plaintext, (byte) 0);
                if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
                if (payload != null) Arrays.fill(payload, (byte) 0);
            }
        }
    }

    char[] read() throws Exception {
        synchronized (LOCK) {
            byte[] payload;
            try (FileInputStream stream = file.openRead()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (count > MAX_BYTES + HEADER_BYTES + 16 - out.size()) {
                        throw new IllegalStateException("Saved SSH identity exceeds 64 KiB");
                    }
                    out.write(buffer, 0, count);
                }
                payload = out.toByteArray();
                Arrays.fill(buffer, (byte) 0);
                out.reset();
            } catch (FileNotFoundException missing) {
                File base = file.getBaseFile();
                if (base.exists() || new File(base.getPath() + ".bak").exists()
                        || new File(base.getPath() + ".new").exists()) throw missing;
                throw new IllegalStateException("No saved SSH identity is available");
            }
            byte[] plaintext = null;
            try {
                if (payload.length < HEADER_BYTES + 16 || payload[0] != 1) {
                    throw new IllegalStateException("Unsupported saved SSH identity format");
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128,
                        Arrays.copyOfRange(payload, 1, HEADER_BYTES)));
                cipher.updateAAD(AAD);
                plaintext = cipher.doFinal(payload, HEADER_BYTES, payload.length - HEADER_BYTES);
                if (plaintext.length == 0 || plaintext.length > MAX_BYTES) {
                    throw new IllegalStateException("Saved SSH identity exceeds 64 KiB");
                }
                validateArmored(plaintext);
                return decodeUtf8(plaintext);
            } finally {
                Arrays.fill(payload, (byte) 0);
                if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    boolean exists() {
        synchronized (LOCK) {
            return fileExists();
        }
    }

    void delete() throws Exception {
        synchronized (LOCK) {
            file.delete();
            if (fileExists()) throw new IllegalStateException("Saved SSH identity could not be removed");
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        }
    }

    private boolean fileExists() {
        File base = file.getBaseFile();
        return base.exists() || new File(base.getPath() + ".bak").exists()
                || new File(base.getPath() + ".new").exists();
    }

    static void validate(char[] identity) throws Exception {
        byte[] bytes = encodeAndValidate(identity);
        Arrays.fill(bytes, (byte) 0);
    }

    private static byte[] encodeAndValidate(char[] identity) throws Exception {
        if (identity == null || identity.length == 0 || identity.length > MAX_BYTES) {
            throw new IllegalArgumentException("SSH identity must be nonempty and at most 64 KiB");
        }
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded;
        try { encoded = encoder.encode(CharBuffer.wrap(identity)); }
        catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("SSH identity must be valid UTF-8 text");
        }
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte) 0);
        try {
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("SSH identity must be at most 64 KiB in UTF-8");
            }
            validateArmored(bytes);
            return bytes;
        } catch (Exception invalid) {
            Arrays.fill(bytes, (byte) 0);
            throw invalid;
        }
    }

    private static char[] decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
        char[] result = new char[decoded.remaining()];
        decoded.get(result);
        if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
        return result;
    }

    private static void validateArmored(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("SSH identity must be nonempty and at most 64 KiB");
        }
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            if (value == '\r') {
                if (i + 1 >= bytes.length || bytes[i + 1] != '\n') {
                    throw new IllegalArgumentException("SSH identity has invalid line endings");
                }
            } else if (value != '\n' && (value < 0x20 || value > 0x7e)) {
                throw new IllegalArgumentException("SSH identity must be valid UTF-8 OpenSSH or PEM text");
            }
        }

        int lineStart = 0;
        int format = -1;
        int endStart = -1;
        int endEnd = -1;
        int bodyStart = -1;
        while (lineStart < bytes.length) {
            int lineEnd = lineEnd(bytes, lineStart);
            if (!blank(bytes, lineStart, lineEnd)) {
                for (int i = 0; i < BEGIN.length; i++) if (equalsAscii(bytes, lineStart, lineEnd, BEGIN[i])) {
                    format = i;
                    bodyStart = nextLine(bytes, lineEnd);
                    break;
                }
                break;
            }
            lineStart = nextLine(bytes, lineEnd);
        }
        if (format < 0) throw new IllegalArgumentException("SSH identity must be an OpenSSH or PEM private key");

        WipingOutput base64 = new WipingOutput();
        byte[] decoded = null;
        try {
            boolean headers = false;
            boolean headerSeparator = false;
            boolean data = false;
            lineStart = bodyStart;
            while (lineStart < bytes.length) {
                int lineEnd = lineEnd(bytes, lineStart);
                if (equalsAscii(bytes, lineStart, lineEnd, END[format])) {
                    endStart = lineStart;
                    endEnd = lineEnd;
                    break;
                }
                if (blank(bytes, lineStart, lineEnd)) {
                    if (headers && !data) headerSeparator = true;
                } else if (!data && format != 0 && !headerSeparator
                        && (startsAscii(bytes, lineStart, lineEnd, "Proc-Type:")
                        || startsAscii(bytes, lineStart, lineEnd, "DEK-Info:"))) {
                    headers = true;
                } else {
                    if (headers && !headerSeparator) {
                        throw new IllegalArgumentException("PEM headers must be separated from key data");
                    }
                    for (int i = lineStart; i < lineEnd; i++) {
                        int value = bytes[i] & 0xff;
                        if (!base64(value)) throw new IllegalArgumentException("SSH identity has invalid key data");
                        base64.write(value);
                    }
                    data = true;
                }
                lineStart = nextLine(bytes, lineEnd);
            }
            if (endStart < 0 || !data || base64.size() < 24 || (base64.size() & 3) != 0) {
                throw new IllegalArgumentException("SSH identity is incomplete or malformed");
            }
            lineStart = nextLine(bytes, endEnd);
            while (lineStart < bytes.length) {
                int lineEnd = lineEnd(bytes, lineStart);
                if (!blank(bytes, lineStart, lineEnd)) {
                    throw new IllegalArgumentException("SSH identity has trailing data");
                }
                lineStart = nextLine(bytes, lineEnd);
            }
            byte[] encodedData = base64.toByteArray();
            try { decoded = java.util.Base64.getDecoder().decode(encodedData); }
            catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("SSH identity has invalid base64 data");
            } finally { Arrays.fill(encodedData, (byte) 0); }
            if (decoded.length < 16 || (format == 0
                    ? !startsAscii(decoded, 0, Math.min(decoded.length, 15), "openssh-key-v1\0")
                    : (decoded[0] & 0xff) != 0x30)) {
                throw new IllegalArgumentException("SSH identity key data has an invalid encoding");
            }
        } finally {
            base64.wipe();
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
    }

    private static int lineEnd(byte[] bytes, int start) {
        int end = start;
        while (end < bytes.length && bytes[end] != '\n') end++;
        if (end > start && bytes[end - 1] == '\r') end--;
        return end;
    }

    private static int nextLine(byte[] bytes, int end) {
        int next = end;
        if (next < bytes.length && bytes[next] == '\r') next++;
        if (next < bytes.length && bytes[next] == '\n') next++;
        return next;
    }

    private static boolean blank(byte[] bytes, int start, int end) {
        for (int i = start; i < end; i++) if (bytes[i] != ' ') return false;
        return true;
    }

    private static boolean equalsAscii(byte[] bytes, int start, int end, String expected) {
        return end - start == expected.length() && startsAscii(bytes, start, end, expected);
    }

    private static boolean startsAscii(byte[] bytes, int start, int end, String expected) {
        if (end - start < expected.length()) return false;
        for (int i = 0; i < expected.length(); i++) if (bytes[start + i] != (byte) expected.charAt(i)) return false;
        return true;
    }

    private static boolean base64(int value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9' || value == '+' || value == '/' || value == '=';
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException("Saved SSH identity encryption key is unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private static final class WipingOutput extends ByteArrayOutputStream {
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
