package app.majung.server.pickup;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Web Push with VAPID (RFC 8292) and no payload, so no content encryption is involved. */
@Component
class VapidPushTransport implements PushTransport {
    private static final Logger log = LoggerFactory.getLogger(VapidPushTransport.class);
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);
    private final Clock clock;
    private final String publicKey;
    private final String subject;
    private final PrivateKey privateKey;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    VapidPushTransport(Clock clock,
                       @Value("${majung.push.vapid-public-key:}") String publicKey,
                       @Value("${majung.push.vapid-private-key:}") String privateKey,
                       @Value("${majung.push.subject:}") String subject) {
        this.clock = clock;
        if (publicKey.isBlank() && privateKey.isBlank() && subject.isBlank()) {
            this.publicKey = null;
            this.subject = null;
            this.privateKey = null;
            return;
        }
        // Half-configured push is a deployment mistake; fail at startup without echoing any secret.
        if (publicKey.isBlank() || privateKey.isBlank() || !(subject.startsWith("mailto:") || subject.startsWith("https://"))) {
            throw new IllegalArgumentException("Web push needs a VAPID key pair and a mailto: or https: subject");
        }
        try {
            byte[] point = Base64.getUrlDecoder().decode(publicKey.strip());
            byte[] scalar = Base64.getUrlDecoder().decode(privateKey.strip());
            if (point.length != 65 || point[0] != 4 || scalar.length != 32) throw new IllegalArgumentException("length");
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
            this.privateKey = KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), curve));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Web push VAPID keys are not valid URL-safe Base64 P-256 keys");
        }
        this.publicKey = publicKey.strip();
        this.subject = subject;
    }

    @Override
    public boolean enabled() { return privateKey != null; }

    @Override
    public String publicKey() { return publicKey; }

    @Override
    public Result send(String endpoint) {
        if (!enabled()) return Result.FAILED;
        try {
            URI uri = URI.create(endpoint);
            String audience = uri.getScheme() + "://" + uri.getAuthority();
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .header("Authorization", "vapid t=" + token(audience) + ", k=" + publicKey)
                    .header("TTL", "600").header("Urgency", "high")
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) return Result.SENT;
            // The endpoint is a capability URL, so only the status is logged.
            log.warn("Arrival push rejected: status={}", status);
            return status == 404 || status == 410 ? Result.GONE : Result.FAILED;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Arrival push failed: {}", e.getClass().getSimpleName());
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        }
    }

    private String token(String audience) throws GeneralSecurityException {
        String header = URL.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        long expires = clock.instant().plus(TOKEN_LIFETIME).getEpochSecond();
        String claims = URL.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + expires + ",\"sub\":\"" + subject + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(privateKey);
        signature.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + URL.encodeToString(signature.sign());
    }
}
