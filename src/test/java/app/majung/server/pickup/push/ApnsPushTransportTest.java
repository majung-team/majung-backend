package app.majung.server.pickup.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class ApnsPushTransportTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void disabledByDefaultAndRejectsPartialConfig() {
        Clock fixed = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC);
        assertFalse(new ApnsPushTransport(fixed, "", "", "", "", false, "").enabled());
        assertThrows(IllegalArgumentException.class, () ->
                new ApnsPushTransport(fixed, "KEY123", "", "app.majung.ios", "broken", false, ""));
        assertThrows(IllegalArgumentException.class, () ->
                new ApnsPushTransport(fixed, "KEY123", "TEAM123", "app.majung.ios", "not-a-key", false, ""));
    }

    @Test
    void validatesApnsEndpointOnlyWhenEnabled() {
        String validToken = "a1b2c3d4".repeat(8);
        assertThrows(app.majung.server.pickup.common.ApiException.class,
                () -> PushEndpoint.validate("apns://" + validToken, false));
        assertEquals("apns://" + validToken,
                PushEndpoint.validate("apns://" + validToken.toUpperCase(), true));
        assertThrows(app.majung.server.pickup.common.ApiException.class,
                () -> PushEndpoint.validate("apns://short", true));
        assertThrows(app.majung.server.pickup.common.ApiException.class,
                () -> PushEndpoint.validate("apns://zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", true));
    }

    @Test
    void signsEs256JwtAndHandlesApnsStatuses() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> topicHeader = new AtomicReference<>();
        AtomicReference<String> pathReceived = new AtomicReference<>();
        AtomicReference<String> bodyReceived = new AtomicReference<>();
        AtomicReference<Integer> nextStatus = new AtomicReference<>(200);
        AtomicReference<String> nextBody = new AtomicReference<>("");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("authorization"));
            topicHeader.set(exchange.getRequestHeaders().getFirst("apns-topic"));
            pathReceived.set(exchange.getRequestURI().getPath());
            bodyReceived.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = nextBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(nextStatus.get(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            Clock fixed = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC);
            ApnsPushTransport transport = new ApnsPushTransport(
                    fixed, "KEY1234567", "K2G6G3ZNZQ", "app.majung.ios", pem, false,
                    "http://127.0.0.1:" + server.getAddress().getPort());
            assertTrue(transport.enabled());

            String deviceHex = "0123456789abcdef".repeat(4);
            String payload = "{\"kind\":\"ARRIVED\",\"pickupId\":\"11111111-2222-4333-8444-555555555555\"}";
            assertEquals(PushTransport.Result.SENT, transport.send("apns://" + deviceHex, payload));
            assertEquals("app.majung.ios", topicHeader.get());
            assertEquals("/3/device/" + deviceHex, pathReceived.get());

            JsonNode sentJson = JSON.readTree(bodyReceived.get());
            assertEquals("마중 차량이 도착했어요", sentJson.path("aps").path("alert").path("title").asText());
            assertEquals("ARRIVED", sentJson.path("kind").asText());

            String jwt = authHeader.get().substring("bearer ".length());
            String[] parts = jwt.split("\\.");
            assertEquals(3, parts.length);
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify((ECPublicKey) pair.getPublic());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));

            nextStatus.set(410);
            nextBody.set("{\"reason\":\"Unregistered\"}");
            assertEquals(PushTransport.Result.GONE, transport.send("apns://" + deviceHex, payload));

            nextStatus.set(400);
            nextBody.set("{\"reason\":\"BadDeviceToken\"}");
            assertEquals(PushTransport.Result.GONE, transport.send("apns://" + deviceHex, payload));

            nextStatus.set(503);
            nextBody.set("");
            assertEquals(PushTransport.Result.FAILED, transport.send("apns://" + deviceHex, payload));
        } finally {
            server.stop(0);
        }
    }
}
