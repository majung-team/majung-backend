package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:enrollment;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
    "majung.rate-limit.enabled=false", "majung.login.unlink-cleanup-enabled=false"
})
@Import(PickupApiTest.TimeConfiguration.class)
class VehicleEnrollmentTest {
    static final AtomicLong subjects=new AtomicLong(900000);
    @Autowired VehicleEnrollmentService enrollments;
    @Autowired VehicleRegistrationService registration;
    @Autowired VehicleAccountService links;
    @Autowired MemberService members;
    @Autowired PickupApiTest.MutableClock clock;
    @Autowired VehicleEnrollmentRepository records;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @org.junit.jupiter.api.BeforeEach void resetClock() { clock.reset(); }
    Member member() { Member m=members.login(""+subjects.incrementAndGet()); members.nickname(m.id,"가상회원"); return m; }
    String auth(VehicleEnrollmentApi.Started start) { return "Bearer "+start.deviceToken(); }
    @Test void noAuthorityUntilVehicleConfirmsAndRetryKeepsSameVehicle() {
        var start=enrollments.start(); Member driver=member();
        assertThrows(ApiException.class,()->registration.requireActive(auth(start)));
        assertEquals("AWAITING_ACCOUNT",enrollments.device(start.enrollmentId(),auth(start)).status());
        enrollments.claim(driver.id,start.enrollmentId(),start.code());
        assertTrue(links.links(driver.id).isEmpty());
        assertThrows(ApiException.class,()->registration.requireActive(auth(start)));
        assertThrows(ApiException.class,()->enrollments.confirm(start.enrollmentId(),auth(start),"wrong-id"));
        assertEquals("LINKED",enrollments.confirm(start.enrollmentId(),auth(start),driver.majungId).status());
        assertEquals(start.enrollmentId(),registration.current(auth(start)).vehicleId());
        enrollments.confirm(start.enrollmentId(),auth(start),driver.majungId);
        assertEquals(1,links.links(driver.id).size());
        assertThrows(ApiException.class,()->enrollments.claim(driver.id,start.enrollmentId(),start.code()));
        assertThrows(ApiException.class,()->enrollments.cancelDevice(start.enrollmentId(),auth(start)));
        clock.advance(Duration.ofMinutes(6));
        assertEquals("LINKED",enrollments.device(start.enrollmentId(),auth(start)).status());
        registration.revoke(start.enrollmentId());
        assertThrows(ApiException.class,()->enrollments.device(start.enrollmentId(),auth(start)));
    }
    @Test void qrCannotActAsDeviceOtherMembersCannotReadOrReplaceRequest() {
        var start=enrollments.start(); Member driver=member(); Member other=member();
        assertThrows(ApiException.class,()->enrollments.claim(driver.id,start.enrollmentId(),start.deviceToken()));
        assertThrows(ApiException.class,()->enrollments.device(start.enrollmentId(),"Bearer "+start.code()));
        enrollments.claim(driver.id,start.enrollmentId(),start.code());
        assertThrows(ApiException.class,()->enrollments.claim(other.id,start.enrollmentId(),start.code()));
        assertThrows(ApiException.class,()->enrollments.account(other.id,start.enrollmentId()));
        assertThrows(ApiException.class,()->enrollments.cancelAccount(other.id,start.enrollmentId()));
        assertThrows(ApiException.class,()->enrollments.confirm(start.enrollmentId(),"Bearer "+start.code(),driver.majungId));
        assertNotEquals(start.deviceToken(),records.findById(start.enrollmentId()).orElseThrow().deviceHash);
    }
    @Test void browserClaimRequiresLoginAndCsrf() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        var start=enrollments.start(); Member driver=member();
        String path="/api/v1/vehicle-links/enrollments/"+start.enrollmentId();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var login=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login()
                .oauth2User(new org.springframework.security.oauth2.core.user.DefaultOAuth2User(
                    java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_MEMBER")),
                    java.util.Map.of("memberId",driver.id.toString()), "memberId"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(login)
                .contentType("application/json").content("{\"code\":\""+start.code()+"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(login)
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType("application/json").content("{\"code\":\""+start.code()+"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertThrows(ApiException.class,()->registration.requireActive(auth(start)));
    }
    @Test void simultaneousAccountsCannotBothClaimOneQr() throws Exception {
        var start=enrollments.start(); Member first=member(); Member second=member();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var gate=new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<Boolean>> results=new java.util.ArrayList<>();
            for (Member member:java.util.List.of(first,second)) results.add(pool.submit(()->{
                gate.await();
                try { enrollments.claim(member.id,start.enrollmentId(),start.code()); return true; }
                catch (ApiException rejected) { assertEquals(409,rejected.status); return false; }
            }));
            gate.countDown();
            int accepted=0;
            for (var result:results) if(result.get(10,java.util.concurrent.TimeUnit.SECONDS)) accepted++;
            assertEquals(1,accepted);
        } finally { pool.shutdownNow(); }
    }
    @Test void expiredAndCancelledQrCannotCreateVehicle() {
        var start=enrollments.start(); Member driver=member();
        enrollments.claim(driver.id,start.enrollmentId(),start.code());
        clock.advance(Duration.ofMinutes(5));
        assertThrows(ApiException.class,()->enrollments.confirm(start.enrollmentId(),auth(start),driver.majungId));
        assertThrows(ApiException.class,()->enrollments.claim(driver.id,start.enrollmentId(),start.code()));
        assertThrows(ApiException.class,()->registration.requireActive(auth(start)));
        var next=enrollments.start(); enrollments.claim(driver.id,next.enrollmentId(),next.code());
        enrollments.cancelAccount(driver.id,next.enrollmentId());
        assertThrows(ApiException.class,()->enrollments.confirm(next.enrollmentId(),auth(next),driver.majungId));
        assertTrue(links.links(driver.id).isEmpty());
    }
}
