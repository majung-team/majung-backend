package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:ride-requests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
    "majung.rate-limit.enabled=false", "majung.login.unlink-cleanup-enabled=false",
    "majung.login.kakao-admin-key=fixture-admin-key", "majung.login.kakao-app-id=fixture-app-id"
})
@Import(PickupApiTest.TimeConfiguration.class)
class RideRequestTest {
    static final AtomicLong subjects=new AtomicLong(900000);
    @Autowired RideRequestService service;
    @Autowired VehicleAccountService links;
    @Autowired KakaoUnlinkService unlink;
    @Autowired VehicleRegistrationService vehicles;
    @Autowired MemberService members;
    @Autowired MemberConnectionRepository connections;
    @Autowired PickupService pickups;
    @Autowired PickupApiTest.MutableClock clock;
    @Autowired WebApplicationContext context;
    @org.junit.jupiter.api.BeforeEach void resetClock() { clock.reset(); }
    Member member() { Member m=members.login(""+subjects.incrementAndGet()); members.nickname(m.id,"가상회원"); return m; }
    record Driver(Member member,String auth) { }
    Driver driver() {
        Member m=member(); var code=vehicles.issueCode(); String auth="Bearer "+vehicles.register(new VehicleRegistrationApi.Registration(code.code(),"시험 차량")).vehicleToken();
        var qr=links.issue(auth); links.claim(m.id,new VehicleAccountApi.Claim(qr.challengeId(),qr.code())); links.confirm(auth,qr.challengeId());
        return new Driver(m,auth);
    }
    RideRequestApi.Submit input(Driver driver) { return new RideRequestApi.Submit(driver.member.id,new PickupApi.Place("시험 장소",37.1,127.1),UUID.randomUUID()); }
    @Test void twoNewPassengersCanRequestAndWaitWithoutPriorConnection() {
        Driver driver=driver(); Member a=member(); Member b=member(); Member c=member();
        var first=service.submit(a.id,input(driver)); var second=service.submit(b.id,input(driver));
        assertTrue(members.connections(a.id,0).isEmpty()); assertTrue(members.connections(b.id,0).isEmpty());
        service.accept(driver.auth,first.requestId()); var running=service.start(driver.auth,first.requestId());
        assertEquals("ACTIVE",running.pickup().status());
        service.accept(driver.auth,second.requestId());
        var third=service.submit(c.id,input(driver)); service.accept(driver.auth,third.requestId());
        assertEquals(2,service.passenger(c.id).get(0).peopleAhead());
        var waiting=service.passenger(b.id).get(0);
        assertEquals("QUEUED",waiting.status()); assertEquals(1,waiting.peopleAhead()); assertNull(waiting.currentPickupRemainingSeconds());
        assertEquals(409,assertThrows(ApiException.class,()->service.start(driver.auth,second.requestId())).status);
        assertEquals(403,assertThrows(ApiException.class,()->service.tracking(b.id,second.requestId())).status);
        assertEquals(404,assertThrows(ApiException.class,()->service.tracking(b.id,first.requestId())).status);
        service.readiness(a.id,first.requestId(),new PickupApi.ReadinessRequest(Pickup.Readiness.READY,1));
        pickups.arrivePickup(running.pickupId(),"Bearer "+running.driverToken());
        pickups.endPickup(running.pickupId(),"Bearer "+running.driverToken(),Pickup.Status.COMPLETED);
        assertEquals("COMPLETED",service.passenger(a.id).get(0).status());
        assertEquals("ACTIVE",service.start(driver.auth,second.requestId()).pickup().status());
        assertEquals(1,service.passenger(c.id).get(0).peopleAhead());
        clock.advance(Duration.ofHours(3));
        assertEquals("COMPLETED",service.passenger(a.id).get(0).status());
    }
    @Test void duplicateSubmissionAndAcceptanceDoNotAddQueueEntries() {
        Driver driver=driver(); Member a=member(); var input=input(driver);
        var request=service.submit(a.id,input); service.submit(a.id,input);
        assertEquals(1,service.received(driver.auth).size());
        service.accept(driver.auth,request.requestId()); service.accept(driver.auth,request.requestId());
        assertEquals(0,service.passenger(a.id).get(0).peopleAhead());
        assertEquals(409,assertThrows(ApiException.class,()->service.submit(a.id,input(driver))).status);
        service.cancel(a.id,request.requestId());
        assertEquals(409,assertThrows(ApiException.class,()->service.accept(driver.auth,request.requestId())).status);
    }
    @Test void requestsExpireAndOtherVehicleCannotAccept() {
        Driver driver=driver(); Driver other=driver(); Member a=member(); var request=service.submit(a.id,input(driver));
        assertEquals(404,assertThrows(ApiException.class,()->service.accept(other.auth,request.requestId())).status);
        assertEquals(404,assertThrows(ApiException.class,()->service.cancel(other.member.id,request.requestId())).status);
        clock.advance(Duration.ofMinutes(30));
        assertEquals("EXPIRED",service.passenger(a.id).get(0).status());
        assertEquals(409,assertThrows(ApiException.class,()->service.accept(driver.auth,request.requestId())).status);
    }
    @Test void oldInvitationPickupDoesNotBlockRequestsButBlocksStartingNextPickup() {
        Driver driver=driver(); var old=pickups.createPickup(new PickupApi.Place("기존 장소",37.2,127.2),driver.auth);
        Member a=member(); var request=service.submit(a.id,input(driver)); service.accept(driver.auth,request.requestId());
        assertEquals(409,assertThrows(ApiException.class,()->service.start(driver.auth,request.requestId())).status);
        pickups.endPickup(old.pickupId(),"Bearer "+old.driverToken(),Pickup.Status.CANCELLED);
        assertEquals("ACTIVE",service.start(driver.auth,request.requestId()).pickup().status());
    }
    @Test void concurrentAcceptsHaveDistinctOrder() throws Exception {
        Driver driver=driver(); Member a=member(); Member b=member();
        var first=service.submit(a.id,input(driver)); var second=service.submit(b.id,input(driver));
        var executor=Executors.newFixedThreadPool(2);
        try {
            List<Callable<RideRequestApi.View>> work=List.of(()->service.accept(driver.auth,first.requestId()),()->service.accept(driver.auth,second.requestId()));
            for (var result:executor.invokeAll(work)) assertEquals("QUEUED",result.get().status());
        } finally { executor.shutdownNow(); }
        var positions=List.of(service.passenger(a.id).get(0).peopleAhead(),service.passenger(b.id).get(0).peopleAhead()).stream().sorted().toList();
        assertEquals(List.of(0,1),positions);
    }
    @Test void passengerOfARequestedPickupCannotBeDisconnectedThroughTheInvitationApi() {
        Driver driver=driver(); Member a=member();
        var request=service.submit(a.id,input(driver)); service.accept(driver.auth,request.requestId());
        var active=service.start(driver.auth,request.requestId());
        assertEquals(409,assertThrows(ApiException.class,()->pickups.disconnectParticipant(active.pickupId(),"Bearer "+active.driverToken())).status);
        assertEquals("ACTIVE",pickups.getPickup(active.pickupId(),"Bearer "+active.driverToken()).status());
    }

    @Test void accountUnlinkStopsOnlyItsRequestedPickupAndRemovesAssociation() {
        Driver driver=driver(); Member a=member(); Member b=member();
        var first=service.submit(a.id,input(driver)); service.accept(driver.auth,first.requestId());
        var active=service.start(driver.auth,first.requestId());
        var second=service.submit(b.id,input(driver)); service.accept(driver.auth,second.requestId());
        unlink.receive("KakaoAK fixture-admin-key","fixture-app-id",a.providerSubject,"fixture");
        unlink.cleanupOne(a.providerSubject);
        assertEquals("CANCELLED",pickups.getPickup(active.pickupId(),"Bearer "+active.driverToken()).status());
        assertEquals(0,service.passenger(b.id).get(0).peopleAhead());
        assertEquals("ACTIVE",service.start(driver.auth,second.requestId()).pickup().status());
    }
    RideRequestApi.Invite invitation(Driver driver, Member passenger) {
        var connection = members.request(driver.member.id, passenger.id);
        members.change(passenger.id, connection.connectionId(), "accept");
        return new RideRequestApi.Invite(connection.connectionId(), new PickupApi.Place("초대 장소", 37.3, 127.3), UUID.randomUUID());
    }
    @Test void connectedInvitationNeedsPassengerConsentAndUsesExistingQueue() {
        Driver driver = driver(); Member a = member(); Member b = member();
        var first = service.submit(a.id, input(driver)); service.accept(driver.auth, first.requestId());
        var active = service.start(driver.auth, first.requestId());
        var input = invitation(driver, b);
        assertEquals(1, service.contacts(driver.auth, 0).size());
        var invited = service.invite(driver.auth, input);
        assertEquals("INVITED", invited.status()); assertTrue(invited.driverInvitation());
        assertEquals(invited.requestId(), service.invite(driver.auth, input).requestId());
        assertEquals(403, assertThrows(ApiException.class, () -> service.tracking(b.id, invited.requestId())).status);
        assertEquals(409, assertThrows(ApiException.class, () -> service.accept(driver.auth, invited.requestId())).status);
        assertEquals(409, assertThrows(ApiException.class, () -> service.start(driver.auth, invited.requestId())).status);
        assertEquals(404, assertThrows(ApiException.class, () -> service.answerInvitation(a.id, invited.requestId(), true)).status);
        var accepted = service.answerInvitation(b.id, invited.requestId(), true);
        assertEquals("QUEUED", accepted.status()); assertEquals(1, accepted.peopleAhead());
        service.answerInvitation(b.id, invited.requestId(), true);
        assertEquals(409, assertThrows(ApiException.class, () -> service.submit(b.id, input(driver))).status);
        pickups.arrivePickup(active.pickupId(), "Bearer " + active.driverToken());
        pickups.endPickup(active.pickupId(), "Bearer " + active.driverToken(), Pickup.Status.COMPLETED);
        assertEquals("ACTIVE", service.start(driver.auth, invited.requestId()).pickup().status());
        assertEquals("ACTIVE", service.tracking(b.id, invited.requestId()).pickup().status());
    }
    @Test void invitationExpiryRejectionCancellationAndLostConnectionAreEnforced() {
        Driver driver = driver(); Member a = member(); var input = invitation(driver, a);
        var invited = service.invite(driver.auth, input);
        service.answerInvitation(a.id, invited.requestId(), false);
        assertEquals("REJECTED", service.answerInvitation(a.id, invited.requestId(), false).status());
        var second = service.invite(driver.auth, new RideRequestApi.Invite(input.connectionId(), input.place(), UUID.randomUUID()));
        assertEquals("CANCELLED", service.cancelInvitation(driver.auth, second.requestId()).status());
        var third = service.invite(driver.auth, new RideRequestApi.Invite(input.connectionId(), input.place(), UUID.randomUUID()));
        clock.advance(Duration.ofMinutes(30));
        assertEquals("EXPIRED", service.passenger(a.id).stream().filter(r -> r.requestId().equals(third.requestId())).findFirst().orElseThrow().status());
        assertEquals(409, assertThrows(ApiException.class, () -> service.answerInvitation(a.id, third.requestId(), true)).status);
        var fourth = service.invite(driver.auth, new RideRequestApi.Invite(input.connectionId(), input.place(), UUID.randomUUID()));
        members.change(a.id, input.connectionId(), "disconnect");
        assertTrue(service.contacts(driver.auth, 0).isEmpty());
        assertEquals(409, assertThrows(ApiException.class, () -> service.answerInvitation(a.id, fourth.requestId(), true)).status);
        assertEquals(409, assertThrows(ApiException.class, () -> service.invite(driver.auth, input)).status);
    }
    @Test void invitationRequiresOwningVehicleAndConfirmedPersonConnection() throws Exception {
        Driver driver = driver(); Driver stranger = driver(); Member a = member();
        var connection = members.request(driver.member.id, a.id);
        var input = new RideRequestApi.Invite(connection.connectionId(), new PickupApi.Place("시험 장소",37.0,127.0),UUID.randomUUID());
        assertEquals(409, assertThrows(ApiException.class, () -> service.invite(driver.auth, input)).status);
        members.change(a.id, connection.connectionId(), "accept");
        assertEquals(409, assertThrows(ApiException.class, () -> service.invite(stranger.auth, input)).status);
        var invited = service.invite(driver.auth, input);
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_MEMBER")),Map.of("memberId",a.id.toString()),"memberId");
        mvc.perform(post("/api/v1/ride-requests/"+invited.requestId()+"/accept-invitation").with(oauth2Login().oauth2User(principal))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ride-requests/"+invited.requestId()+"/accept-invitation").with(oauth2Login().oauth2User(principal)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        links.detachDevice(driver.auth);
        assertEquals(409, assertThrows(ApiException.class, () -> service.start(driver.auth, invited.requestId())).status);
    }
    @Test void sessionAndCsrfAreRequiredOnPassengerApi() throws Exception {
        var mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/ride-requests")).andExpect(status().isUnauthorized());
        Driver driver=driver(); Member passenger=member();
        var principal=new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_MEMBER")),Map.of("memberId",passenger.id.toString()),"memberId");
        String body="{\"driverId\":\""+driver.member.id+"\",\"requestId\":\""+UUID.randomUUID()+"\",\"place\":{\"label\":\"시험 장소\",\"latitude\":37,\"longitude\":127}}";
        mvc.perform(post("/api/v1/ride-requests").with(oauth2Login().oauth2User(principal)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ride-requests").with(oauth2Login().oauth2User(principal)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/vehicles/ride-requests")).andExpect(status().isUnauthorized());
    }
}
