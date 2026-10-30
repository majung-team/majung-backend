package app.majung.server.pickup;

import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationsHealthApiTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC);

    @Test void returnsOnlyAvailabilityForAFreshSuccessfulHostCheck() throws Exception {
        Path state = directory.resolve("status.json");
        Files.writeString(state, "{\"ok\":true,\"checkedAt\":1000,\"privateHostDetails\":\"fixture-private\"}");
        var mapper = new ObjectMapper();
        var health = new OperationsHealthApi(mapper, state.toString(), clock).health();
        assertEquals("{\"status\":\"UP\"}", mapper.writeValueAsString(health));
    }

    @Test void missingStaleFailedFutureAndMalformedChecksFailClosed() throws Exception {
        Path state = directory.resolve("status.json");
        var api = new OperationsHealthApi(new ObjectMapper(), state.toString(), clock);
        assertEquals(503, assertThrows(ApiException.class, api::health).status);
        for (String invalid : new String[]{
                "{\"ok\":true,\"checkedAt\":820}", "{\"ok\":false,\"checkedAt\":1000}",
                "{\"ok\":true,\"checkedAt\":1006}", "{\"ok\":true}", "{\"ok\":\"true\",\"checkedAt\":1000}",
                "{\"ok\":true,\"checkedAt\":1000.5}", "invalid", " ".repeat(4097)}) {
            Files.writeString(state, invalid);
            ApiException failure = assertThrows(ApiException.class, api::health);
            assertEquals(503, failure.status);
            assertFalse(failure.getMessage().contains(state.toString()));
        }
    }
}
