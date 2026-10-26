import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/** Prints a VAPID P-256 key pair in the URL-safe Base64 form the server and browsers use. Run: java deploy/VapidKeys.java */
public class VapidKeys {
    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        byte[] point = new byte[65];
        point[0] = 4;
        System.arraycopy(fixed(publicKey.getW().getAffineX()), 0, point, 1, 32);
        System.arraycopy(fixed(publicKey.getW().getAffineY()), 0, point, 33, 32);
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        System.out.println("VAPID_PUBLIC_KEY=" + url.encodeToString(point));
        System.out.println("VAPID_PRIVATE_KEY=" + url.encodeToString(fixed(((ECPrivateKey) pair.getPrivate()).getS())));
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }
}
