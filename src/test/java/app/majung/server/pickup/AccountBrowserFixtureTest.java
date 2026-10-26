package app.majung.server.pickup;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/** Explicit, loopback-only manual browser fixture. Never packaged in bootJar or used in production. */
@EnabledIfEnvironmentVariable(named = "MAJUNG_BROWSER_FIXTURE", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.address=127.0.0.1", "server.port=18081", "server.servlet.session.cookie.secure=false",
        "spring.datasource.url=jdbc:h2:mem:account-browser;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
        "majung.rate-limit.enabled=false", "majung.login.enabled=true",
        "majung.login.kakao-client-id=fixture-client", "majung.login.kakao-client-secret=fixture-secret",
        "majung.login.kakao-admin-key=fixture-admin-key", "majung.login.kakao-app-id=fixture-app-id",
        "majung.login.redirect-uri=https://example.test/api/v1/auth/callback/kakao"
})
@Import(AccountBrowserFixtureTest.Provider.class)
class AccountBrowserFixtureTest {
    @org.springframework.beans.factory.annotation.Autowired MemberService members;
    @org.springframework.beans.factory.annotation.Autowired VehicleRegistrationService registration;
    @org.springframework.beans.factory.annotation.Autowired VehicleAccountService vehicleAccounts;
    @org.springframework.beans.factory.annotation.Autowired RideRequestService rides;
    @org.springframework.beans.factory.annotation.Autowired PickupService pickups;
    private static final String CALLBACK = "http://127.0.0.1:18082/api/v1/auth/callback/kakao";
    private static final AtomicLong identity = new AtomicLong(70001);
    private static HttpServer provider;

    static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null) return result;
        for (String field : raw.split("&")) {
            String[] parts = field.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }
    static void reply(com.sun.net.httpserver.HttpExchange exchange, String type, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Provider {
        @Bean @Primary ClientRegistrationRepository browserRegistration() throws Exception {
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext("/authorize", exchange -> {
                Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
                if (!CALLBACK.equals(query.get("redirect_uri"))) { exchange.sendResponseHeaders(400,-1); exchange.close(); return; }
                String state = URLEncoder.encode(query.getOrDefault("state", ""), StandardCharsets.UTF_8);
                reply(exchange, "text/html;charset=UTF-8", "<html lang='ko'><meta charset='utf-8'><h1>로컬 로그인 시험</h1>"
                        + "<p>카카오 서버가 아닌 가상 인증 공급자입니다. 실제 계정 정보를 입력하지 않습니다.</p>"
                        + "<p><a href='/continue?person=one&amp;state=" + state + "'>가상 사용자 1로 계속</a></p>"
                        + "<p><a href='/continue?person=two&amp;state=" + state + "'>가상 사용자 2로 계속</a></p>"
                        + "<p><a href='/continue?person=three&amp;state=" + state + "'>가상 사용자 3으로 계속</a></p></html>");
            });
            provider.createContext("/continue", exchange -> {
                Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
                identity.set("one".equals(query.get("person")) ? 70001 : "two".equals(query.get("person")) ? 70002 : 70003);
                exchange.getResponseHeaders().set("Location", CALLBACK + "?code=fixture-code&state="
                        + URLEncoder.encode(query.getOrDefault("state", ""), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(302,-1); exchange.close();
            });
            provider.createContext("/token", exchange -> reply(exchange, "application/json",
                    "{\"access_token\":\"fixture-token\",\"token_type\":\"Bearer\"}"));
            provider.createContext("/me", exchange -> reply(exchange, "application/json", "{\"id\":" + identity.get() + "}"));
            provider.start();
            String origin = "http://127.0.0.1:" + provider.getAddress().getPort();
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("kakao")
                    .clientId("fixture-client").clientSecret("fixture-secret")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(CALLBACK)
                    .authorizationUri(origin + "/authorize").tokenUri(origin + "/token")
                    .userInfoUri(origin + "/me").userNameAttributeName("id").build());
        }
    }
    @Test void keepFixtureAvailableForBrowser() throws Exception {
        Member driver=members.login("70001"); members.nickname(driver.id,"가상운전자");
        Member first=members.login("70002"); members.nickname(first.id,"가상탑승자A");
        Member second=members.login("70003"); members.nickname(second.id,"가상탑승자B");
        var code=registration.issueCode(); var vehicle=registration.register(new VehicleRegistrationApi.Registration(code.code(),"시험 차량"));
        String auth="Bearer "+vehicle.vehicleToken(); var qr=vehicleAccounts.issue(auth);
        vehicleAccounts.claim(driver.id,new VehicleAccountApi.Claim(qr.challengeId(),qr.code())); vehicleAccounts.confirm(auth,qr.challengeId());
        var firstRequest=rides.submit(first.id,new RideRequestApi.Submit(driver.id,new PickupApi.Place("A만 볼 수 있는 장소",37.1,127.1),java.util.UUID.randomUUID()));
        rides.accept(auth,firstRequest.requestId()); var active=rides.start(auth,firstRequest.requestId());
        if ("true".equals(System.getenv("MAJUNG_INVITATION_FIXTURE"))) {
            var connection = members.request(driver.id, second.id);
            members.change(second.id, connection.connectionId(), "accept");
            rides.invite(auth, new RideRequestApi.Invite(connection.connectionId(), new PickupApi.Place("초대 시험 장소",37.3,127.3),java.util.UUID.randomUUID()));
        }
        Path capability=Path.of(System.getProperty("java.io.tmpdir"),"majung-browser-vehicle.json");
        Files.writeString(capability,"{\"vehicleId\":\""+vehicle.vehicleId()+"\",\"vehicleToken\":\""+vehicle.vehicleToken()+"\",\"pickupId\":\""+active.pickupId()+"\",\"driverToken\":\""+active.driverToken()+"\"}");
        Files.setPosixFilePermissions(capability,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Path stop = Path.of(System.getProperty("java.io.tmpdir"), "majung-browser-fixture.stop");
        Files.deleteIfExists(stop);
        System.out.println("Manual browser fixture ready: API 18081, use web proxy 18082; synthetic users only");
        try {
            long deadline = System.nanoTime() + java.time.Duration.ofMinutes(15).toNanos();
            while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(500);
        } finally { provider.stop(0); Files.deleteIfExists(stop); Files.deleteIfExists(capability); }
    }
}
