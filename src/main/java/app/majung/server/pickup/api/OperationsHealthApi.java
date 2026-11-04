package app.majung.server.pickup.api;

import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Publishes availability only. Host details, backup metadata and pickup counts stay on the VM. */
@Tag(name = "10. 시스템 헬스체크 (Health)")
@RestController
public class OperationsHealthApi {
    public record Health(
            @Schema(description = "운영 상태 (UP)", example = "UP") String status) { }
    private final ObjectMapper mapper;
    private final Path state;
    private final Clock clock;

    public OperationsHealthApi(ObjectMapper mapper,
            @Value("${majung.operations-state-file:/var/lib/majung-operations/status.json}") String filename,
            Clock clock) {
        this.mapper = mapper;
        this.state = Path.of(filename);
        this.clock = clock;
    }

    @Operation(summary = "운영 백업 및 호스트 헬스체크", description = "서버 VM 내부의 일일 백업 상태 및 호스트 점검 파일의 최신성을 확인합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "운영 정상 (UP)"),
            @ApiResponse(responseCode = "503", description = "운영 점검 파일 만료 또는 확인 실패")
    })
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
