package app.majung.server.pickup;

import app.majung.server.MajungServerApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import tools.jackson.databind.json.JsonMapper;

/** CLI only: refuses production DBs, creates fixtures only in an isolated restored DB. */
public final class RestoreVerification {
    public static void main(String[] args) throws Exception {
        String database = System.getenv("DB_URL");
        if (args.length != 0 || database == null || !database.matches(
                "jdbc:postgresql://127\\.0\\.0\\.1:5432/majung_restore_check_[0-9a-f]{16}")) {
            throw new IllegalStateException("An isolated restore database is required");
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MajungServerApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=18099",
                "--spring.jpa.hibernate.ddl-auto=validate", "--majung.login.enabled=false",
                "--management.server.port=-1")) {
            verify(context, "http://127.0.0.1:18099");
            Files.writeString(Path.of("/var/lib/majung-operations/restore-functional.json"),
                    "{\"sessionAuthentication\":true,\"accountQuery\":true,\"pickupQuery\":true,\"logoutRevocation\":true}");
            new CountDownLatch(1).await();
        }
    }

    @SuppressWarnings("unchecked")
    static void verify(ConfigurableApplicationContext context, String origin) throws Exception {
        Member member = new Member();
        member.id = UUID.randomUUID();
        member.provider = "restore-test";
        member.providerSubject = UUID.randomUUID().toString();
        member.majungId = "MJ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        member.nickname = "복원 시험";
        member.normalizedNickname = member.nickname;
        member.profileReady = true;
        member.createdAt = Instant.now();
        context.getBean(MemberRepository.class).saveAndFlush(member);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
        var principal = new DefaultOAuth2User(authorities, Map.of("memberId", member.id.toString()), "memberId");
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        SessionRepository<Session> repository = (SessionRepository<Session>) (SessionRepository<?>)
                context.getBean(JdbcIndexedSessionRepository.class);
        Session session = repository.createSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        repository.save(session);
        String cookie = "JSESSIONID=" + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var json = JsonMapper.builder().build();
        var response = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/auth/session"))
                .timeout(Duration.ofSeconds(10)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        var data = json.readTree(response.body());
        require(response.statusCode() == 200 && data.path("authenticated").asBoolean(), "Session authentication failed");
        require(data.path("member").path("memberId").asText().equals(member.id.toString()), "Session member mismatch");
        String csrf = data.path("csrfToken").asText();
        response = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/accounts/me"))
                .timeout(Duration.ofSeconds(10)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200 && json.readTree(response.body()).path("memberId").asText().equals(member.id.toString()), "Account query failed");
        TokenService tokens = context.getBean(TokenService.class);
        String owner = tokens.issue();
        Pickup pickup = Pickup.create(tokens.hash(owner), tokens.hash(tokens.issue()), Instant.now(),
                Instant.now().plusSeconds(600), Instant.now().plusSeconds(600), new PickupApi.Place("복원 시험 장소", 37.0, 127.0));
        context.getBean(PickupRepository.class).saveAndFlush(pickup);
        response = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/pickups/" + pickup.id))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + owner).GET().build(), HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200 && json.readTree(response.body()).path("pickupId").asText().equals(pickup.id.toString()), "Pickup query failed");
        response = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/auth/logout"))
                .timeout(Duration.ofSeconds(10)).header("Cookie", cookie).header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 204 && repository.findById(session.getId()) == null, "Logout failed");
        response = client.send(HttpRequest.newBuilder(URI.create(origin + "/api/v1/accounts/me"))
                .timeout(Duration.ofSeconds(10)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 401, "Logged-out session remained authenticated");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
