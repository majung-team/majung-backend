package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** OAuth provider HTTP fixture; never uses a real Kakao user or real provider credentials. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:account-flow;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
        "majung.rate-limit.enabled=false", "majung.login.enabled=true",
        "majung.login.kakao-client-id=fixture-client", "majung.login.kakao-client-secret=fixture-secret",
        "majung.login.redirect-uri=https://example.test/api/v1/auth/callback/kakao",
        "majung.login.kakao-admin-key=fixture-admin-key", "majung.login.kakao-app-id=fixture-app-id",
        "majung.login.unlink-cleanup-enabled=false"
})
@Import(AccountFlowTest.ProviderFixture.class)
class AccountFlowTest {
    static final AtomicLong subject = new AtomicLong(1000);
    static HttpServer provider;
    static {
        try {
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext("/token", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean valid = form.contains("code=fixture-code") && form.contains("client_id=fixture-client")
                        && form.contains("client_secret=fixture-secret");
                String body = valid ? "{\"access_token\":\"fixture-provider-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}"
                        : "{\"error\":\"invalid_grant\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(valid ? 200 : 400, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            provider.createContext("/me", exchange -> {
                boolean valid = "Bearer fixture-provider-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = ("{\"id\":" + subject.get() + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(valid ? 200 : 401, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            provider.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    @AfterAll static void stopProvider() { provider.stop(0); }
    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderFixture {
        @Bean @Primary ClientRegistrationRepository fixtureRegistration() {
            String origin = "http://127.0.0.1:" + provider.getAddress().getPort();
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("kakao")
                    .clientId("fixture-client").clientSecret("fixture-secret")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://example.test/api/v1/auth/callback/kakao")
                    .authorizationUri(origin + "/authorize").tokenUri(origin + "/token")
                    .userInfoUri(origin + "/me").userNameAttributeName("id").build());
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired MemberService memberService;
    @Autowired KakaoUnlinkService unlinkService;
    @Autowired KakaoUnlinkRepository unlinkRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberConnectionRepository memberConnectionRepository;
    MockMvc mvc;
    final JsonMapper json = JsonMapper.builder().build();
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    record Login(MockHttpSession session, String csrf, UUID memberId, String majungId) { }

    Login login() throws Exception {
        subject.incrementAndGet();
        MvcResult start = mvc.perform(get("/api/v1/auth/authorize/kakao")).andExpect(status().is3xxRedirection()).andReturn();
        MockHttpSession session = (MockHttpSession) start.getRequest().getSession(false);
        String before = session.getId();
        String state = java.net.URLDecoder.decode(UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl()).build().getQueryParams().getFirst("state"), StandardCharsets.UTF_8);
        assertNotNull(state);
        MvcResult callback = mvc.perform(get("/api/v1/auth/callback/kakao").session(session)
                        .param("state", state).param("code", "fixture-code"))
                .andReturn();
        if (!"/".equals(callback.getResponse().getRedirectedUrl())) {
            Object failure = callback.getRequest().getSession().getAttribute("SPRING_SECURITY_LAST_EXCEPTION");
            throw new AssertionError("Fixture login failed", failure instanceof Throwable ? (Throwable) failure : null);
        }
        session = (MockHttpSession) callback.getRequest().getSession(false);
        assertNotEquals(before, session.getId());
        JsonNode info = tree(mvc.perform(get("/api/v1/auth/session").session(session)).andExpect(status().isOk()).andReturn());
        assertTrue(info.path("authenticated").asBoolean());
        assertFalse(info.toString().contains("fixture-provider-token"));
        assertFalse(info.toString().contains("providerSubject"));
        String majungId = info.path("member").path("majungId").asText();
        assertTrue(majungId.matches("MJ-[A-F0-9]{10}"));
        return new Login(session, info.path("csrfToken").asText(), UUID.fromString(info.path("member").path("memberId").asText()), majungId);
    }
    JsonNode tree(MvcResult result) { return json.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)); }
    JsonNode nickname(Login login, String nickname) throws Exception {
        return tree(mvc.perform(patch("/api/v1/accounts/me").session(login.session()).header("X-CSRF-TOKEN", login.csrf())
                .contentType("application/json").content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isOk()).andReturn());
    }
    JsonNode request(Login from, Login to) throws Exception {
        return tree(mvc.perform(post("/api/v1/connections").session(from.session()).header("X-CSRF-TOKEN", from.csrf())
                .contentType("application/json").content("{\"memberId\":\"" + to.memberId() + "\"}"))
                .andExpect(status().isOk()).andReturn());
    }
    @Test void oauthStateIsBoundToBrowserAndCannotBeReplayed() throws Exception {
        subject.incrementAndGet();
        long before = memberRepository.count();
        MvcResult start = mvc.perform(get("/api/v1/auth/authorize/kakao")).andReturn();
        MockHttpSession browser = (MockHttpSession) start.getRequest().getSession(false);
        String state = java.net.URLDecoder.decode(UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl()).build().getQueryParams().getFirst("state"), StandardCharsets.UTF_8);
        mvc.perform(get("/api/v1/auth/callback/kakao").param("state", state).param("code", "fixture-code"))
                .andExpect(redirectedUrl("/?login=failed"));
        assertEquals(before, memberRepository.count());
        mvc.perform(get("/api/v1/auth/callback/kakao").session(browser).param("state", "wrong").param("code", "fixture-code"))
                .andExpect(redirectedUrl("/?login=failed"));
        assertEquals(before, memberRepository.count());
        mvc.perform(get("/api/v1/auth/callback/kakao").session(browser).param("state", state).param("code", "fixture-code"))
                .andExpect(redirectedUrl("/"));
        assertEquals(before + 1, memberRepository.count());
        mvc.perform(get("/api/v1/auth/callback/kakao").session(browser).param("state", state).param("code", "fixture-code"))
                .andExpect(redirectedUrl("/?login=failed"));
        assertEquals(before + 1, memberRepository.count());
    }
    @Test void profileAndSearchRequireLoginAndCsrfDoesNotAffectExistingBearerApi() throws Exception {
        mvc.perform(get("/api/v1/accounts/search").param("query", "승준")).andExpect(status().isUnauthorized());
        Login user = login();
        mvc.perform(patch("/api/v1/accounts/me").session(user.session()).contentType("application/json").content("{\"nickname\":\"승준\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/accounts/search").session(user.session()).param("query", "승준")).andExpect(status().isConflict());
        assertEquals(user.majungId(), nickname(user, "승준").path("majungId").asText());
        assertEquals(user.majungId(), nickname(user, "새 닉네임").path("majungId").asText());
        mvc.perform(post("/api/v1/pickups").contentType("application/json")
                .content("{\"place\":{\"label\":\"예시\",\"latitude\":37,\"longitude\":127}}"))
                .andExpect(status().isCreated());
    }
    @Test void duplicateNicknamesSearchByNameOrIdWithoutPrivateDataOrWildcardExpansion() throws Exception {
        Login first = login(), second = login(), viewer = login();
        nickname(first, "동일닉네임"); nickname(second, "동일닉네임"); nickname(viewer, "검색자");
        JsonNode results = tree(mvc.perform(get("/api/v1/accounts/search").session(viewer.session()).param("query", "동일닉네임"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(2, results.size());
        assertNotEquals(results.get(0).path("majungId").asText(), results.get(1).path("majungId").asText());
        assertEquals(3, results.get(0).size());
        assertFalse(results.toString().contains("provider"));
        JsonNode exact = tree(mvc.perform(get("/api/v1/accounts/search").session(viewer.session()).param("query", first.majungId().toLowerCase()))
                .andExpect(status().isOk()).andReturn());
        assertEquals(1, exact.size());
        JsonNode wildcard = tree(mvc.perform(get("/api/v1/accounts/search").session(viewer.session()).param("query", "%%"))
                .andExpect(status().isOk()).andReturn());
        assertEquals(0, wildcard.size());
        mvc.perform(get("/api/v1/accounts/search").session(viewer.session()).param("query", "동일").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }
    @Test void onlyRecipientAcceptsAndConnectionDoesNotApprovePickupOrExposeLocation() throws Exception {
        Login from = login(), to = login(), stranger = login();
        nickname(from, "보낸 사람"); nickname(to, "받는 사람"); nickname(stranger, "다른 사람");
        JsonNode pending = request(from, to);
        String id = pending.path("connectionId").asText();
        assertEquals("PENDING", pending.path("status").asText());
        assertEquals(id, request(to, from).path("connectionId").asText());
        assertEquals("PENDING", request(from, to).path("status").asText());
        mvc.perform(post("/api/v1/connections/" + id + "/accept").session(from.session()).header("X-CSRF-TOKEN", from.csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/connections/" + id + "/accept").session(stranger.session()).header("X-CSRF-TOKEN", stranger.csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/connections/" + id + "/accept").session(to.session()).header("X-CSRF-TOKEN", to.csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        nickname(to, "바꾼 이름");
        JsonNode list = tree(mvc.perform(get("/api/v1/connections").session(from.session())).andExpect(status().isOk()).andReturn());
        assertEquals("바꾼 이름", list.get(0).path("nickname").asText());
        assertEquals(5, list.get(0).size());
        assertFalse(list.toString().contains("latitude"));
        mvc.perform(post("/api/v1/connections/" + id + "/disconnect").session(from.session()).header("X-CSRF-TOKEN", from.csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/connections/" + id + "/accept").session(to.session()).header("X-CSRF-TOKEN", to.csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/connections").session(from.session()).header("X-CSRF-TOKEN", from.csrf())
                .contentType("application/json").content("{\"memberId\":\"" + from.memberId() + "\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void logoutRequiresCsrfAndInvalidatesServerSession() throws Exception {
        Login user = login();
        mvc.perform(post("/api/v1/auth/logout").session(user.session())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/logout").session(user.session()).header("X-CSRF-TOKEN", user.csrf()))
                .andExpect(status().isNoContent());
        assertTrue(user.session().isInvalid());
        mvc.perform(get("/api/v1/accounts/me")).andExpect(status().isUnauthorized());
    }
    @Test void unlinkRequiresProviderCredentialAndPersistsBeforeBlockingSessions() throws Exception {
        Login user = login(), friend = login();
        String providerSubject = memberRepository.findById(user.memberId()).orElseThrow().providerSubject;
        nickname(user, "연결 해제 사용자"); nickname(friend, "연결 해제 상대");
        String connectionId = request(user, friend).path("connectionId").asText();
        String endpoint = "/api/v1/auth/kakao/unlink";
        mvc.perform(post(endpoint).contentType("application/x-www-form-urlencoded")
                .param("app_id", "fixture-app-id").param("user_id", providerSubject).param("referrer_type", "UNLINK_FROM_APPS"))
                .andExpect(status().isForbidden());
        mvc.perform(post(endpoint).header("Authorization", "KakaoAK fixture-admin-key")
                .contentType("application/x-www-form-urlencoded").param("app_id", "other-app")
                .param("user_id", providerSubject).param("referrer_type", "UNLINK_FROM_APPS"))
                .andExpect(status().isForbidden());
        assertFalse(unlinkRepository.existsById(providerSubject));
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(post(endpoint).header("Authorization", "KakaoAK fixture-admin-key")
                    .contentType("application/x-www-form-urlencoded").param("app_id", "fixture-app-id")
                    .param("user_id", providerSubject).param("referrer_type", "UNLINK_FROM_APPS"))
                    .andExpect(status().isOk());
        }
        assertTrue(unlinkRepository.existsById(providerSubject));
        assertTrue(memberRepository.existsById(user.memberId())); // cleanup has not run yet
        mvc.perform(get("/api/v1/connections").session(friend.session()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/accounts/search").session(friend.session()).param("query", "연결 해제 사용자"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/accounts/me").session(user.session())).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/accounts/me").session(user.session()).header("X-CSRF-TOKEN", user.csrf())
                .contentType("application/json").content("{\"nickname\":\"금지된 변경\"}"))
                .andExpect(status().isUnauthorized());
        assertThrows(ApiException.class, () -> memberService.login(providerSubject));
        unlinkService.cleanupOne(providerSubject);
        assertFalse(memberRepository.existsById(user.memberId()));
        assertFalse(memberConnectionRepository.existsById(UUID.fromString(connectionId)));
        assertFalse(unlinkRepository.existsById(providerSubject));
        assertTrue(memberRepository.existsById(friend.memberId()));
        mvc.perform(get("/api/v1/accounts/me").session(user.session())).andExpect(status().isUnauthorized());
        // An absent user and a repeated cleanup are harmless.
        unlinkService.receive("KakaoAK fixture-admin-key", "fixture-app-id", providerSubject, "UNLINK_FROM_APPS");
        unlinkService.cleanupOne(providerSubject);
        unlinkService.cleanupOne(providerSubject);
        assertFalse(unlinkRepository.existsById(providerSubject));
    }
    @Test void concurrentFirstLoginAndOppositeRequestsHaveOneRecord() throws Exception {
        String providerSubject = Long.toString(subject.addAndGet(100));
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Member>> attempts = List.of(() -> memberService.login(providerSubject), () -> memberService.login(providerSubject));
            var results = executor.invokeAll(attempts);
            UUID id = results.get(0).get().id;
            assertEquals(id, results.get(1).get().id);
            Member other = memberService.login(Long.toString(subject.incrementAndGet()));
            memberService.nickname(id, "동시 요청 하나"); memberService.nickname(other.id, "동시 요청 둘");
            List<Callable<UUID>> requests = List.of(() -> memberService.request(id, other.id).connectionId(),
                    () -> memberService.request(other.id, id).connectionId());
            var connections = executor.invokeAll(requests);
            assertEquals(connections.get(0).get(), connections.get(1).get());
        } finally { executor.shutdownNow(); }
    }
}
