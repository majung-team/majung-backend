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
        "spring.datasource.url=jdbc:h2:mem:place-proposal-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "majung.rate-limit.enabled=false"
})
@Import(PickupApiTest.TimeConfiguration.class)
class PlaceProposalTest {
    private static final Map<String, Object> PLACE = Map.of("label", "예시 장소", "latitude", 37.0, "longitude", 127.0);
    private static final Map<String, Object> OTHER = Map.of("label", "정문 앞", "latitude", 37.001, "longitude", 127.002);

    @LocalServerPort int port;
    @Autowired PickupApiTest.MutableClock clock;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void reset() { clock.reset(); }

    @Test void passengerProposalKeepsTheConfirmedPlaceAndTheDriverSeesIt() throws Exception {
        Pickup pickup = connected();
        JsonNode before = get(pickup, pickup.owner);
        assertTrue(before.path("placeProposal").isNull());
        Response proposed = call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", OTHER));
        assertEquals(200, proposed.status());
        assertEquals("예시 장소", proposed.body().path("place").path("label").asText());
        assertEquals("정문 앞", proposed.body().path("placeProposal").path("place").path("label").asText());
        assertEquals("PASSENGER", proposed.body().path("placeProposal").path("proposedBy").asText());
        JsonNode seenByDriver = get(pickup, pickup.owner);
        assertEquals(37.001, seenByDriver.path("placeProposal").path("place").path("latitude").asDouble());
        assertEquals(37.0, seenByDriver.path("place").path("latitude").asDouble());
    }

    @Test void proposalAndReadinessReceiptDoNotOverwriteEachOther() throws Exception {
        Pickup pickup = connected();
        call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", OTHER));
        call("PUT", pickup.path() + "/readiness", pickup.guest, Map.of("readiness", "READY", "sequence", 1));
        JsonNode before = get(pickup, pickup.owner);
        Response receipt = call("PUT", pickup.path() + "/readiness-receipt", pickup.owner,
                Map.of("participantId", before.path("participant").path("participantId").asText(), "sequence", 1));
        assertEquals(200, receipt.status());
        assertEquals("정문 앞", receipt.body().path("placeProposal").path("place").path("label").asText());
        assertEquals("예시 장소", receipt.body().path("place").path("label").asText());
        call("DELETE", pickup.path() + "/place-proposal", pickup.guest, null);
        JsonNode withdrawn = get(pickup, pickup.guest);
        assertTrue(withdrawn.path("placeProposal").isNull());
        assertEquals(1, withdrawn.path("participant").path("readinessReceivedSequence").asInt());
        assertEquals("READY", withdrawn.path("participant").path("readiness").asText());
    }

    @Test void driverCanProposeAndReplaceAndEitherSideSeesTheLatest() throws Exception {
        Pickup pickup = connected();
        call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", OTHER));
        Response replaced = call("PUT", pickup.path() + "/place-proposal", pickup.owner,
                Map.of("place", Map.of("label", "후문", "latitude", 37.003, "longitude", 127.004)));
        assertEquals(200, replaced.status());
        assertEquals("DRIVER", replaced.body().path("placeProposal").path("proposedBy").asText());
        assertEquals("후문", get(pickup, pickup.guest).path("placeProposal").path("place").path("label").asText());
    }

    @Test void passengerCanOnlyWithdrawTheirOwnProposalButDriverCanClearAny() throws Exception {
        Pickup pickup = connected();
        call("PUT", pickup.path() + "/place-proposal", pickup.owner, Map.of("place", OTHER));
        assertEquals(403, call("DELETE", pickup.path() + "/place-proposal", pickup.guest, null).status());
        assertEquals("DRIVER", get(pickup, pickup.guest).path("placeProposal").path("proposedBy").asText());
        call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", OTHER));
        assertEquals(200, call("DELETE", pickup.path() + "/place-proposal", pickup.guest, null).status());
        assertTrue(get(pickup, pickup.owner).path("placeProposal").isNull());
        call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", OTHER));
        assertEquals(200, call("DELETE", pickup.path() + "/place-proposal", pickup.owner, null).status());
        assertTrue(get(pickup, pickup.guest).path("placeProposal").isNull());
    }

    @Test void rejectsStrangersSamePlaceInvalidCoordinatesAndUnconnectedPickups() throws Exception {
        Pickup pickup = connected();
        assertEquals(401, call("PUT", pickup.path() + "/place-proposal", null, Map.of("place", OTHER)).status());
        assertEquals(403, call("PUT", pickup.path() + "/place-proposal", "x".repeat(43), Map.of("place", OTHER)).status());
        Response same = call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", PLACE));
        assertEquals(409, same.status());
        assertEquals("SAME_PLACE", same.body().path("code").asText());
        assertEquals(400, call("PUT", pickup.path() + "/place-proposal", pickup.guest,
                Map.of("place", Map.of("label", "", "latitude", 37.0, "longitude", 127.0))).status());
        assertEquals(400, call("PUT", pickup.path() + "/place-proposal", pickup.guest,
                Map.of("place", Map.of("label", "범위 밖", "latitude", 95.0, "longitude", 127.0))).status());
        // Nobody has joined a fresh pickup, so no proposal can be made for it.
        Response created = call("POST", "/api/v1/pickups", null, Map.of("place", PLACE));
        String path = "/api/v1/pickups/" + created.body().path("pickupId").asText();
        assertEquals(403, call("PUT", path + "/place-proposal", created.body().path("driverToken").asText(),
                Map.of("place", OTHER)).status());
    }

    @Test void proposalIsDroppedWhenThePassengerIsDisconnectedOrThePickupEnds() throws Exception {
        Pickup pickup = connected();
        call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", OTHER));
        assertEquals(200, call("DELETE", pickup.path() + "/participant", pickup.owner, null).status());
        assertTrue(get(pickup, pickup.owner).path("placeProposal").isNull());
        Pickup ended = connected();
        call("PUT", ended.path() + "/place-proposal", ended.guest, Map.of("place", OTHER));
        assertEquals(200, call("POST", ended.path() + "/cancellation", ended.owner, null).status());
        assertTrue(get(ended, ended.owner).path("placeProposal").isNull());
        assertEquals(410, call("PUT", ended.path() + "/place-proposal", ended.owner, Map.of("place", OTHER)).status());
    }

    @Test void confirmedPlaceRejectsOldTelemetryAndDuplicateConfirmationIsSafe() throws Exception {
        Pickup pickup = active();
        JsonNode proposal = call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", OTHER)).body();
        Map<String, Object> confirmation = confirmation(proposal, OTHER, clock.instant().toString());
        assertEquals(403, call("PUT", pickup.path() + "/place-confirmation", pickup.guest, confirmation).status());
        assertEquals(200, call("PUT", pickup.path() + "/vehicle-state", pickup.owner, telemetry(1, 1)).status());
        Response confirmed = call("PUT", pickup.path() + "/place-confirmation", pickup.owner, confirmation);
        assertEquals(200, confirmed.status());
        assertEquals(2, confirmed.body().path("placeVersion").asInt());
        assertEquals("정문 앞", confirmed.body().path("place").path("label").asText());
        assertTrue(confirmed.body().path("placeProposal").isNull());
        assertEquals(200, call("PUT", pickup.path() + "/place-confirmation", pickup.owner, confirmation).status());
        assertEquals(2, get(pickup, pickup.owner).path("placeVersion").asInt());
        assertEquals(409, call("PUT", pickup.path() + "/vehicle-state", pickup.owner, telemetry(2, 1)).status());
        assertEquals(409, call("DELETE", pickup.path() + "/vehicle-state", pickup.owner,
                Map.of("sequence", 3, "placeVersion", 1)).status());
        assertEquals(409, call("POST", pickup.path() + "/arrival", pickup.owner, null).status());
        assertEquals(200, call("PUT", pickup.path() + "/vehicle-state", pickup.owner, telemetry(2, 2)).status());
        assertEquals(200, call("POST", pickup.path() + "/arrival", pickup.owner, Map.of("placeVersion", 2)).status());
    }

    @Test void changedProposalOrUnverifiedRouteCannotChangeTheConfirmedPlace() throws Exception {
        Pickup pickup = active();
        JsonNode first = call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", OTHER)).body();
        assertEquals(409, call("PUT", pickup.path() + "/place-confirmation", pickup.owner,
                confirmation(first, PLACE, clock.instant().toString())).status());
        assertEquals(409, call("PUT", pickup.path() + "/place-confirmation", pickup.owner,
                confirmation(first, OTHER, clock.instant().minusSeconds(11).toString())).status());
        assertEquals(409, call("PUT", pickup.path() + "/place-confirmation", pickup.owner,
                confirmation(first, OTHER, clock.instant().plusSeconds(6).toString())).status());
        call("DELETE", pickup.path() + "/place-proposal", pickup.guest, null);
        JsonNode second = call("PUT", pickup.path() + "/place-proposal", pickup.guest, Map.of("place", OTHER)).body();
        assertEquals(409, call("PUT", pickup.path() + "/place-confirmation", pickup.owner,
                confirmation(first, OTHER, clock.instant().toString())).status());
        assertEquals("예시 장소", get(pickup, pickup.owner).path("place").path("label").asText());
        assertEquals(200, call("PUT", pickup.path() + "/place-confirmation", pickup.owner,
                confirmation(second, OTHER, clock.instant().toString())).status());
    }

    @Test void smallClockSkewDoesNotExtendVehicleFreshnessPastServerReceipt() throws Exception {
        Pickup pickup = active();
        Response updated = call("PUT", pickup.path() + "/vehicle-state", pickup.owner,
                Map.of("latitude", 37.01, "longitude", 127.01, "durationSeconds", 120,
                        "sourceTime", clock.instant().plusSeconds(4).toString(), "sequence", 1, "placeVersion", 1));
        assertEquals(200, updated.status());
        assertTrue(updated.body().path("validForMillis").asLong() <= 30000);
        clock.advance(Duration.ofSeconds(31));
        JsonNode stale = call("GET", pickup.path() + "/vehicle-state", pickup.guest, null).body();
        assertEquals("STALE", stale.path("freshness").asText());
        assertEquals(0, stale.path("validForMillis").asLong());
    }

    private Map<String, Object> confirmation(JsonNode snapshot, Map<String, Object> place, String sourceTime) {
        return Map.of("proposalId", snapshot.path("placeProposal").path("proposalId").asText(),
                "expectedPlaceVersion", 1, "place", place, "durationSeconds", 180, "sourceTime", sourceTime);
    }

    private Map<String, Object> telemetry(long sequence, long version) {
        return Map.of("latitude", 37.01, "longitude", 127.01, "durationSeconds", 120,
                "sourceTime", clock.instant().toString(), "sequence", sequence, "placeVersion", version);
    }

    private Pickup active() throws Exception {
        Pickup pickup = connected();
        String participant = get(pickup, pickup.owner).path("participant").path("participantId").asText();
        assertEquals(200, call("POST", pickup.path() + "/participants/" + participant + "/approval", pickup.owner, null).status());
        return pickup;
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
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), json.readTree(response.body()));
    }
}
