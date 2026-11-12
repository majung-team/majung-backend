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
import app.majung.server.pickup.service.PickupService;
import app.majung.server.pickup.service.RideRequestService;
import app.majung.server.pickup.service.TokenService;
import app.majung.server.pickup.service.VehicleAccountService;
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
    @Autowired PickupService pickupService;
    @Autowired VehicleAccountService vehicleAccounts;
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

        mvc.perform(post("/api/v1/ops/api/quick")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetMemberId\":\"" + realUser.id + "\",\"kind\":\"connect-dummy-passenger\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        var realCarContacts = rideRequests.contacts(realCarAuth, 0);
        assertFalse(realCarContacts.isEmpty());
        UUID contactForInvite = realCarContacts.stream()
                .filter(c -> !c.majungId().equals(received.get(0).majungId()))
                .findFirst().orElseThrow().connectionId();
        RideRequestApi.View driverInvite = rideRequests.invite(realCarAuth,
                new RideRequestApi.Invite(contactForInvite, new PickupApi.Place("판교역 2번 출구", 37.3947, 127.1112), UUID.randomUUID()));

        mvc.perform(post("/api/v1/ops/api/requests/passenger-action")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + driverInvite.requestId() + "\",\"action\":\"accept-invite\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        mvc.perform(post("/api/v1/ops/api/requests/passenger-action")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + submitted.requestId() + "\",\"action\":\"cancel\"}"))
                .andExpect(status().isForbidden());

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

    @Test
    void vehicleFirstWorkflowSupportsUnlinkedDirectRequestsQrClaimGuestJoinAndResetPreservation() throws Exception {
        MockMvc mvc = mvc();

        MvcResult loginRes = mvc.perform(post("/api/v1/ops/api/login")
                        .with(r -> { r.setRemoteAddr("10.10.0.25"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminKey\":\"" + ADMIN_KEY + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie opsCookie = new Cookie(OpsSessionService.COOKIE_NAME,
                loginRes.getResponse().getCookie(OpsSessionService.COOKIE_NAME).getValue());
        String csrf = JSON.readTree(loginRes.getResponse().getContentAsString()).path("csrfToken").asText();

        // Register a physical/emulator vehicle with ZERO iPhone real members linked
        String unlinkedRawToken = tokens.issue();
        RegisteredVehicle unlinkedCar = new RegisteredVehicle();
        unlinkedCar.id = UUID.randomUUID();
        unlinkedCar.credentialHash = tokens.hash(unlinkedRawToken);
        unlinkedCar.displayName = "에뮬레이터 아이오닉5";
        unlinkedCar.vehicleModel = "아이오닉 5";
        unlinkedCar.licensePlate = "99허 8877";
        unlinkedCar.registeredAt = Instant.now();
        unlinkedCar = vehicles.saveAndFlush(unlinkedCar);
        String carAuth = "Bearer " + unlinkedRawToken;

        // 1. Real unlinked vehicle appears first in $.vehicles with its vehicleCode
        mvc.perform(get("/api/v1/ops/api/state").cookie(opsCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles[0].vehicleId").value(unlinkedCar.id.toString()))
                .andExpect(jsonPath("$.vehicles[0].vehicleCode").value(unlinkedCar.vehicleCode))
                .andExpect(jsonPath("$.vehicles[0].dummy").value(false))
                .andExpect(jsonPath("$.vehicles[0].linked").value(false));

        // 2. Ops Console sends direct dummy passenger request to the unlinked vehicle
        mvc.perform(post("/api/v1/ops/api/vehicles/request")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleCode\":\"" + unlinkedCar.vehicleCode + "\",\"placeLabel\":\"판교역 2번 출구\",\"latitude\":37.3947,\"longitude\":127.1112}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").isNotEmpty());

        var incoming = rideRequests.received(carAuth);
        assertEquals(1, incoming.size());
        UUID reqId = incoming.get(0).requestId();
        rideRequests.accept(carAuth, reqId);
        RideRequestApi.Started started = rideRequests.start(carAuth, reqId);

        // Ops Console acts as the dummy passenger during the unlinked vehicle's live pickup
        mvc.perform(post("/api/v1/ops/api/pickups/passenger-action")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupId\":\"" + started.pickupId() + "\",\"readiness\":\"READY\",\"quickMessage\":\"출구 앞이에요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participant.readiness").value("READY"));

        // Arrive and finish pickup cleanly via pickup driver token
        String pickupDriverAuth = "Bearer " + started.driverToken();
        pickupService.arrivePickup(started.pickupId(), pickupDriverAuth);
        pickupService.endPickup(started.pickupId(), pickupDriverAuth, Pickup.Status.COMPLETED);

        // 3. Vehicle creates QR challenge; Ops Console claims it on behalf of a dummy user, then vehicle confirms
        var qr = vehicleAccounts.issue(carAuth);
        String qrLink = "https://majung.cloud/#vehicle/" + qr.challengeId() + "/" + qr.code();
        mvc.perform(post("/api/v1/ops/api/vehicle-qr/claim")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"link\":\"" + qrLink + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.vehicles[0].vehicleId").value(unlinkedCar.id.toString()));

        vehicleAccounts.confirm(carAuth, qr.challengeId());

        // Linking a dummy account lets Ops connect dummy friends to it, while STILL protecting the real vehicle from tick!
        mvc.perform(post("/api/v1/ops/api/vehicles/connect-dummy")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleId\":\"" + unlinkedCar.id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.vehicles[0].linked").value(true))
                .andExpect(jsonPath("$.state.vehicles[0].dummy").value(false));

        // 4. Vehicle creates guest invite; Ops Console joins as guest and sends readiness
        var guestCreated = pickupService.createPickup(new PickupApi.Place("강남역 2번 출구", 37.4979, 127.0276), carAuth);
        String guestLink = "https://majung.cloud/#join/" + guestCreated.pickupId() + "/" + guestCreated.inviteToken();
        mvc.perform(post("/api/v1/ops/api/guest-join")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"link\":\"" + guestLink + "\",\"displayName\":\"테스트 손님\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/ops/api/pickups/passenger-action")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupId\":\"" + guestCreated.pickupId() + "\",\"readiness\":\"PREPARING\",\"quickMessage\":\"금방 갑니다\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participant.displayName").value("테스트 손님"))
                .andExpect(jsonPath("$.participant.readiness").value("PREPARING"));

        // Real vehicle MUST still be protected against operator vehicleTick
        mvc.perform(post("/api/v1/ops/api/pickups/tick")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupId\":\"" + guestCreated.pickupId() + "\",\"action\":\"arrive\"}"))
                .andExpect(status().isForbidden());

        // 5. Resetting dummy data removes the dummy link cleanly WITHOUT deleting the real emulator vehicle
        mvc.perform(post("/api/v1/ops/api/dummy/reset")
                        .cookie(opsCookie).header("X-Ops-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"withSamplePickups\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicles[0].vehicleId").value(unlinkedCar.id.toString()))
                .andExpect(jsonPath("$.vehicles[0].linked").value(false));

        assertTrue(vehicles.findById(unlinkedCar.id).isPresent());
        assertTrue(tokens.matches(unlinkedRawToken, vehicles.findById(unlinkedCar.id).orElseThrow().credentialHash));
    }
}
