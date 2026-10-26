package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:lifecycle;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
    "majung.expiration.interval-ms=3600000", "majung.rate-limit.enabled=false"
})
@Import(PickupApiTest.TimeConfiguration.class)
class PickupLifecycleTest {
    @Autowired PickupService service;
    @Autowired PickupRepository pickups;
    @Autowired PickupExpirationJob job;
    @Autowired PickupApiTest.MutableClock clock;
    record Session(UUID id, String owner, String guest) { }
    @BeforeEach void reset() { pickups.deleteAll(); clock.reset(); }
    Session active() {
        var created = service.createPickup(new PickupApi.Place("시험 장소",37.0,127.0),null);
        var guest = service.joinPickup(created.pickupId(),new PickupApi.JoinRequest(created.inviteToken(),"시험 탑승자"));
        service.approveParticipant(created.pickupId(),guest.participantId(),"Bearer "+created.driverToken());
        return new Session(created.pickupId(),"Bearer "+created.driverToken(),"Bearer "+guest.participantToken());
    }
    void vehicle(Session session, long sequence) {
        service.updateVehicle(session.id,session.owner,new PickupApi.VehicleRequest(37.0,127.0,300L,clock.instant(),sequence));
    }
    @Test void inactivityExpiresAtBoundaryAndPollingCannotKeepItAlive() {
        Session session=active(); var started=clock.instant(); vehicle(session,1);
        clock.advance(Duration.ofSeconds(1799));
        assertEquals("ACTIVE",service.getPickup(session.id,session.owner).status());
        service.updateReadiness(session.id,session.guest,new PickupApi.ReadinessRequest(Pickup.Readiness.READY,1));
        clock.advance(Duration.ofSeconds(1));
        var ended=service.getPickup(session.id,session.owner);
        assertEquals("EXPIRED",ended.status()); assertNull(ended.place()); assertEquals(started.plusSeconds(1800),ended.endedAt());
        var stored=pickups.findById(session.id).orElseThrow();
        assertNull(stored.vehicleLatitude); assertNull(stored.durationSeconds);
        assertEquals(410,assertThrows(ApiException.class,()->vehicle(session,2)).status);
    }
    @Test void validNewVehicleDataRenewsActivityButOldOrInvalidatedDataDoesNot() {
        Session session=active(); vehicle(session,1);
        clock.advance(Duration.ofMinutes(20)); vehicle(session,2);
        var validAt=clock.instant();
        clock.advance(Duration.ofMinutes(20));
        service.updateVehicle(session.id,session.owner,new PickupApi.VehicleRequest(37.0,127.0,300L,validAt,3));
        service.invalidateVehicle(session.id,session.owner,4);
        clock.advance(Duration.ofMinutes(10));
        assertEquals("EXPIRED",service.getPickup(session.id,session.owner).status());
    }
    @Test void duplicateArrivalAndFreshDataDoNotExtendArrivalLimit() {
        Session session=active(); service.arrivePickup(session.id,session.owner); var arrived=clock.instant();
        clock.advance(Duration.ofMinutes(29)); vehicle(session,1); service.arrivePickup(session.id,session.owner);
        assertEquals(arrived,pickups.findById(session.id).orElseThrow().arrivedAt);
        clock.advance(Duration.ofMinutes(1));
        assertEquals("EXPIRED",service.getPickup(session.id,session.owner).status());
        assertEquals(arrived.plusSeconds(1800),pickups.findById(session.id).orElseThrow().endedAt);
    }
    @Test void hardLimitStillExpiresWithContinuousValidVehicleUpdates() {
        Session session=active(); var start=clock.instant();
        for (int step=1;step<=35;step++) { clock.advance(Duration.ofMinutes(20)); vehicle(session,step); }
        clock.advance(Duration.ofMinutes(19)); vehicle(session,36);
        clock.advance(Duration.ofMinutes(1));
        assertEquals(start.plus(Duration.ofHours(12)),service.getPickup(session.id,session.owner).endedAt());
        assertEquals("EXPIRED",service.getPickup(session.id,session.owner).status());
    }
    @Test void scheduledCleanupExpiresWithoutReadsAndKeepsCompletedState() {
        Session completed=active(); service.arrivePickup(completed.id,completed.owner);
        service.endPickup(completed.id,completed.owner,Pickup.Status.COMPLETED);
        for (int index=0;index<105;index++) active();
        clock.advance(Duration.ofMinutes(30));
        assertEquals(105,job.expireDuePickups());
        assertEquals(0,job.expireDuePickups());
        assertEquals("COMPLETED",service.getPickup(completed.id,completed.owner).status());
        assertEquals(105,pickups.findAll().stream().filter(p->p.status==Pickup.Status.EXPIRED).count());
    }
}
