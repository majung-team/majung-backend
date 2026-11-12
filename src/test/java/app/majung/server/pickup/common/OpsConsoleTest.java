package app.majung.server.pickup.common;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.majung.server.pickup.api.PickupApi;
import app.majung.server.pickup.api.RideRequestApi;
import app.majung.server.pickup.dev.DevDummySeedService;
import app.majung.server.pickup.dev.OpsSessionService;
import app.majung.server.pickup.domain.Member;
import app.majung.server.pickup.domain.Pickup;
import app.majung.server.pickup.domain.RegisteredVehicle;
import app.majung.server.pickup.domain.VehicleAccountLink;
import app.majung.server.pickup.repository.MemberRepository;
import app.majung.server.pickup.repository.PickupRepository;
import app.majung.server.pickup.repository.RegisteredVehicleRepository;
import app.majung.server.pickup.repository.VehicleAccountLinkRepository;
import app.majung.server.pickup.service.RideRequestService;
import app.majung.server.pickup.service.TokenService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ops-console-deploy;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
        "majung.deployment=true",
        "majung.admin-key=test-ops-admin-key-0123456789abcdef",
        "majung.development-tools=false",
        "majung.retention.enabled=true",
        "majung.rate-limit.enabled=true",
        "majung.dummy-data.enabled=true"
})
class OpsConsoleTest {
    private static final String ADMIN_KEY = "test-ops-admin-key-0123456789abcdef";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired WebApplicationContext context;
    @Autowired MemberRepository members;
    @Autowired RegisteredVehicleRepository vehicles;
    @Autowired VehicleAccountLinkRepository links;
    @Autowired PickupRepository pickups;
    @Autowired RideRequestService rideRequests;
    @Autowired TokenService tokens;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void productionOpsConsoleRequiresAdminAuthSupportsRealPhoneTestingAndProtectsRealVehicles() throws Exception {
        MockMvc mvc = mvc();

        // 1. Static ops UI is reachable under /api/v1/ops, but unauthenticated API state fails 401 cleanly
        mvc.perform(get("/api/v1/ops")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ops/ops.js")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ops/api/state")).andExpect(status().isUnauthorized());

        // 2. Wrong admin key brute-force lockout after 5 attempts
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/ops/api/login")
                            .with(r -> { r.setRemoteAddr("10.10.0.99"); return r; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"adminKey\":\"wrong-key\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/ops/api/login")
                        .with(r -> { r.setRemoteAddr("10.10.0.99"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminKey\":\"" + ADMIN_KEY + "\"}"))
                .andExpect(status().isTooManyRequests());

        // 3. Valid admin key login sets HttpOnly+Secure+SameSite=Strict session cookie and CSRF token
        MvcResult loginRes = mvc.perform(post("/api/v1/ops/api/login")
                        .with(r -> { r.setRemoteAddr("10.10.0.10"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminKey\":\"" + ADMIN_KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andReturn();

        String setCookie = loginRes.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertTrue(setCookie.contains("Secure"));
        String cookieVal = loginRes.getResponse().getCookie(OpsSessionService.COOKIE_NAME).getValue();
        Cookie opsCookie = new Cookie(OpsSessionService.COOKIE_NAME, cookieVal);
        String csrf = JSON.readTree(loginRes.getResponse().getContentAsString()).path("csrfToken").asText();

        // CSRF and same-origin enforcement on mutations
        mvc.perform(post("/api/v1/ops/api/dummy/seed").cookie(opsCookie))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ops/api/dummy/seed")
                        .cookie(opsCookie)
                        .header("X-Ops-CSRF", csrf)
                        .header("Host", "majung.cloud")
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());

        // 4. Create real Kakao member and test real-phone-to-dummy-driver flow end-to-end
        Member realUser = new Member();
        realUser.id = UUID.randomUUID();
        realUser.provider = "kakao";
        realUser.providerSubject = "9988776655";
        realUser.majungId = "MJ-REAL001";
        realUser.nickname = "실폰 승준";
        realUser.normalizedNickname = "실폰 승준";
        realUser.profileReady = true;
        realUser.createdAt = Instant.now();
        members.saveAndFlush(realUser);

        // Catalog was already seeded on startup; verify 16+ dummy members exist
        mvc.perform(get("/api/v1/ops/api/state").cookie(opsCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deployment").value(true))
                .andExpect(jsonPath("$.members", hasSize(greaterThanOrEqualTo(17))));

        // Inject full scenario onto real Kakao user
        mvc.perform(post("/api/v1/ops/api/users/inject")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"MJ-REAL001\"}"))
                .andExpect(status().isOk());

        // Real Kakao user submits a ride request to dummy driver Kim Minjun (10000000-...-000000000002)
        UUID minjunId = DevDummySeedService.DUMMY_SPECS.get(1).id();
        RideRequestApi.View submitted = rideRequests.submit(realUser.id,
                new RideRequestApi.Submit(minjunId, new PickupApi.Place("강남역 2번 출구", 37.4979, 127.0276), UUID.randomUUID()));

        // Operator accepts & starts & approaches & arrives via Ops Console
        mvc.perform(post("/api/v1/ops/api/requests/action")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + submitted.requestId() + "\",\"action\":\"accept\"}"))
                .andExpect(status().isOk());
        MvcResult startedRes = mvc.perform(post("/api/v1/ops/api/requests/action")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + submitted.requestId() + "\",\"action\":\"start\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String newPickupId = JSON.readTree(startedRes.getResponse().getContentAsString()).path("pickupId").asText();

        mvc.perform(post("/api/v1/ops/api/pickups/tick")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupId\":\"" + newPickupId + "\",\"action\":\"approach\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/ops/api/pickups/tick")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupId\":\"" + newPickupId + "\",\"action\":\"arrive\"}"))
                .andExpect(status().isOk());

        // Verify one-click quick actions
        mvc.perform(post("/api/v1/ops/api/quick")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":\"" + realUser.id + "\",\"kind\":\"send-invite\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mvc.perform(post("/api/v1/ops/api/quick")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":\"" + realUser.id + "\",\"kind\":\"send-friend-request\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mvc.perform(post("/api/v1/ops/api/quick")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":\"" + realUser.id + "\",\"kind\":\"start-live-pickup\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        // Dummy passenger -> my linked vehicle flow
        mvc.perform(post("/api/v1/ops/api/quick")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":\"" + realUser.id + "\",\"kind\":\"dummy-request-my-vehicle\"}"))
                .andExpect(status().isConflict());

        // Real physical vehicle protection: non-dummy vehicles cannot be driven by Ops Console,
        // while dummy passengers requesting my linked vehicle CAN be driven by Ops Console passenger actions.
        String realCarRawToken = tokens.issue();
        RegisteredVehicle realCar = new RegisteredVehicle();
        realCar.id = UUID.randomUUID();
        realCar.credentialHash = tokens.hash(realCarRawToken);
        realCar.displayName = "실제 등록 차량";
        realCar.registeredAt = Instant.now();
        vehicles.saveAndFlush(realCar);

        VehicleAccountLink realLink = new VehicleAccountLink();
        realLink.vehicleId = realCar.id;
        realLink.member = realUser;
        realLink.linkedAt = Instant.now();
        links.saveAndFlush(realLink);

        mvc.perform(post("/api/v1/ops/api/quick")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":\"" + realUser.id + "\",\"kind\":\"dummy-request-my-vehicle\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        String realCarAuth = "Bearer " + realCarRawToken;
        var received = rideRequests.received(realCarAuth);
        assertFalse(received.isEmpty());
        UUID incomingReqId = received.get(0).requestId();
        rideRequests.accept(realCarAuth, incomingReqId);
        RideRequestApi.Started realCarPickup = rideRequests.start(realCarAuth, incomingReqId);

        mvc.perform(post("/api/v1/ops/api/pickups/passenger-action")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupId\":\"" + realCarPickup.pickupId() + "\",\"readiness\":\"READY\",\"quickMessage\":\"지금 내려가요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participant.readiness").value("READY"))
                .andExpect(jsonPath("$.quickMessage.text").value("지금 내려가요"));

        mvc.perform(post("/api/v1/ops/api/pickups/tick")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupId\":\"" + realCarPickup.pickupId() + "\",\"action\":\"arrive\"}"))
                .andExpect(status().isForbidden());

        // Reset dummy data cleans ONLY dev-dummy and preserves real Kakao member
        MvcResult resetRes = mvc.perform(post("/api/v1/ops/api/dummy/reset")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"withSamplePickups\":false}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode resetJson = JSON.readTree(resetRes.getResponse().getContentAsString());
        assertEquals(1, resetJson.path("stats").path("realMembers").asInt());
        assertFalse(members.findById(realUser.id).isEmpty());
        assertEquals("실폰 승준", members.findById(realUser.id).orElseThrow().nickname);
    }
}
