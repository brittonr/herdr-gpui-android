package dev.herdr.android;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
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

    private static String armor(String label, byte[] body) {
        return "-----BEGIN " + label + "-----\n" + Base64.getEncoder().encodeToString(body)
                + "\n-----END " + label + "-----\n";
    }

    private static void reject(char[] input) throws Exception {
        try { RemoteIdentityStore.validate(input); }
        catch (IllegalArgumentException expected) { return; }
        fail("Expected invalid SSH identity to be rejected");
    }

    private static void reject(String input) throws Exception { reject(input.toCharArray()); }
}
