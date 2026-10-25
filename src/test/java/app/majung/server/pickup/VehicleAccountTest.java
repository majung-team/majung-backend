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
}
