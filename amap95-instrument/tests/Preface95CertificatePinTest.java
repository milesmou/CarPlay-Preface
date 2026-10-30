import java.nio.file.Files;
import java.nio.file.Paths;
public final class Preface95CertificatePinTest {
    public static void main(String[] args) throws Exception {
        byte[] cert = Files.readAllBytes(Paths.get(args[0]));
        if (!Preface95CertificatePin.matches(cert)) throw new AssertionError("Reviewed signer rejected");
        cert[cert.length - 1] ^= 1;
        if (Preface95CertificatePin.matches(cert) || Preface95CertificatePin.matches(null) ||
                Preface95CertificatePin.matches(new byte[0]) || Preface95CertificatePin.matches(new byte[16385])) {
            throw new AssertionError("Unknown/malformed signer accepted");
        }
        System.out.println("Certificate pin: exact reviewed signer accepted; changed/empty/oversized certificate rejected");
    }
}
