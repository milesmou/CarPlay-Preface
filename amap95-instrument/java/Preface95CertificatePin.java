import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Exact signing identity of this explicitly re-signed test build. */
public final class Preface95CertificatePin {
    private static final String SHA256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
    public static boolean matches(byte[] certificate) {
        if (certificate == null || certificate.length == 0 || certificate.length > 16384) return false;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate);
            int difference = 0;
            for (int i = 0; i < digest.length; i++) {
                int expected = Integer.parseInt(SHA256.substring(i * 2, i * 2 + 2), 16);
                difference |= (digest[i] & 255) ^ expected;
            }
            return difference == 0;
        } catch (NoSuchAlgorithmException error) { return false; }
    }
}
