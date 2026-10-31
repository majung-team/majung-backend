package app.majung.server.pickup;

import app.majung.server.MajungServerApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Two real HTTP servers and one isolated H2 database; no external identity provider. */
class SharedSessionServerTest {
    private ConfigurableApplicationContext start(boolean initialize) {
        return new SpringApplicationBuilder(MajungServerApplication.class).run(
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:shared-session-servers;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=validate", "--spring.sql.init.mode=" + (initialize ? "always" : "never"),
                "--spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
                "--spring.session.jdbc.initialize-schema=" + (initialize ? "always" : "never"),
                "--server.servlet.session.cookie.secure=false", "--majung.login.enabled=false",
                "--majung.rate-limit.enabled=false", "--spring.main.banner-mode=off", "--logging.level.root=ERROR");
    }
    private URI uri(ConfigurableApplicationContext context, String path) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://127.0.0.1:" + port + path);
    }
    @SuppressWarnings("unchecked")
    @Test void eitherServerReadsLoginAndLogoutRevokesBoth() throws Exception {
        try (var blue = start(true); var green = start(false)) {
            var member = blue.getBean(MemberService.class).login("99111001");
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
            var principal = new DefaultOAuth2User(authorities, Map.of("memberId", member.id.toString()), "memberId");
            var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
            SessionRepository<Session> firstRepository = (SessionRepository<Session>)
                    (SessionRepository<?>) blue.getBean(JdbcIndexedSessionRepository.class);
            Session session = firstRepository.createSession();
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    new SecurityContextImpl(authentication));
            firstRepository.save(session);
            assertNotNull(green.getBean(JdbcIndexedSessionRepository.class).findById(session.getId()));
            String cookie = "JSESSIONID=" + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
            var client = HttpClient.newHttpClient();
            var json = JsonMapper.builder().build();
            String csrf = null;
            for (var server : List.of(blue, green)) {
                var response = client.send(HttpRequest.newBuilder(uri(server, "/api/v1/auth/session"))
                        .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var data = json.readTree(response.body());
                assertTrue(data.path("authenticated").asBoolean());
                assertEquals(member.id.toString(), data.path("member").path("memberId").asText());
                csrf = data.path("csrfToken").asText();
                assertFalse(csrf.isBlank());
            }
            var logout = client.send(HttpRequest.newBuilder(uri(blue, "/api/v1/auth/logout"))
                    .header("Cookie", cookie).header("X-CSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, logout.statusCode());
            var after = client.send(HttpRequest.newBuilder(uri(green, "/api/v1/auth/session"))
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(json.readTree(after.body()).path("authenticated").asBoolean());
            assertNull(firstRepository.findById(session.getId()));
            RestoreVerification.verify(blue, uri(blue, "").toString());
        }
    }
}
