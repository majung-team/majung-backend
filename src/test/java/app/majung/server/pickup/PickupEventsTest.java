package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:pickup-events-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "majung.rate-limit.enabled=false"
})
@Import(PickupApiTest.TimeConfiguration.class)
class PickupEventsTest {
    private static final Map<String, Object> PLACE = Map.of("label", "예시 장소", "latitude", 37.0, "longitude", 127.0);
    private static final Map<String, Object> OTHER = Map.of("label", "정문 앞", "latitude", 37.001, "longitude", 127.002);

    @LocalServerPort int port;
    @Autowired PickupApiTest.MutableClock clock;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void reset() { clock.reset(); }

    @Test void streamRechecksAccessAndDeliversReadinessChanges() throws Exception {
        Pickup pickup = connected();
        assertEquals(401, call("GET", pickup.path() + "/events", null, null).status());
        assertEquals(403, call("GET", pickup.path() + "/events", "z".repeat(43), null).status());
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pickup.path() + "/events"))
                .header("Authorization", "Bearer " + pickup.guest).header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/event-stream"));
        assertEquals("no-store", response.headers().firstValue("cache-control").orElse(""));
        var frames = new java.util.concurrent.LinkedBlockingQueue<String>();
        try (var body = response.body()) {
            Thread reader = new Thread(() -> {
                try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(body))) {
                    String line;
                    while ((line = lines.readLine()) != null) if (!line.isBlank()) frames.add(line);
                } catch (java.io.IOException ignored) { }
            });
            reader.setDaemon(true); reader.start();
            assertEquals("data:refresh", awaitLine(frames, "data:"));
            assertEquals(200, call("PUT", pickup.path() + "/readiness", pickup.guest,
                    Map.of("readiness", "READY", "sequence", 1)).status());
            assertEquals("data:refresh", awaitLine(frames, "data:"));
            assertEquals("READY", get(pickup, pickup.guest).path("participant").path("readiness").asText());
            assertEquals(200, call("DELETE", pickup.path() + "/participant", pickup.owner, null).status());
            assertEquals("event:access-ended", awaitLine(frames, "event:access-ended"));
        }
    }

    @Test void limitsConcurrentStreamsForTheSamePickup() throws Exception {
        Pickup pickup = connected();
        var opened = new java.util.ArrayList<java.io.InputStream>();
        try {
            for (int index = 0; index < 4; index++) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pickup.path() + "/events"))
                        .header("Authorization", "Bearer " + pickup.guest).header("Accept", "text/event-stream").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, response.statusCode()); opened.add(response.body());
            }
            assertEquals(429, call("GET", pickup.path() + "/events", pickup.guest, null).status());
        } finally {
            call("POST", pickup.path() + "/cancellation", pickup.owner, null);
            for (var body : opened) body.close();
        }
    }

    private String awaitLine(java.util.concurrent.BlockingQueue<String> lines, String prefix) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < until) {
            String line = lines.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith(prefix)) return line;
        }
        throw new AssertionError("SSE event did not arrive");
    }

    private record Pickup(String id, String owner, String guest) {
        String path() { return "/api/v1/pickups/" + id; }
    }
    private record Response(int status, JsonNode body) { }

    private Pickup connected() throws Exception {
        JsonNode created = call("POST", "/api/v1/pickups", null, Map.of("place", PLACE)).body();
        String path = "/api/v1/pickups/" + created.path("pickupId").asText();
        JsonNode joined = call("POST", path + "/participants", null,
                Map.of("inviteToken", created.path("inviteToken").asText())).body();
        return new Pickup(created.path("pickupId").asText(), created.path("driverToken").asText(),
                joined.path("participantToken").asText());
    }

    private JsonNode get(Pickup pickup, String token) throws Exception {
        return call("GET", pickup.path(), token, null).body();
    }

    private Response call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream, application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), json.readTree(response.body()));
    }
}
