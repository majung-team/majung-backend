package app.majung.server.pickup.api;

import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "04. 차량 등록 (Vehicle Registration)", description = "차량 등록 코드 발급, 기기 등록 및 차량 정보 관리 API")
@RestController
public class VehicleRegistrationApi {
    public record IssuedCode(
            @Schema(description = "등록 코드 식별자 (UUID)") UUID registrationCodeId,
            @Schema(description = "43자리 일회용 차량 등록 코드", example = "E3aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789-_abc") String code,
            @Schema(description = "코드 만료 시각") Instant expiresAt) { }

    public record CodeStatus(
            @Schema(description = "등록 코드 식별자 (UUID)") UUID registrationCodeId,
            @Schema(description = "만료 시각") Instant expiresAt,
            @Schema(description = "소비(등록 완료) 시각") Instant consumedAt,
            @Schema(description = "등록된 차량 식별자 (UUID)") UUID vehicleId) { }

    public record Registration(
            @Schema(description = "43자리 등록 코드", example = "E3aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789-_abc") @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String code,
            @Schema(description = "차량 명칭 (최대 32자)", example = "카니발 12가3456") @NotBlank @Size(max = 32) String displayName) { }

    public record Registered(
            @Schema(description = "발급된 차량 식별자 (UUID)") UUID vehicleId,
            @Schema(description = "차량 전용 영구 인증 토큰 (Bearer)") String vehicleToken) { }

    public record VehicleInfo(
            @Schema(description = "차량 식별자 (UUID)") UUID vehicleId,
            @Schema(description = "고정 차량 호출 코드", example = "VEH-7K2M9A") String vehicleCode,
            @Schema(description = "차량 명칭", example = "아이오닉 5 · 12가 3456") String displayName,
            @Schema(description = "차종", example = "아이오닉 5") String vehicleModel,
            @Schema(description = "차량 번호", example = "12가 3456") String licensePlate,
            @Schema(description = "운전자 구별 힌트", example = "송*준 아빠") String ownerHint,
            @Schema(description = "계정 미연결 시에도 직접 마중 요청 수신 허용 여부", example = "true") boolean directRequestEnabled,
            @Schema(description = "최초 등록 시각") Instant registeredAt,
            @Schema(description = "폐기 시각 (미폐기 시 null)") Instant revokedAt) { }

    public record UpdateProfile(
            @Schema(description = "차종 (최대 32자)", example = "아이오닉 5") @Size(max = 32) String vehicleModel,
            @Schema(description = "차량 번호 (최대 16자)", example = "12가 3456") @Size(max = 16) String licensePlate,
            @Schema(description = "운전자 구별 힌트 (최대 40자)", example = "송*준 아빠") @Size(max = 40) String ownerHint,
            @Schema(description = "직접 마중 요청 수신 여부", example = "true") Boolean directRequestEnabled) { }

    private final DeploymentPolicy deploymentPolicy;
    private final VehicleRegistrationService vehicleRegistrationService;

    public VehicleRegistrationApi(DeploymentPolicy deploymentPolicy, VehicleRegistrationService vehicleRegistrationService) {
        this.deploymentPolicy = deploymentPolicy;
        this.vehicleRegistrationService = vehicleRegistrationService;
    }

    @Operation(summary = "[관리자] 차량 등록 코드 발급", description = "신규 차량 디바이스를 시스템에 등록하기 위한 일회용 43자리 등록 코드를 발급합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 코드 발급 성공"),
            @ApiResponse(responseCode = "401", description = "관리자 권한 필요")
    })
    @PostMapping(value = "/api/v1/admin/vehicle-registration-codes", produces = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedCode issue(@Parameter(description = "관리자 인증 토큰") @RequestHeader(value = "Authorization", required = false) String authorization) {
        deploymentPolicy.requireAdmin(authorization);
        return vehicleRegistrationService.issueCode();
    }

    @Operation(summary = "[관리자] 차량 등록 코드 상태 조회", description = "발급된 차량 등록 코드의 만료 여부 및 소비(등록) 상태를 확인합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "코드 상태 조회 성공"),
            @ApiResponse(responseCode = "401", description = "관리자 권한 필요"),
            @ApiResponse(responseCode = "404", description = "등록 코드를 찾을 수 없음")
    })
    @GetMapping(value = "/api/v1/admin/vehicle-registration-codes/{id}", produces = "application/json")
    CodeStatus codeStatus(@Parameter(description = "등록 코드 식별자 (UUID)") @PathVariable UUID id,
                          @Parameter(description = "관리자 인증 토큰") @RequestHeader(value = "Authorization", required = false) String authorization) {
        deploymentPolicy.requireAdmin(authorization);
        return vehicleRegistrationService.codeStatus(id);
    }

    @Operation(summary = "차량 기기 신규 등록", description = "차량 디바이스에서 관리자 발급 등록 코드를 입력하여 영구 차량 인증 토큰을 발급받습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "차량 등록 완료"),
            @ApiResponse(responseCode = "400", description = "유효하지 않거나 이미 사용된 등록 코드")
    })
    @PostMapping(value = "/api/v1/vehicles/registrations", consumes = "application/json", produces = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    Registered register(@Valid @RequestBody Registration request) {
        return vehicleRegistrationService.register(request);
    }

    @Operation(summary = "현재 차량 기기 정보 조회", description = "현재 차량 기기 인증 토큰으로 등록된 차량의 명칭 및 등록 시각을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "차량 정보 조회 성공"),
            @ApiResponse(responseCode = "401", description = "차량 인증 필요")
    })
    @GetMapping(value = "/api/v1/vehicles/me", produces = "application/json")
    VehicleInfo current(@Parameter(description = "차량 인증 토큰") @RequestHeader(value = "Authorization", required = false) String authorization) {
        return vehicleRegistrationService.current(authorization);
    }

    @Operation(summary = "현재 차량 차종·차량번호·구별 힌트 수정", description = "차량 앱에서 탑승자 확인용 차종, 차량 번호, 구별 힌트 및 상시 수신 여부를 저장합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "차량 정보 저장 성공"),
            @ApiResponse(responseCode = "401", description = "차량 인증 필요")
    })
    @PutMapping(value = "/api/v1/vehicles/me", consumes = "application/json", produces = "application/json")
    VehicleInfo updateProfile(@Parameter(description = "차량 인증 토큰") @RequestHeader(value = "Authorization", required = false) String authorization,
                              @Valid @RequestBody UpdateProfile request) {
        return vehicleRegistrationService.updateProfile(authorization, request);
    }

    @Operation(summary = "[관리자] 차량 기기 등록 폐기", description = "등록된 특정 차량 기기의 접근 권한을 폐기(Revoke)합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "차량 등록 폐기 성공"),
            @ApiResponse(responseCode = "401", description = "관리자 권한 필요"),
            @ApiResponse(responseCode = "404", description = "해당 차량을 찾을 수 없음")
    })
    @PostMapping(value = "/api/v1/admin/vehicles/{id}/revocation", produces = "application/json")
    VehicleInfo revoke(@Parameter(description = "차량 식별자 (UUID)") @PathVariable UUID id,
                       @Parameter(description = "관리자 인증 토큰") @RequestHeader(value = "Authorization", required = false) String authorization) {
        deploymentPolicy.requireAdmin(authorization);
        return vehicleRegistrationService.revoke(id);
    }
}
