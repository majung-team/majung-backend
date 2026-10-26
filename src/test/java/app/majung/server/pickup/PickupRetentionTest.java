package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:retention-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "majung.retention.enabled=true",
        "majung.retention.cleanup-interval-ms=3600000"
})
@Import(PickupApiTest.TimeConfiguration.class)
class PickupRetentionTest {
    @Autowired PickupRetentionJob job;
    @Autowired PickupRepository pickupRepository;
    @Autowired PickupApiTest.MutableClock clock;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RideRequestRepository requests;
    @org.junit.jupiter.api.BeforeEach void reset() { requests.deleteAll(); pickupRepository.deleteAll(); clock.reset(); }

    @Test void associatedRequestUsesPickupEndAndNeverDeletesAnActivePickupRequest() {
        Instant cutoff=clock.instant().minus(Duration.ofDays(7));
        UUID active=save(null,clock.instant().plusSeconds(3600));
        UUID recent=save(cutoff.plusSeconds(1),clock.instant());
        UUID old=save(cutoff,clock.instant());
        UUID activeRequest=request(active,cutoff.minusSeconds(86400));
        UUID recentRequest=request(recent,cutoff.minusSeconds(86400));
        UUID oldRequest=request(old,clock.instant().plusSeconds(86400));
        assertEquals(1,job.purgeExpiredPickups());
        assertTrue(requests.existsById(activeRequest)); assertTrue(requests.existsById(recentRequest));
        assertFalse(requests.existsById(oldRequest)); assertFalse(pickupRepository.existsById(old));
    }
    private UUID request(UUID pickupId,Instant expiry) {
        RideRequest request=new RideRequest(); request.id=UUID.randomUUID(); request.driverId=UUID.randomUUID();
        request.passengerId=UUID.randomUUID(); request.pickupId=pickupId; request.status="ACTIVE";
        request.createdAt=clock.instant().minus(Duration.ofDays(8)); request.expiresAt=expiry;
        requests.saveAndFlush(request); return request.id;
    }

    @Test void deleteOnlyEndedOrExpiredRowsAtRetentionBoundary() {
        clock.reset();
        Instant cutoff = clock.instant().minus(Duration.ofDays(7));
        UUID completed = save(cutoff, clock.instant().plusSeconds(1));
        UUID expired = save(null, cutoff);
        UUID cancelled = save(cutoff.minusSeconds(1), clock.instant());
        UUID recentEnd = save(cutoff.plusSeconds(1), cutoff.minusSeconds(5));
        UUID recentExpiry = save(null, cutoff.plusSeconds(1));
        UUID active = save(null, clock.instant().plusSeconds(600));
        assertEquals(3, job.purgeExpiredPickups());
        for (UUID id : new UUID[]{completed, expired, cancelled}) {
            assertFalse(pickupRepository.existsById(id));
            assertEquals(0, jdbcTemplate.queryForObject("select count(*) from pickup where id = ?", Integer.class, id));
        }
        for (UUID id : new UUID[]{recentEnd, recentExpiry, active}) assertTrue(pickupRepository.existsById(id));
        assertEquals(0, job.purgeExpiredPickups());
    }

    private UUID save(Instant ended, Instant expiry) {
        Pickup pickup = Pickup.create("test-hash", "test-invite", clock.instant(), expiry, expiry,
                new PickupApi.Place("예시 장소", 37.0, 127.0));
        pickup.endedAt = ended;
        pickup.status = ended == null ? Pickup.Status.ACTIVE : Pickup.Status.COMPLETED;
        pickup.participantName = "테스트";
        pickup.vehicleLatitude = 37.0;
        pickupRepository.saveAndFlush(pickup);
        return pickup.id;
    }
}
