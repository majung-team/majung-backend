package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:arrival-push-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "majung.rate-limit.enabled=false"
})
@Import({PickupApiTest.TimeConfiguration.class, ArrivalPushTest.FakePushConfiguration.class})
class ArrivalPushTest {
    static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/test-endpoint-1";

    /** Replaces the real VAPID transport so no test talks to a push service. */
    @TestConfiguration
    static class FakePushConfiguration {
        @Bean
        @Primary
        FakePush fakePush() { return new FakePush(); }
    }

    static class FakePush implements PushTransport {
        volatile boolean enabled = true;
        volatile Result result = Result.SENT;
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override public boolean enabled() { return enabled; }
        @Override public String publicKey() { return "test-public-key"; }
        @Override public Result send(String endpoint) { sent.add(endpoint); return result; }
        void reset() { enabled = true; result = Result.SENT; sent.clear(); }
    }

    @LocalServerPort int port;
    @Autowired PickupApiTest.MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakePush push;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void reset() { push.reset(); clock.reset(); }

    // ---- flow through the pickup API

    @Test
    void sendsOneNoticeWhenTheCarIsThreeMinutesAwayAndNeverAgain() throws Exception {
        Pickup pickup = connected();
        assertEquals(200, subscribe(pickup, pickup.guest, ENDPOINT).status());
        assertEquals(1, subscriptions(pickup.id));
        assertEquals(200, vehicle(pickup, 1, 400).status());
        Thread.sleep(300);
        assertTrue(push.sent.isEmpty(), "ETA above three minutes must not notify");
        assertEquals(200, vehicle(pickup, 2, 170).status());
        await(() -> push.sent.size() == 1);
        assertEquals(ENDPOINT, push.sent.get(0));
        assertEquals(200, vehicle(pickup, 3, 100).status());
        // Re-subscribing, even with a fresh endpoint, does not re-arm the notice.
        assertEquals(200, subscribe(pickup, pickup.guest, ENDPOINT + "-b").status());
        assertEquals(200, vehicle(pickup, 4, 60).status());
        Thread.sleep(400);
        assertEquals(1, push.sent.size());
    }

    @Test
    void confirmedPlaceRearmsNoticeButOldPlaceTelemetryCannotTriggerIt() throws Exception {
        Pickup pickup = connected();
        subscribe(pickup, pickup.guest, ENDPOINT);
        vehicle(pickup, 1, 100);
        await(() -> push.sent.size() == 1);
        Map<String, Object> place = Map.of("label", "새 시험 장소", "latitude", 37.002, "longitude", 127.003);
        JsonNode proposal = call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", place)).body();
        assertEquals(200, call("PUT", pickup.path() + "/place-confirmation", pickup.owner,
                Map.of("proposalId", proposal.path("placeProposal").path("proposalId").asText(),
                        "expectedPlaceVersion", 1, "place", place, "durationSeconds", 100,
                        "sourceTime", clock.instant().toString())).status());
        assertEquals(409, vehicle(pickup, 2, 90).status());
        assertEquals(1, push.sent.size());
        assertEquals(200, call("PUT", pickup.path() + "/vehicle-state", pickup.owner,
                Map.of("latitude", 37.01, "longitude", 127.01, "durationSeconds", 90,
                        "sourceTime", clock.instant().toString(), "sequence", 2, "placeVersion", 2)).status());
        await(() -> push.sent.size() == 2);
        assertEquals(200, call("PUT", pickup.path() + "/vehicle-state", pickup.owner,
                Map.of("latitude", 37.01, "longitude", 127.01, "durationSeconds", 80,
                        "sourceTime", clock.instant().toString(), "sequence", 3, "placeVersion", 2)).status());
        Thread.sleep(300);
        assertEquals(2, push.sent.size());
    }

    @Test
    void doesNotNotifyWithoutASubscriptionOrAfterUnsubscribing() throws Exception {
        Pickup pickup = connected();
        assertEquals(200, vehicle(pickup, 1, 100).status());
        Thread.sleep(300);
        assertTrue(push.sent.isEmpty());
        Pickup other = connected();
        subscribe(other, other.guest, ENDPOINT);
        JsonNode off = call("DELETE", other.path() + "/arrival-push", other.guest, null).body();
        assertFalse(off.path("subscribed").asBoolean());
        assertEquals(0, subscriptions(other.id));
        vehicle(other, 1, 100);
        Thread.sleep(300);
        assertTrue(push.sent.isEmpty());
    }

    @Test
    void onlyTheConnectedPassengerCanSubscribeAndEndpointsAreRestricted() throws Exception {
        Pickup pickup = connected();
        assertEquals(403, subscribe(pickup, pickup.owner, ENDPOINT).status());
        assertEquals(401, call("PUT", pickup.path() + "/arrival-push", null, Map.of("endpoint", ENDPOINT)).status());
        for (String bad : List.of("http://fcm.googleapis.com/fcm/send/x", "https://127.0.0.1/push/x",
                "https://localhost/push/x", "https://169.254.169.254/latest", "https://evil.example.com/push/x",
                "https://fcm.googleapis.com.evil.example.com/x", "https://user:pw@fcm.googleapis.com/fcm/send/x",
                "https://fcm.googleapis.com:8443/fcm/send/x", "not a url")) {
            Response response = subscribe(pickup, pickup.guest, bad);
            assertEquals(400, response.status(), bad);
            assertEquals("INVALID_PUSH_ENDPOINT", response.body().path("code").asText(), bad);
        }
        assertEquals(0, subscriptions(pickup.id));
        // Nobody is connected yet on a fresh invitation, so a notice cannot be requested for it.
        Response created = call("POST", "/api/v1/pickups", null,
                Map.of("place", Map.of("label", "시험 장소", "latitude", 37.0, "longitude", 127.0)));
        String id = created.body().path("pickupId").asText();
        assertEquals(403, call("PUT", "/api/v1/pickups/" + id + "/arrival-push", created.body().path("driverToken").asText(),
                Map.of("endpoint", ENDPOINT)).status());
    }

    @Test
    void removesTheSubscriptionWhenThePassengerIsDisconnectedOrThePickupEnds() throws Exception {
        Pickup pickup = connected();
        subscribe(pickup, pickup.guest, ENDPOINT);
        assertEquals(200, call("DELETE", pickup.path() + "/participant", pickup.owner, null).status());
        assertEquals(0, subscriptions(pickup.id));

        Pickup ending = connected();
        subscribe(ending, ending.guest, ENDPOINT);
        assertEquals(200, call("POST", ending.path() + "/cancellation", ending.owner, null).status());
        assertEquals(0, subscriptions(ending.id));
        assertEquals(0, push.sent.size());
    }

    @Test
    void dropsSubscriptionsTheBrowserVendorReportsGone() throws Exception {
        Pickup pickup = connected();
        subscribe(pickup, pickup.guest, ENDPOINT);
        push.result = PushTransport.Result.GONE;
        vehicle(pickup, 1, 90);
        await(() -> subscriptions(pickup.id) == 0);
        assertEquals(1, push.sent.size());
    }

    @Test
    void featureIsOffUntilKeysAreConfigured() throws Exception {
        Pickup pickup = connected();
        push.enabled = false;
        Response off = subscribe(pickup, pickup.guest, ENDPOINT);
        assertEquals(404, off.status());
        assertEquals("PUSH_DISABLED", off.body().path("code").asText());
        assertTrue(call("GET", "/api/v1/config", null, null).body().path("webPushPublicKey").isNull());
        push.enabled = true;
        assertEquals("test-public-key", call("GET", "/api/v1/config", null, null).body().path("webPushPublicKey").asText());
    }

    // ---- transport and endpoint rules

    @Test
    void signsVapidRequestsAndMapsPushServiceAnswers() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();
        byte[] point = new byte[65];
        point[0] = 4;
        System.arraycopy(fixed(publicKey.getW().getAffineX()), 0, point, 1, 32);
        System.arraycopy(fixed(publicKey.getW().getAffineY()), 0, point, 33, 32);
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String publicText = url.encodeToString(point);
        Instant now = Instant.parse("2026-10-26T01:00:00Z");
        VapidPushTransport transport = new VapidPushTransport(Clock.fixed(now, ZoneOffset.UTC), publicText,
                url.encodeToString(fixed(((ECPrivateKey) pair.getPrivate()).getS())), "mailto:ops@example.test");
        assertTrue(transport.enabled());
        assertEquals(publicText, transport.publicKey());

        AtomicReference<com.sun.net.httpserver.HttpExchange> seen = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<Integer> status = new AtomicReference<>(201);
        AtomicReference<Integer> bodyBytes = new AtomicReference<>(-1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seen.set(exchange);
            bodyBytes.set(exchange.getRequestBody().readAllBytes().length);
            if (status.get() == 302) exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/push/abc";
            assertEquals(PushTransport.Result.SENT, transport.send(endpoint));
            assertEquals(0, bodyBytes.get(), "no payload is sent");
            assertEquals("600", seen.get().getRequestHeaders().getFirst("TTL"));
            assertEquals("high", seen.get().getRequestHeaders().getFirst("Urgency"));
            String header = authorization.get();
            assertTrue(header.startsWith("vapid t="), header);
            assertTrue(header.endsWith(", k=" + publicText), header);
            String[] jwt = header.substring("vapid t=".length(), header.indexOf(", k=")).split("\\.");
            assertEquals(3, jwt.length);
            Base64.Decoder decode = Base64.getUrlDecoder();
            assertEquals("{\"typ\":\"JWT\",\"alg\":\"ES256\"}", new String(decode.decode(jwt[0]), StandardCharsets.UTF_8));
            JsonNode claims = json.readTree(decode.decode(jwt[1]));
            assertEquals("http://127.0.0.1:" + server.getAddress().getPort(), claims.path("aud").asText());
            assertEquals("mailto:ops@example.test", claims.path("sub").asText());
            assertEquals(now.plus(Duration.ofHours(12)).getEpochSecond(), claims.path("exp").asLong());
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
            var key = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(
                    new BigInteger(1, java.util.Arrays.copyOfRange(point, 1, 33)),
                    new BigInteger(1, java.util.Arrays.copyOfRange(point, 33, 65))), curve));
            Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
            verify.initVerify(key);
            verify.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
            assertTrue(verify.verify(decode.decode(jwt[2])), "VAPID signature must verify with the public key");

            status.set(410);
            assertEquals(PushTransport.Result.GONE, transport.send(endpoint));
            status.set(404);
            assertEquals(PushTransport.Result.GONE, transport.send(endpoint));
            status.set(500);
            assertEquals(PushTransport.Result.FAILED, transport.send(endpoint));
            status.set(302);
            assertEquals(PushTransport.Result.FAILED, transport.send(endpoint), "redirects are not followed");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsIncompleteOrMalformedVapidConfiguration() {
        Clock fixed = Clock.systemUTC();
        assertFalse(new VapidPushTransport(fixed, "", "", "").enabled());
        assertThrows(IllegalArgumentException.class, () -> new VapidPushTransport(fixed, "AAAA", "", "mailto:a@b.test"));
        assertThrows(IllegalArgumentException.class, () -> new VapidPushTransport(fixed, "AAAA", "BBBB", ""));
        assertThrows(IllegalArgumentException.class, () -> new VapidPushTransport(fixed, "AAAA", "BBBB", "mailto:a@b.test"));
        assertThrows(IllegalArgumentException.class, () -> new VapidPushTransport(fixed, "%%%", "%%%", "mailto:a@b.test"));
    }

    @Test
    void acceptsOnlyTheBrowserVendorsPushServices() {
        for (String ok : List.of("https://fcm.googleapis.com/fcm/send/abc", "https://updates.push.services.mozilla.com/wpush/v2/abc",
                "https://web.push.apple.com/QAbc", "https://par02p.notify.windows.com/w/?token=abc")) {
            assertEquals(ok, PushEndpoint.validate(ok));
        }
        for (String bad : List.of("", "   ", "https://fcm.googleapis.com", "https://fcm.googleapis.com/", "ftp://fcm.googleapis.com/x",
                "https://fcm.googleapis.com/x#frag", "https://" + "a".repeat(1100) + ".push.apple.com/x")) {
            assertThrows(ApiException.class, () -> PushEndpoint.validate(bad), bad);
        }
        assertThrows(ApiException.class, () -> PushEndpoint.validate(null));
    }

    // ---- helpers

    record Pickup(String id, String owner, String guest) {
        String path() { return "/api/v1/pickups/" + id; }
    }

    record Response(int status, JsonNode body) { }

    private Pickup connected() throws Exception {
        Response created = call("POST", "/api/v1/pickups", null,
                Map.of("place", Map.of("label", "시험 장소", "latitude", 37.0, "longitude", 127.0)));
        assertEquals(201, created.status());
        String id = created.body().path("pickupId").asText();
        Response joined = call("POST", "/api/v1/pickups/" + id + "/participants", null,
                Map.of("inviteToken", created.body().path("inviteToken").asText()));
        assertEquals(201, joined.status());
        return new Pickup(id, created.body().path("driverToken").asText(), joined.body().path("participantToken").asText());
    }

    private Response subscribe(Pickup pickup, String token, String endpoint) throws Exception {
        return call("PUT", pickup.path() + "/arrival-push", token, Map.of("endpoint", endpoint));
    }

    private Response vehicle(Pickup pickup, long sequence, long eta) throws Exception {
        return call("PUT", pickup.path() + "/vehicle-state", pickup.owner, Map.of("latitude", 37.01, "longitude", 127.01,
                "durationSeconds", eta, "sourceTime", clock.instant().toString(), "sequence", sequence));
    }

    private int subscriptions(String pickupId) {
        return jdbc.queryForObject("select count(*) from arrival_push where pickup_id = ?", Integer.class, pickupId);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), "condition not reached in time");
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private Response call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), json.readTree(response.body()));
    }
}
