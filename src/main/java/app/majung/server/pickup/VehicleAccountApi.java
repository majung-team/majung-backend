package app.majung.server.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "03. 차량-계정 연동 (Vehicle Accounts)", description = "차량 디스플레이 QR 코드를 통한 운전자 계정 연동 및 승인/해제 API")
@RestController
class VehicleAccountApi {
    record Challenge(
            @Schema(description = "연동 챌린지 식별자 (UUID)") UUID challengeId,
            @Schema(description = "43자리 일회용 연동 코드 (QR 내용)", example = "E3aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789-_abc") String code,
            @Schema(description = "코드 만료 시각") Instant expiresAt) { }

    record Claim(
            @Schema(description = "연동 챌린지 식별자 (UUID)") @NotNull UUID challengeId,
            @Schema(description = "스캔한 43자리 코드", example = "E3aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789-_abc") @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String code) { }

    record Confirm(
            @Schema(description = "승인할 연동 챌린지 식별자 (UUID)") @NotNull UUID challengeId) { }

    record DeviceView(
            @Schema(description = "기기 연동 진행 상태 (AWAITING_CLAIM, AWAITING_CONFIRM, LINKED)", example = "AWAITING_CLAIM") String status,
            @Schema(description = "챌린지 식별자 (UUID)") UUID challengeId,
            @Schema(description = "연동 요청 회원 닉네임", example = "김운전") String nickname,
            @Schema(description = "연동 요청 회원 마중 아이디", example = "driver_kim") String majungId,
            @Schema(description = "만료 시각") Instant expiresAt) { }

    record RequestView(
            @Schema(description = "챌린지 식별자 (UUID)") UUID challengeId,
            @Schema(description = "차량 식별자 (UUID)") UUID vehicleId,
            @Schema(description = "차량 명칭", example = "카니발 12가3456") String vehicleName,
            @Schema(description = "진행 상태", example = "AWAITING_CONFIRM") String status,
            @Schema(description = "만료 시각") Instant expiresAt) { }

    record LinkView(
            @Schema(description = "연동된 차량 식별자 (UUID)") UUID vehicleId,
            @Schema(description = "차량 명칭", example = "카니발 12가3456") String vehicleName,
            @Schema(description = "연동 완료 시각") Instant linkedAt) { }

    private final VehicleAccountService service;

    VehicleAccountApi(VehicleAccountService service) {
        this.service = service;
    }

    @Operation(summary = "차량 연동 챌린지(QR) 생성", description = "차량 기기에서 운전자 스마트폰과 연동하기 위한 일회용 챌린지 코드(43자리)를 발급받습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "챌린지 생성 성공"),
            @ApiResponse(responseCode = "401", description = "차량 인증 실패")
    })
    @PostMapping("/api/v1/vehicles/account-link/challenges")
    Challenge issue(@Parameter(description = "차량 인증 헤더") @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.issue(authorization);
    }

    @Operation(summary = "차량 기기 연동 상태 조회", description = "차량 화면에서 현재 계정 연동 요청 및 요청자 닉네임을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상태 조회 성공")
    })
    @GetMapping("/api/v1/vehicles/account-link")
    DeviceView state(@Parameter(description = "차량 인증 헤더") @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.device(authorization);
    }

    @Operation(summary = "차량 기기 연동 최종 승인", description = "차량 디스플레이에서 스마트폰으로 요청한 회원의 연동을 최종 승인합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "연동 승인 성공")
    })
    @PostMapping("/api/v1/vehicles/account-link/confirm")
    DeviceView confirm(@Parameter(description = "차량 인증 헤더") @RequestHeader(value = "Authorization", required = false) String authorization,
                       @Valid @RequestBody Confirm request) {
        return service.confirm(authorization, request.challengeId());
    }

    @Operation(summary = "차량 기기에서 계정 연동 해제", description = "차량 화면에서 현재 연동된 운전자 계정 연결을 해제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "연동 해제 성공")
    })
    @DeleteMapping("/api/v1/vehicles/account-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void detachDevice(@Parameter(description = "차량 인증 헤더") @RequestHeader(value = "Authorization", required = false) String authorization) {
        service.detachDevice(authorization);
    }

    @Operation(summary = "내 연동 차량 목록 조회", description = "로그인한 회원 계정에 연동된 차량 기기 목록을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @GetMapping("/api/v1/vehicle-links")
    List<LinkView> links(@Parameter(hidden = true) Authentication authentication) {
        return service.links(AccountIdentity.requireMember(authentication));
    }

    @Operation(summary = "QR 코드로 차량 연동 요청", description = "스마트폰 카메라로 차량 디스플레이의 QR 코드를 스캔하여 계정 연동을 요청합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "연동 요청 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @PostMapping("/api/v1/vehicle-links/requests")
    RequestView claim(@Parameter(hidden = true) Authentication authentication,
                      @Valid @RequestBody Claim request) {
        return service.claim(AccountIdentity.requireMember(authentication), request);
    }

    @Operation(summary = "차량 연동 요청 진행 상태 조회", description = "회원이 보낸 차량 연동 요청의 승인 대기 상태를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상태 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @GetMapping("/api/v1/vehicle-links/requests/{id}")
    RequestView request(@Parameter(hidden = true) Authentication authentication,
                        @Parameter(description = "챌린지 식별자 (UUID)") @PathVariable UUID id) {
        return service.request(AccountIdentity.requireMember(authentication), id);
    }

    @Operation(summary = "차량 연동 요청 취소", description = "보냈던 차량 연동 요청을 취소합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "요청 취소 완료")
    })
    @PostMapping("/api/v1/vehicle-links/requests/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@Parameter(hidden = true) Authentication authentication,
                @Parameter(description = "챌린지 식별자 (UUID)") @PathVariable UUID id) {
        service.cancel(AccountIdentity.requireMember(authentication), id);
    }

    @Operation(summary = "회원 계정에서 차량 연동 해제", description = "스마트폰에서 등록된 특정 차량과의 연동을 해제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "연동 해제 성공")
    })
    @DeleteMapping("/api/v1/vehicle-links/{vehicleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void detach(@Parameter(hidden = true) Authentication authentication,
                @Parameter(description = "차량 식별자 (UUID)") @PathVariable UUID vehicleId) {
        service.detach(AccountIdentity.requireMember(authentication), vehicleId);
    }
}
