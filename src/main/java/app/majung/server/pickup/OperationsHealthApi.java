package app.majung.server.pickup;

import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes availability only. Host details, backup metadata and pickup counts stay on the VM. */
@RestController
class OperationsHealthApi {
    record Health(String status) { }
    private final ObjectMapper mapper;
    private final Path state;
    private final Clock clock;

    OperationsHealthApi(ObjectMapper mapper,
            @Value("${majung.operations-state-file:/var/lib/majung-operations/status.json}") String filename,
            Clock clock) {
        this.mapper = mapper;
        this.state = Path.of(filename);
        this.clock = clock;
    }

    @GetMapping("/api/v1/health/operations")
    Health health() {
        try (var input = Files.newInputStream(state)) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) throw new IllegalStateException();
            var result = mapper.readTree(bytes);
            var timestamp = result.get("checkedAt");
            long now = clock.instant().getEpochSecond();
            if (timestamp == null || !timestamp.isIntegralNumber() || !timestamp.canConvertToLong()
                    || now - timestamp.longValue() >= 180 || timestamp.longValue() > now + 5
                    || !result.path("ok").isBoolean() || !result.path("ok").booleanValue()) {
                throw new IllegalStateException();
            }
            return new Health("UP");
        } catch (Exception failure) {
            throw new ApiException(503, "OPERATIONS_UNAVAILABLE", "서버 상태를 확인하고 있어요. 잠시 후 다시 시도해주세요.");
        }
    }
}
