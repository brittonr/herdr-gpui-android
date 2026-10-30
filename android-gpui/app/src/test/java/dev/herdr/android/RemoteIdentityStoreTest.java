package dev.herdr.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class RemoteIdentityStoreTest {
    @Test public void acceptsOpenSshAndPemArmorWithoutMutatingCallerChars() throws Exception {
        byte[] openssh = "openssh-key-v1\0x".getBytes(StandardCharsets.US_ASCII);
        char[] key = armor("OPENSSH PRIVATE KEY", openssh).replace("\n", "\r\n").toCharArray();
        char[] original = key.clone();
        RemoteIdentityStore.validate(key);
        assertArrayEquals(original, key);

        byte[] pem = new byte[16];
        pem[0] = 0x30;
        RemoteIdentityStore.validate(armor("PRIVATE KEY", pem).toCharArray());
    }

    @Test public void rejectsMismatchedOrInvalidArmoredKeyData() throws Exception {
        byte[] openssh = "openssh-key-v1\0x".getBytes(StandardCharsets.US_ASCII);
        String valid = armor("OPENSSH PRIVATE KEY", openssh);
        reject(valid.replace("END OPENSSH PRIVATE KEY", "END PRIVATE KEY"));
        reject(valid.replace("OPENSSH PRIVATE KEY", "OPENSSH PRIVATE KEY")
                .replace(Base64.getEncoder().encodeToString(openssh), "not-base64!"));
        reject("-----BEGIN PRIVATE KEY-----\nYWJjZGVmZ2hpamtsbW5vcA==\n-----END PRIVATE KEY-----\n");
        reject(valid + "unexpected trailing text\n");
        reject("-----BEGIN OPENSSH PRIVATE KEY-----\n\ud800\n-----END OPENSSH PRIVATE KEY-----\n");
    }

    @Test public void enforcesTheUtf8ByteLimitAndValidUnicodeInput() throws Exception {
        char[] oversized = new char[64 * 1024 + 1];
        reject(oversized);
        char[] multibyteOversized = new char[32 * 1024 + 1];
        java.util.Arrays.fill(multibyteOversized, '\u00e9');
        try { RemoteIdentityStore.validate(multibyteOversized); fail("Expected UTF-8 byte overflow to be rejected"); }
        catch (IllegalArgumentException expected) { }
        byte[] openssh = "openssh-key-v1\0x".getBytes(StandardCharsets.US_ASCII);
        char[] malformedUtf16 = armor("OPENSSH PRIVATE KEY", openssh).toCharArray();
        malformedUtf16[malformedUtf16.length - 2] = '\ud800';
        reject(malformedUtf16);
    }

    @Test public void tellsFromTheKeyItselfWhetherSigningInNeedsItsPassphrase() throws Exception {
        assertFalse(needsPassphrase(armor("OPENSSH PRIVATE KEY", openSsh("none"))));
        assertTrue(needsPassphrase(armor("OPENSSH PRIVATE KEY", openSsh("aes256-ctr"))));
        // Too short to name a cipher: not known to be encrypted, so sign-in still tries it.
        assertFalse(needsPassphrase(armor("OPENSSH PRIVATE KEY",
                "openssh-key-v1\0x".getBytes(StandardCharsets.US_ASCII))));
        byte[] der = new byte[16];
        der[0] = 0x30;
        assertFalse(needsPassphrase(armor("PRIVATE KEY", der)));
        assertTrue(needsPassphrase(armor("ENCRYPTED PRIVATE KEY", der)));
        assertFalse(needsPassphrase(armor("RSA PRIVATE KEY", der)));
        assertTrue(needsPassphrase(armor("RSA PRIVATE KEY", "Proc-Type: 4,ENCRYPTED\n"
                + "DEK-Info: AES-128-CBC,00112233445566778899AABBCCDDEEFF\n\n", der)));
        // Sign-in goes on to use the same characters, so the check must leave them intact.
        char[] key = armor("OPENSSH PRIVATE KEY", openSsh("none")).toCharArray();
        char[] original = key.clone();
        RemoteIdentityStore.needsPassphrase(key);
        assertArrayEquals(original, key);
    }

    private static String armor(String label, byte[] body) {
        return armor(label, "", body);
    }

    /** A PEM block whose `headers` sit between its first line and the body. */
    private static String armor(String label, String headers, byte[] body) {
        return "-----BEGIN " + label + "-----\n" + headers + Base64.getEncoder().encodeToString(body)
                + "\n-----END " + label + "-----\n";
    }

    /** The start of an openssh-key-v1 container: magic, cipher, KDF and its options, key count. */
    private static byte[] openSsh(String cipher) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII), 0, 15);
        for (String field : new String[] {cipher, "none".equals(cipher) ? "none" : "bcrypt", ""}) {
            byte[] bytes = field.getBytes(StandardCharsets.US_ASCII);
            out.write(new byte[] {0, 0, 0, (byte) bytes.length}, 0, 4);
            out.write(bytes, 0, bytes.length);
        }
        out.write(new byte[] {0, 0, 0, 1}, 0, 4);
        return out.toByteArray();
    }

    private static boolean needsPassphrase(String key) throws Exception {
        return RemoteIdentityStore.needsPassphrase(key.toCharArray());
    }

    private static void reject(char[] input) throws Exception {
        try { RemoteIdentityStore.validate(input); }
        catch (IllegalArgumentException expected) { return; }
        fail("Expected invalid SSH identity to be rejected");
    }

    private static void reject(String input) throws Exception { reject(input.toCharArray()); }
}
