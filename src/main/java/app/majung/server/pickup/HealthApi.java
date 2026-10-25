package app.majung.server.pickup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthApi {
    record Health(String status, String revision) { }
    private final JdbcTemplate database;
    private final String revision;
    HealthApi(JdbcTemplate database) {
        this.database = database;
        String value = "local";
        ClassPathResource resource = new ClassPathResource("majung-release.txt");
        if (resource.exists()) {
            try (var input = resource.getInputStream()) {
                value = new String(input.readNBytes(64), StandardCharsets.UTF_8).strip();
                if (!value.matches("[0-9a-f]{40}")) throw new IllegalStateException("Invalid release revision");
            } catch (IOException failure) { throw new IllegalStateException("Cannot read release revision", failure); }
        }
        this.revision = value;
    }
    @GetMapping("/api/v1/health")
    Health health() {
        try {
            if (!Integer.valueOf(1).equals(database.queryForObject("SELECT 1", Integer.class))) throw new IllegalStateException();
            return new Health("UP", revision);
        } catch (RuntimeException failure) {
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "서버 상태를 확인하고 있어요. 잠시 후 다시 시도해주세요.");
        }
    }
}
