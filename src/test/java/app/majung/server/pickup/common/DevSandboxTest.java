package app.majung.server.pickup.common;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.majung.server.pickup.dev.DevDummySeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dev-sandbox;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
        "majung.deployment=false", "majung.development-tools=true",
        "majung.dummy-data.enabled=true", "majung.rate-limit.enabled=false"
})
class DevSandboxTest {
    @Autowired WebApplicationContext context;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void seedsDiverseDriversAndUsersSupportsLoginSearchSimulationAndReset() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/sandbox")).andExpect(status().isOk());
        mvc.perform(get("/sandbox/sandbox.js")).andExpect(status().isOk());

        MvcResult stateRes = mvc.perform(get("/api/v1/dev/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", hasSize(greaterThanOrEqualTo(16))))
                .andExpect(jsonPath("$.requests", hasSize(greaterThanOrEqualTo(6))))
                .andExpect(jsonPath("$.pickups", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.guestInvite.pickupId").isNotEmpty())
                .andExpect(jsonPath("$.vehicleQr.challengeId").isNotEmpty())
                .andReturn();

        JsonNode state = JSON.readTree(stateRes.getResponse().getContentAsString());
        String guestId = state.path("guestInvite").path("pickupId").asText();
        String guestToken = state.path("guestInvite").path("inviteToken").asText();
        mvc.perform(post("/api/v1/pickups/" + guestId + "/invitation-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteToken\":\"" + guestToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeLabel").isNotEmpty());

        MvcResult loginResult = mvc.perform(post("/api/v1/dev/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + DevDummySeedService.DEMO_SELF_ID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.majungId").value("MJ-DEMO001"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

        mvc.perform(get("/api/v1/accounts/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("송승준"));

        mvc.perform(get("/api/v1/accounts/search").session(session).param("query", "민준"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nickname").value("김민준 아이오닉5"));

        mvc.perform(get("/api/v1/connections").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(8))));

        mvc.perform(get("/api/v1/ride-requests").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests", hasSize(greaterThanOrEqualTo(4))));

        String activePickupId = null;
        for (JsonNode p : state.path("pickups")) {
            if ("ACTIVE".equals(p.path("status").asText())) {
                activePickupId = p.path("pickupId").asText();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(activePickupId);
        mvc.perform(post("/api/v1/dev/simulate/vehicle-tick")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pickupId\":\"" + activePickupId + "\",\"action\":\"approach\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").isNumber());

        mvc.perform(post("/api/v1/dev/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", hasSize(greaterThanOrEqualTo(16))));
    }
}
