package app.majung.server.pickup.api;

import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "10. 시스템 헬스체크 (Health)", description = "서비스 가동 상태, DB 연결 상태 및 Blue-Green 무중단 배포 슬롯 점검 API")
@RestController
public class HealthApi {
    public record Health(
            @Schema(description = "서비스 상태 (UP, DOWN)", example = "UP") String status,
            @Schema(description = "배포된 Git 커밋 해시 리비전", example = "4ca8abdfe18b9ce34f2d5e2e4ef1572d424b9588") String revision,
            @Schema(description = "현재 트래픽을 수신 중인 배포 슬롯 (blue, green, standalone)", example = "blue") String slot) { }

    private final JdbcTemplate database;
    private final String revision;

    public HealthApi(JdbcTemplate database) {
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

    @Operation(summary = "시스템 헬스체크 및 배포 버전 확인", description = "데이터베이스 연결 상태, 세션 테이블 정상 여부, 현재 배포된 Git 커밋 해시 및 무중단 배포 슬롯(blue/green)을 확인합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "시스템 정상 가동 중 (UP)"),
            @ApiResponse(responseCode = "503", description = "시스템 점검 중 또는 DB 연결 실패")
    })
    @GetMapping("/api/v1/health")
    Health health() {
        try {
            if (!Integer.valueOf(1).equals(database.queryForObject("SELECT 1", Integer.class))) throw new IllegalStateException();
            if (!Integer.valueOf(0).equals(database.queryForObject(
                    "SELECT COUNT(*) FROM SPRING_SESSION WHERE 1=0", Integer.class))) throw new IllegalStateException();
            String slot = System.getenv("MAJUNG_SLOT");
            if (!"blue".equals(slot) && !"green".equals(slot)) slot = "standalone";
            return new Health("UP", revision, slot);
        } catch (RuntimeException failure) {
            throw new ApiException(503, "SERVICE_UNAVAILABLE", "서버 상태를 확인하고 있어요. 잠시 후 다시 시도해주세요.");
        }
    }
}
