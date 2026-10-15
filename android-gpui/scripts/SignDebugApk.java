/* Local verification builds only; this is not a release-signing workflow. */
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

public final class SignDebugApk {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: SignDebugApk <keystore> <unsigned.apk> <signed.apk> <minSdk>");
        }
        int minSdk = Integer.parseInt(args[3]);
        KeyStore keys = KeyStore.getInstance("JKS");
        try (FileInputStream input = new FileInputStream(args[0])) {
            keys.load(input, "android".toCharArray());
        }
        PrivateKey key = (PrivateKey) keys.getKey("androiddebugkey", "android".toCharArray());
        X509Certificate certificate = (X509Certificate) keys.getCertificate("androiddebugkey");
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "androiddebugkey", key, Collections.singletonList(certificate)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(new File(args[1]))
                .setOutputApk(new File(args[2]))
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setAlignmentPreserved(false)
                .setLibraryPageAlignmentBytes(16384)
                .setCreatedBy("Herdr local verification build")
                .build().sign();
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[2]))
                // Check from API 24 so both v2 and v3 are actually exercised even
                // when the application itself requires Android API 29 or newer.
                .setMinCheckedPlatformVersion(Math.min(minSdk, 24)).build().verify();
        for (ApkVerifier.IssueWithParams warning : result.getWarnings()) {
            System.err.println("Signature warning: " + warning);
        }
        if (!result.isVerified() || !result.isVerifiedUsingV2Scheme()
                || !result.isVerifiedUsingV3Scheme()) {
            throw new IllegalStateException("APK signature verification failed: verified=" + result.isVerified()
                    + ", v2=" + result.isVerifiedUsingV2Scheme()
                    + ", v3=" + result.isVerifiedUsingV3Scheme() + "; " + result.getAllErrors());
        }
        System.out.println("APK signature verified: v2=" + result.isVerifiedUsingV2Scheme()
                + ", v3=" + result.isVerifiedUsingV3Scheme());
    }
}
