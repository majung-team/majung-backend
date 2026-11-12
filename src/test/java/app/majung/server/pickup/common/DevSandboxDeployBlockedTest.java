package app.majung.server.pickup.common;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:prod-sandbox-blocked;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/initial-schema.sql",
        "majung.deployment=true",
        "majung.admin-key=0123456789abcdef0123456789abcdef",
        "majung.development-tools=false",
        "majung.retention.enabled=true",
        "majung.rate-limit.enabled=true"
})
class DevSandboxDeployBlockedTest {
    @Autowired WebApplicationContext context;

    @Test
    void sandboxRoutesAndBeansDisabledInDeployMode() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/sandbox")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/dev/state")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/dev/login")).andExpect(status().isNotFound());
    }
}
