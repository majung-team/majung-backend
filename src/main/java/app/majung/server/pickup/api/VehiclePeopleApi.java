package app.majung.server.pickup.api;

import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "06. 사람 연결 (Vehicle People)", description = "차량 기기와 탑승자 간의 사전 연결(즐겨찾기 탑승자) 관리 API")
@RestController
@RequestMapping("/api/v1/vehicles/people")
public class VehiclePeopleApi {
    public record Search(
            @Schema(description = "검색된 회원 목록") List<AccountApi.SearchResult> items) { }

    public record Connections(
            @Schema(description = "연결된 탑승자 목록") List<AccountApi.Connection> items) { }

    public record Result(
            @Schema(description = "연결 처리 결과 정보") AccountApi.Connection connection) { }

    public record Target(
            @Schema(description = "대상 회원 식별자 (UUID)") @NotNull UUID memberId,
            @Schema(description = "운전자 마중 아이디", example = "majung_pilot") @NotBlank String driverMajungId) { }

    public record Driver(
            @Schema(description = "운전자 마중 아이디", example = "majung_pilot") @NotBlank String driverMajungId) { }

    private final VehiclePeopleService service;

    public VehiclePeopleApi(VehiclePeopleService service) {
        this.service = service;
    }

    @Operation(summary = "차량에서 연결할 사람 검색", description = "차량 기기에서 마중 아이디 또는 닉네임으로 함께 탈 사람을 검색합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검색 성공")
    })
    @GetMapping("/search")
    Search search(@Parameter(description = "차량 인증 토큰") @RequestHeader(value = "Authorization", required = false) String authorization,
                  @Parameter(description = "검색 키워드 (아이디 또는 닉네임)") @RequestParam String query,
                  @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page) {
        return new Search(service.search(authorization, query, page));
    }

    @Operation(summary = "차량 연결 사람 목록 조회", description = "차량 기기에 연결되었거나 요청 대기 중인 탑승자 목록을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "목록 조회 성공")
    })
    @GetMapping("/connections")
    Connections connections(@Parameter(description = "차량 인증 토큰") @RequestHeader(value = "Authorization", required = false) String authorization,
                            @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page) {
        return new Connections(service.connections(authorization, page));
    }

    @Operation(summary = "차량에서 사람 연결 요청 전송", description = "차량 기기에서 특정 회원에게 함께 타기(자주 타는 사람) 연결을 요청합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "연결 요청 성공")
    })
    @PostMapping("/connections")
    Result request(@Parameter(description = "차량 인증 토큰") @RequestHeader(value = "Authorization", required = false) String authorization,
                   @Valid @RequestBody Target target) {
        return new Result(service.request(authorization, target.memberId(), target.driverMajungId()));
    }

    @Operation(summary = "차량 사람 연결 상태 변경 (수락/거절/삭제)", description = "차량 기기에서 연결 요청을 수락, 거절하거나 기존 연결을 삭제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상태 변경 성공")
    })
    @PostMapping("/connections/{id}/{action}")
    Result change(@Parameter(description = "차량 인증 토큰") @RequestHeader(value = "Authorization", required = false) String authorization,
                  @Parameter(description = "연결 식별자 (UUID)") @PathVariable UUID id,
                  @Parameter(description = "작업 구분 (accept, reject, delete)") @PathVariable String action,
                  @Valid @RequestBody Driver driver) {
        return new Result(service.change(authorization, id, action, driver.driverMajungId()));
    }
}
