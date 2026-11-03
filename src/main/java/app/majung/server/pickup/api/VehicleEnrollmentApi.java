package app.majung.server.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "05. 차량 등록 관리 (Vehicle Enrollment)", description = "차량 디스플레이와 스마트폰 계정 간의 기기 등록 및 연동 세션 관리 API")
@RestController
class VehicleEnrollmentApi {
    record Started(
            @Schema(description = "등록 세션 식별자 (UUID)") UUID enrollmentId,
            @Schema(description = "임시 기기 인증 토큰") String deviceToken,
            @Schema(description = "43자리 등록 코드", example = "E3aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789-_abc") String code,
            @Schema(description = "만료 시각") Instant expiresAt) { }

    record Claim(
            @Schema(description = "스캔 또는 입력한 43자리 코드", example = "E3aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789-_abc") @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String code) { }

    record Confirm(
            @Schema(description = "승인할 대상 마중 아이디", example = "majung_pilot") @NotNull String majungId) { }

    record State(
            @Schema(description = "등록 세션 식별자 (UUID)") UUID enrollmentId,
            @Schema(description = "진행 상태 (PENDING, CLAIMED, CONFIRMED, CANCELLED)", example = "PENDING") String status,
            @Schema(description = "요청자 닉네임", example = "김운전") String nickname,
            @Schema(description = "요청자 마중 아이디", example = "driver_kim") String majungId,
            @Schema(description = "만료 시각") Instant expiresAt) { }

    private final VehicleEnrollmentService service;

    VehicleEnrollmentApi(VehicleEnrollmentService service) {
        this.service = service;
    }

    @Operation(summary = "차량 등록 세션 시작", description = "차량 기기에서 새로운 등록 세션을 시작하고 43자리 등록 코드를 발급받습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "세션 시작 성공")
    })
    @PostMapping("/api/v1/vehicles/enrollments")
    Started start() {
        return service.start();
    }

    @Operation(summary = "차량 기기 등록 상태 조회", description = "차량 기기 관점에서 등록 세션 진행 상태 및 요청자 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상태 조회 성공")
    })
    @GetMapping("/api/v1/vehicles/enrollments/{id}")
    State device(@Parameter(description = "등록 세션 식별자 (UUID)") @PathVariable UUID id,
                 @Parameter(description = "임시 기기 토큰") @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.device(id, authorization);
    }

    @Operation(summary = "차량 기기 등록 확정", description = "차량 화면에서 스마트폰으로 요청한 회원의 등록을 최종 확정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록 확정 성공")
    })
    @PostMapping("/api/v1/vehicles/enrollments/{id}/confirm")
    State confirm(@Parameter(description = "등록 세션 식별자 (UUID)") @PathVariable UUID id,
                  @Parameter(description = "임시 기기 토큰") @RequestHeader(value = "Authorization", required = false) String authorization,
                  @Valid @RequestBody Confirm request) {
        return service.confirm(id, authorization, request.majungId());
    }

    @Operation(summary = "차량 기기 등록 취소", description = "차량 기기에서 진행 중인 등록 세션을 취소합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록 취소 성공")
    })
    @PostMapping("/api/v1/vehicles/enrollments/{id}/cancel")
    State cancelDevice(@Parameter(description = "등록 세션 식별자 (UUID)") @PathVariable UUID id,
                       @Parameter(description = "임시 기기 토큰") @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.cancelDevice(id, authorization);
    }

    @Operation(summary = "스마트폰 계정에서 차량 등록 코드 등록", description = "회원 계정에서 차량 화면의 등록 코드를 입력하여 등록을 진행합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록 요청 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @PostMapping("/api/v1/vehicle-links/enrollments/{id}")
    State claim(@Parameter(hidden = true) Authentication authentication,
                @Parameter(description = "등록 세션 식별자 (UUID)") @PathVariable UUID id,
                @Valid @RequestBody Claim request) {
        return service.claim(AccountIdentity.requireMember(authentication), id, request.code());
    }

    @Operation(summary = "스마트폰 계정 관점 차량 등록 상태 확인", description = "회원 스마트폰에서 진행 중인 차량 등록 상태를 확인합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상태 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @GetMapping("/api/v1/vehicle-links/enrollments/{id}")
    State account(@Parameter(hidden = true) Authentication authentication,
                  @Parameter(description = "등록 세션 식별자 (UUID)") @PathVariable UUID id) {
        return service.account(AccountIdentity.requireMember(authentication), id);
    }

    @Operation(summary = "스마트폰 계정 관점 차량 등록 취소", description = "회원 스마트폰에서 차량 등록 진행을 취소합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "등록 취소 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @PostMapping("/api/v1/vehicle-links/enrollments/{id}/cancel")
    State cancelAccount(@Parameter(hidden = true) Authentication authentication,
                        @Parameter(description = "등록 세션 식별자 (UUID)") @PathVariable UUID id) {
        return service.cancelAccount(AccountIdentity.requireMember(authentication), id);
    }
}
