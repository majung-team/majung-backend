package app.majung.server.pickup.push;

import app.majung.server.pickup.common.ApiException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Apple Push Notification service HTTP/2 ES256 provider transport.
 * Leave all APNs configuration properties blank to keep APNs disabled safely.
 */
@Component
public class ApnsPushTransport {
    private static final Logger log = LoggerFactory.getLogger(ApnsPushTransport.class);
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration JWT_REFRESH = Duration.ofMinutes(45);

    private final Clock clock;
    private final String keyId;
    private final String teamId;
    private final String bundleId;
    private final String baseUrl;
    private final PrivateKey privateKey;
    private final HttpClient client;
    private String cachedJwt;
    private long cachedAtEpochSecond;

    public ApnsPushTransport(
            Clock clock,
            @Value("${majung.apns.key-id:}") String keyId,
            @Value("${majung.apns.team-id:}") String teamId,
            @Value("${majung.apns.bundle-id:}") String bundleId,
            @Value("${majung.apns.private-key:}") String privateKeyPem,
            @Value("${majung.apns.sandbox:false}") boolean sandbox,
            @Value("${majung.apns.base-url:}") String overrideBaseUrl) {
        this.clock = clock;
        if (keyId.isBlank() && teamId.isBlank() && bundleId.isBlank() && privateKeyPem.isBlank()) {
            this.keyId = null;
            this.teamId = null;
            this.bundleId = null;
            this.baseUrl = null;
            this.privateKey = null;
            this.client = null;
            return;
        }
        if (keyId.isBlank() || teamId.isBlank() || bundleId.isBlank() || privateKeyPem.isBlank()) {
            throw new IllegalArgumentException("APNs requires key-id, team-id, bundle-id and a PKCS#8 ES256 private key");
        }
        this.keyId = keyId.strip();
        this.teamId = teamId.strip();
        this.bundleId = bundleId.strip();
        String chosenBase = !overrideBaseUrl.isBlank()
                ? overrideBaseUrl.strip()
                : (sandbox ? "https://api.sandbox.push.apple.com" : "https://api.push.apple.com");
        while (chosenBase.endsWith("/")) {
            chosenBase = chosenBase.substring(0, chosenBase.length() - 1);
        }
        this.baseUrl = chosenBase;
        this.privateKey = parsePkcs8(privateKeyPem);
        this.client = HttpClient.newBuilder()
                .version(overrideBaseUrl.isBlank() ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean enabled() {
        return privateKey != null;
    }

    public PushTransport.Result send(String apnsEndpoint, String payloadJson) {
        if (!enabled() || apnsEndpoint == null || !apnsEndpoint.startsWith("apns://")) {
            return PushTransport.Result.FAILED;
        }
        String token = apnsEndpoint.substring("apns://".length());
        if (!token.matches("[0-9a-f]{64,256}")) {
            return PushTransport.Result.GONE;
        }
        try {
            String apnsBody = buildAlertPayload(payloadJson);
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/3/device/" + token))
                    .timeout(Duration.ofSeconds(8))
                    .header("authorization", "bearer " + bearerToken())
                    .header("apns-topic", bundleId)
                    .header("apns-push-type", "alert")
                    .header("apns-priority", "10")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(apnsBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return PushTransport.Result.SENT;
            }
            String reason = extractReason(response.body());
            log.warn("APNs arrival push rejected: status={}, reason={}", status, reason);
            if (status == 410 || "BadDeviceToken".equals(reason) || "Unregistered".equals(reason)) {
                return PushTransport.Result.GONE;
            }
            return PushTransport.Result.FAILED;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            log.warn("APNs arrival push failed: {}", e.getClass().getSimpleName());
            return PushTransport.Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PushTransport.Result.FAILED;
        }
    }

    private String buildAlertPayload(String rawPayload) {
        boolean arrived = false;
        String pickupId = "";
        if (rawPayload != null && !rawPayload.isBlank()) {
            try {
                JsonNode node = JSON.readTree(rawPayload);
                arrived = "ARRIVED".equals(node.path("kind").asText(""));
                pickupId = node.path("pickupId").asText("");
            } catch (Exception ignored) { }
        }
        String title = arrived ? "마중 차량이 도착했어요" : "곧 마중 차량이 도착해요";
        String body = arrived ? "약속 장소에서 차량을 확인하고 탑승해주세요." : "도착 약 3분 전이에요. 탑승을 준비해주세요.";
        var alert = JSON.createObjectNode().put("title", title).put("body", body);
        var aps = JSON.createObjectNode();
        aps.set("alert", alert);
        aps.put("sound", "default");
        var root = JSON.createObjectNode();
        root.set("aps", aps);
        root.put("kind", arrived ? "ARRIVED" : "APPROACHING");
        if (!pickupId.isBlank()) {
            root.put("pickupId", pickupId);
        }
        return JSON.writeValueAsString(root);
    }

    private static String extractReason(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return "";
        try {
            return JSON.readTree(responseBody).path("reason").asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private synchronized String bearerToken() throws GeneralSecurityException {
        long nowSeconds = clock.instant().getEpochSecond();
        if (cachedJwt != null && (nowSeconds - cachedAtEpochSecond) < JWT_REFRESH.toSeconds()) {
            return cachedJwt;
        }
        String headerJson = JSON.writeValueAsString(
                JSON.createObjectNode().put("alg", "ES256").put("kid", keyId));
        String claimsJson = JSON.writeValueAsString(
                JSON.createObjectNode().put("iss", teamId).put("iat", nowSeconds));
        String unsigned = URL.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
                + "." + URL.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(privateKey);
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        cachedJwt = unsigned + "." + URL.encodeToString(signature.sign());
        cachedAtEpochSecond = nowSeconds;
        return cachedJwt;
    }

    private static PrivateKey parsePkcs8(String pem) {
        try {
            String stripped = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] der = Base64.getDecoder().decode(stripped);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("APNs private key must be a valid PKCS#8 EC key", e);
        }
    }
}
