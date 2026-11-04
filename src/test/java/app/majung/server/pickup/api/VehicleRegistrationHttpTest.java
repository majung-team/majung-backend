package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:vehicle-registration;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
        "majung.admin-key=test-admin-key-not-a-secret-0123456789",
        "majung.development-tools=false", "majung.rate-limit.enabled=false"
})
@Import(PickupApiTest.TimeConfiguration.class)
class VehicleRegistrationHttpTest {
    private static final String ADMIN = "test-admin-key-not-a-secret-0123456789";
    private static final String PLACE = "{\"place\":{\"label\":\"예시 장소\",\"latitude\":37,\"longitude\":127}}";
    @LocalServerPort int port;
    @Autowired PickupApiTest.MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    @BeforeEach void reset() { clock.reset(); }

    @Test void adminKeyDoesNotCreatePickupAndRegistrationSecretsAreNotStoredRaw() throws Exception {
        assertEquals(403, call("POST", "/api/v1/admin/vehicle-registration-codes", null, null).statusCode());
        assertEquals(403, call("POST", "/api/v1/admin/vehicle-registration-codes", "wrong", null).statusCode());
        assertEquals(401, call("POST", "/api/v1/pickups", null, PLACE).statusCode());
        assertEquals(401, call("POST", "/api/v1/pickups", ADMIN, PLACE).statusCode());
        JsonNode code = issue();
        JsonNode vehicle = register(code.path("code").asText());
        JsonNode record = tree(call("GET", "/api/v1/admin/vehicle-registration-codes/" + code.path("registrationCodeId").asText(), ADMIN, null));
        assertEquals(vehicle.path("vehicleId").asText(), record.path("vehicleId").asText());
        assertFalse(record.toString().contains(code.path("code").asText()));
        assertEquals(43, vehicle.path("vehicleToken").asText().length());
        assertFalse(jdbc.queryForList("select code_hash from vehicle_registration_code", String.class).contains(code.path("code").asText()));
        assertFalse(jdbc.queryForList("select credential_hash from registered_vehicle", String.class).contains(vehicle.path("vehicleToken").asText()));
        String token = vehicle.path("vehicleToken").asText();
        assertEquals(201, call("POST", "/api/v1/pickups", token, PLACE).statusCode());
        assertEquals(201, call("POST", "/api/v1/pickups", token, PLACE).statusCode());
        HttpResponse<String> me = call("GET", "/api/v1/vehicles/me", token, null);
        assertEquals(200, me.statusCode());
        assertFalse(me.body().contains(token));
        assertEquals("no-store", me.headers().firstValue("Cache-Control").orElseThrow());
    }

    @Test void expiredAndReusedCodesCannotRegister() throws Exception {
        String code = issue().path("code").asText();
        register(code);
        assertEquals(410, registration(code).statusCode());
        String expired = issue().path("code").asText();
        clock.advance(Duration.ofMinutes(10));
        assertEquals(410, registration(expired).statusCode());
        assertEquals(410, registration("Z".repeat(43)).statusCode());
        assertEquals(400, registration("short").statusCode());
    }

    @Test void concurrentRegistrationConsumesCodeOnce() throws Exception {
        String code = issue().path("code").asText();
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> attempts = List.of(() -> registration(code).statusCode(), () -> registration(code).statusCode());
            var futures = executor.invokeAll(attempts);
            List<Integer> statuses = List.of(futures.get(0).get(), futures.get(1).get()).stream().sorted().toList();
            assertEquals(List.of(201, 410), statuses);
        } finally { executor.shutdownNow(); }
    }

    @Test void revocationStopsOnlyThatVehiclesPickupAndRejectsAllNewCreation() throws Exception {
        JsonNode first = register(issue().path("code").asText());
        JsonNode second = register(issue().path("code").asText());
        String vehicleToken = first.path("vehicleToken").asText();
        JsonNode pickup = tree(call("POST", "/api/v1/pickups", vehicleToken, PLACE));
        String path = "/api/v1/pickups/" + pickup.path("pickupId").asText();
        String owner = pickup.path("driverToken").asText();
        JsonNode guest = tree(call("POST", path + "/participants", null,
                "{\"displayName\":\"시험\",\"inviteToken\":\"" + pickup.path("inviteToken").asText() + "\"}"));
        assertEquals(200, call("POST", path + "/participants/" + guest.path("participantId").asText() + "/approval", owner, null).statusCode());
        assertEquals(200, call("PUT", path + "/vehicle-state", owner,
                "{\"latitude\":37,\"longitude\":127,\"durationSeconds\":120,\"sourceTime\":\"" + clock.instant() + "\",\"sequence\":1}").statusCode());
        String revoke = "/api/v1/admin/vehicles/" + first.path("vehicleId").asText() + "/revocation";
        assertEquals(403, call("POST", revoke, vehicleToken, null).statusCode());
        assertEquals(200, call("POST", revoke, ADMIN, null).statusCode());
        assertEquals(200, call("POST", revoke, ADMIN, null).statusCode());
        assertEquals("VEHICLE_REVOKED", tree(call("POST", "/api/v1/pickups", vehicleToken, PLACE)).path("code").asText());
        assertEquals(403, call("GET", "/api/v1/vehicles/me", vehicleToken, null).statusCode());
        JsonNode snapshot = tree(call("GET", path, owner, null));
        assertEquals("CANCELLED", snapshot.path("status").asText());
        assertTrue(snapshot.path("place").isNull());
        assertEquals(410, call("GET", path + "/vehicle-state", guest.path("participantToken").asText(), null).statusCode());
        assertNull(jdbc.queryForObject("select vehicle_latitude from pickup where id = ?", Double.class, java.util.UUID.fromString(pickup.path("pickupId").asText())));
        assertEquals(201, call("POST", "/api/v1/pickups", second.path("vehicleToken").asText(), PLACE).statusCode());
        assertEquals(403, call("POST", "/api/v1/pickups", owner, PLACE).statusCode());
    }

    private JsonNode issue() throws Exception { return tree(call("POST", "/api/v1/admin/vehicle-registration-codes", ADMIN, null)); }
    private HttpResponse<String> registration(String code) throws Exception {
        return call("POST", "/api/v1/vehicles/registrations", null, "{\"code\":\"" + code + "\",\"displayName\":\"시험 차량\"}");
    }
    private JsonNode register(String code) throws Exception {
        HttpResponse<String> response = registration(code);
        assertEquals(201, response.statusCode());
        return tree(response);
    }
    private JsonNode tree(HttpResponse<String> response) { return json.readTree(response.body()); }
    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
