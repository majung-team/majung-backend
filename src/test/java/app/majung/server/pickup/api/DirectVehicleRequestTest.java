package app.majung.server.pickup.api;

import app.majung.server.pickup.common.ApiException;
import app.majung.server.pickup.domain.Member;
import app.majung.server.pickup.domain.Pickup;
import app.majung.server.pickup.service.MemberService;
import app.majung.server.pickup.service.PickupService;
import app.majung.server.pickup.service.RideRequestService;
import app.majung.server.pickup.service.VehicleAccountService;
import app.majung.server.pickup.service.VehicleRegistrationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:direct-vehicle-requests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
        "majung.admin-key=test-admin-key-not-a-secret-0123456789",
        "majung.rate-limit.enabled=false", "majung.login.unlink-cleanup-enabled=false"
})
@Import({PickupApiTest.TimeConfiguration.class, app.majung.server.pickup.push.ArrivalPushTest.FakePushConfiguration.class})
public class DirectVehicleRequestTest {
    private static final String ADMIN = "test-admin-key-not-a-secret-0123456789";
    private static final AtomicLong SUBJECTS = new AtomicLong(950000);

    @LocalServerPort int port;
    @Autowired RideRequestService rideRequests;
    @Autowired VehicleRegistrationService vehicles;
    @Autowired VehicleAccountService links;
    @Autowired MemberService members;
    @Autowired PickupService pickups;
    @Autowired PickupApiTest.MutableClock clock;
    @Autowired WebApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void reset() {
        clock.reset();
    }

    private Member member(String name) {
        Member m = members.login("" + SUBJECTS.incrementAndGet());
        members.nickname(m.id, name);
        return m;
    }

    private org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken auth(Member m) {
        var user = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                java.util.Map.of("id", m.id.toString()),
                "id");
        return new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(user, user.getAuthorities(), "kakao");
    }

    private String registerUnlinkedVehicle(String displayName) {
        var code = vehicles.issueCode();
        return "Bearer " + vehicles.register(new VehicleRegistrationApi.Registration(code.code(), displayName)).vehicleToken();
    }

    @Test
    void unlinkedVehicleReceivesDirectRequestWithProfileTrustAndBlocking() throws Exception {
        String vehicleAuth = registerUnlinkedVehicle("초기 차량");
        VehicleRegistrationApi.VehicleInfo updated = vehicles.updateProfile(vehicleAuth,
                new VehicleRegistrationApi.UpdateProfile("아이오닉 5", "12가 3456", "송*준 아빠", true));
        assertNotNull(updated.vehicleCode());
        assertTrue(updated.vehicleCode().startsWith("VEH-"));
        assertEquals("아이오닉 5 · 12가 3456", updated.displayName());
        assertEquals("송*준 아빠", updated.ownerHint());

        Member passenger = member("지민");
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        // Lookup requires auth
        mvc.perform(get("/api/v1/ride-requests/vehicles/lookup").param("code", updated.vehicleCode()))
                .andExpect(status().isUnauthorized());

        // Normalized short code without prefix resolves car model, plate, and hint
        String rawTail = updated.vehicleCode().substring(4).toLowerCase();
        mvc.perform(get("/api/v1/ride-requests/vehicles/lookup")
                        .with(authentication(auth(passenger)))
                        .param("code", rawTail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleId").value(updated.vehicleId().toString()))
                .andExpect(jsonPath("$.vehicleModel").value("아이오닉 5"))
                .andExpect(jsonPath("$.licensePlate").value("12가 3456"))
                .andExpect(jsonPath("$.ownerHint").value("송*준 아빠"))
                .andExpect(jsonPath("$.linkedDriver").value(false))
                .andExpect(jsonPath("$.trusted").value(false));

        // Submit direct request to unlinked vehicle
        UUID reqId = UUID.randomUUID();
        mvc.perform(post("/api/v1/ride-requests")
                        .with(authentication(auth(passenger)))
                        .with(csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":"%s","requestId":"%s","place":{"label":"광교중앙역 1번 출구","latitude":37.2886,"longitude":127.0516}}
                                """.formatted(updated.vehicleId(), reqId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.nickname").value("아이오닉 5 · 12가 3456"))
                .andExpect(jsonPath("$.majungId").value(updated.vehicleCode()));

        // Unlinked vehicle sees incoming request as FIRST_TIME
        List<RideRequestApi.View> inbox = rideRequests.received(vehicleAuth);
        assertEquals(1, inbox.size());
        assertEquals("FIRST_TIME", inbox.get(0).trustStatus());
        assertEquals("지민", inbox.get(0).nickname());

        // Accept and start pickup without driver account
        RideRequestApi.View accepted = rideRequests.accept(vehicleAuth, reqId);
        assertEquals("QUEUED", accepted.status());
        assertEquals("TRUSTED", accepted.trustStatus());

        RideRequestApi.Started started = rideRequests.start(vehicleAuth, reqId);
        assertEquals("ACTIVE", started.pickup().status());
        pickups.arrivePickup(started.pickupId(), "Bearer " + started.driverToken());
        pickups.endPickup(started.pickupId(), "Bearer " + started.driverToken(), Pickup.Status.COMPLETED);

        // Passenger now sees vehicle in favorite-vehicles
        mvc.perform(get("/api/v1/ride-requests/favorite-vehicles")
                        .with(authentication(auth(passenger))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles[0].vehicleId").value(updated.vehicleId().toString()))
                .andExpect(jsonPath("$.vehicles[0].trusted").value(true));

        // Subsequent request from trusted passenger skips FIRST_TIME warning
        RideRequestApi.View second = rideRequests.submit(passenger.id,
                new RideRequestApi.Submit(null, updated.vehicleId(), new PickupApi.Place("판교역", 37.3947, 127.1112), UUID.randomUUID()));
        assertEquals("TRUSTED", rideRequests.received(vehicleAuth).get(0).trustStatus());
        rideRequests.cancel(passenger.id, second.requestId());

        // Stranger can be rejected + blocked
        Member stranger = member("낯선이");
        RideRequestApi.View strangerReq = rideRequests.submit(stranger.id,
                new RideRequestApi.Submit(null, updated.vehicleId(), new PickupApi.Place("서울역", 37.5547, 126.9707), UUID.randomUUID()));
        RideRequestApi.View rejected = rideRequests.reject(vehicleAuth, strangerReq.requestId(), true);
        assertEquals("REJECTED", rejected.status());
        assertEquals("BLOCKED", rejected.trustStatus());

        mvc.perform(get("/api/v1/ride-requests/vehicles/lookup")
                        .with(authentication(auth(stranger)))
                        .param("code", updated.vehicleCode()))
                .andExpect(status().isForbidden());
        assertEquals(403, assertThrows(ApiException.class, () -> rideRequests.submit(stranger.id,
                new RideRequestApi.Submit(null, updated.vehicleId(), new PickupApi.Place("서울역", 37.5547, 126.9707), UUID.randomUUID()))).status);

        // Disabling direct requests blocks new lookup & submission
        vehicles.updateProfile(vehicleAuth, new VehicleRegistrationApi.UpdateProfile(null, null, null, false));
        mvc.perform(get("/api/v1/ride-requests/vehicles/lookup")
                        .with(authentication(auth(passenger)))
                        .param("code", updated.vehicleCode()))
                .andExpect(status().isConflict());

        // Re-enabling works, and passenger can remove favorite cleanly
        vehicles.updateProfile(vehicleAuth, new VehicleRegistrationApi.UpdateProfile(null, null, null, true));
        mvc.perform(delete("/api/v1/ride-requests/favorite-vehicles/" + updated.vehicleId())
                        .with(authentication(auth(passenger)))
                        .with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/ride-requests/favorite-vehicles")
                        .with(authentication(auth(passenger))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles.length()").value(0));

        // HTTP PUT /api/v1/vehicles/me check
        HttpResponse<String> putRes = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/vehicles/me"))
                        .header("Authorization", vehicleAuth)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"vehicleModel\":\"카니발\",\"licensePlate\":\"34나 7890\",\"ownerHint\":\"엄마 차\",\"directRequestEnabled\":true}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, putRes.statusCode());
        JsonNode putJson = json.readTree(putRes.body());
        assertEquals("카니발 · 34나 7890", putJson.path("displayName").asText());
        assertEquals("엄마 차", putJson.path("ownerHint").asText());
    }
}
