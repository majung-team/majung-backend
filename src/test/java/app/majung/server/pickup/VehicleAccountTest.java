package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:vehicle-account;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
    "majung.rate-limit.enabled=false", "majung.login.unlink-cleanup-enabled=false"
})
@Import(PickupApiTest.TimeConfiguration.class)
class VehicleAccountTest {
    static final AtomicLong subjects = new AtomicLong(800000);
    @Autowired VehicleAccountService links;
    @Autowired VehicleRegistrationService vehicles;
    @Autowired MemberService members;
    @Autowired VehiclePeopleService people;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired PickupApiTest.MutableClock clock;

    @org.junit.jupiter.api.BeforeEach void resetClock() { clock.reset(); }
    Member member() { Member m = members.login("" + subjects.incrementAndGet()); members.nickname(m.id, "가상회원"); return m; }
    String vehicle() { var code = vehicles.issueCode(); return "Bearer " + vehicles.register(new VehicleRegistrationApi.Registration(code.code(), "시험 차량")).vehicleToken(); }
    @Test void phoneRequestNeedsExplicitVehicleConfirmation() {
        String vehicle = vehicle(); Member driver = member(); var qr = links.issue(vehicle);
        links.claim(driver.id, new VehicleAccountApi.Claim(qr.challengeId(), qr.code()));
        assertTrue(links.links(driver.id).isEmpty());
        assertEquals("AWAITING_VEHICLE_CONFIRMATION", links.device(vehicle).status());
        links.confirm(vehicle, qr.challengeId());
        assertEquals("LINKED", links.request(driver.id, qr.challengeId()).status());
        assertEquals(1, links.links(driver.id).size());
        links.confirm(vehicle, qr.challengeId());
        assertEquals(1, links.links(driver.id).size());
        links.detachDevice(vehicle);
        assertTrue(links.links(driver.id).isEmpty());
    }
    @Test void anotherClaimantAndAnotherVehicleCannotTakeOver() {
        String vehicle = vehicle(); Member owner = member(); Member other = member(); var qr = links.issue(vehicle);
        links.claim(owner.id, new VehicleAccountApi.Claim(qr.challengeId(), qr.code()));
        assertEquals(409, assertThrows(ApiException.class, () -> links.claim(other.id, new VehicleAccountApi.Claim(qr.challengeId(), qr.code()))).status);
        String wrongVehicle = vehicle();
        assertEquals(410, assertThrows(ApiException.class, () -> links.confirm(wrongVehicle, qr.challengeId())).status);
        assertEquals(404, assertThrows(ApiException.class, () -> links.request(other.id, qr.challengeId())).status);
        links.confirm(vehicle, qr.challengeId());
        assertEquals(409, assertThrows(ApiException.class, () -> links.issue(vehicle)).status);
    }
    @Test void replacingAndExpiringQrRejectsOldRequests() {
        String vehicle = vehicle(); Member owner = member(); var old = links.issue(vehicle); var current = links.issue(vehicle);
        assertEquals(410, assertThrows(ApiException.class, () -> links.claim(owner.id, new VehicleAccountApi.Claim(old.challengeId(), old.code()))).status);
        links.claim(owner.id, new VehicleAccountApi.Claim(current.challengeId(), current.code()));
        clock.advance(Duration.ofMinutes(5));
        assertEquals(410, assertThrows(ApiException.class, () -> links.confirm(vehicle, current.challengeId())).status);
        assertTrue(links.links(owner.id).isEmpty());
    }
    @Test void vehiclePeopleUsesLinkedDriverAndPreservesRecipientApproval() throws Exception {
        String vehicle=vehicle(); Member owner=member(); Member passenger=member(); Member stranger=member();
        assertEquals(409,assertThrows(ApiException.class,()->people.search(vehicle,passenger.majungId,0)).status);
        var challenge=links.issue(vehicle);
        links.claim(owner.id,new VehicleAccountApi.Claim(challenge.challengeId(),challenge.code()));
        links.confirm(vehicle,challenge.challengeId());
        assertEquals(passenger.id,people.search(vehicle,passenger.majungId,0).get(0).memberId());
        assertEquals(409,assertThrows(ApiException.class,()->people.request(vehicle,passenger.id,stranger.majungId)).status);
        var requested=people.request(vehicle,passenger.id,owner.majungId);
        assertEquals("PENDING",requested.status());
        assertEquals(requested.connectionId(),people.request(vehicle,passenger.id,owner.majungId).connectionId());
        assertEquals("OUTGOING",people.connections(vehicle,0).get(0).direction());
        assertEquals(403,assertThrows(ApiException.class,()->people.change(vehicle,requested.connectionId(),"accept",owner.majungId)).status);
        var other=members.request(passenger.id,stranger.id);
        assertEquals(404,assertThrows(ApiException.class,()->people.change(vehicle,other.connectionId(),"accept",owner.majungId)).status);
        members.change(passenger.id,requested.connectionId(),"accept");
        assertEquals("ACCEPTED",people.connections(vehicle,0).get(0).status());
        var incoming=members.request(stranger.id,owner.id);
        assertEquals("ACCEPTED",people.change(vehicle,incoming.connectionId(),"accept",owner.majungId).status());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
            .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/vehicles/people/search")
            .param("query",passenger.majungId).header("Authorization",vehicle))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].memberId").value(passenger.id.toString()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/vehicles/people/connections"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/vehicles/people/connections")
            .header("Authorization",vehicle).contentType("application/json").content("{}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertEquals(400,assertThrows(ApiException.class,()->people.request(vehicle,owner.id,owner.majungId)).status);
        links.detachDevice(vehicle);
        assertEquals(409,assertThrows(ApiException.class,()->people.connections(vehicle,0)).status);
        vehicles.revoke(vehicles.current(vehicle).vehicleId());
        assertEquals(403,assertThrows(ApiException.class,()->people.search(vehicle,"가상",0)).status);
    }

}
