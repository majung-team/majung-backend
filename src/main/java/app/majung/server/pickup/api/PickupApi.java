package app.majung.server.pickup.api;

import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "01. 픽업 (Pickups)", description = "픽업 세션 생성, 초대 참여, 상태/준비 변경, 차량 위치 업데이트 등 실시간 픽업 핵심 API")
@RestController
@RequestMapping(value = "/api/v1/pickups", produces = "application/json")
public class PickupApi {
    public record Place(
            @Schema(description = "장소 명칭/라벨", example = "강남역 2번 출구") @NotBlank @Size(max = 120) String label,
            @Schema(description = "위도 (Latitude)", example = "37.4979") @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @Schema(description = "경도 (Longitude)", example = "127.0276") @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) { }

    public record CreateRequest(
            @Schema(description = "픽업 약속 장소") @Valid @NotNull Place place) { }

    public record JoinRequest(
            @Schema(description = "43자리 URL-safe Base64 초대 토큰", example = "E3aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789-_abc") @NotBlank @Size(min = 43, max = 43) String inviteToken,
            @Schema(description = "탑승자 표시 닉네임 (최대 12자)", example = "승객1") @Size(max = 12) String displayName) { }

    public record ReadinessRequest(
            @Schema(description = "탑승 준비 상태 (NOT_READY, READY, COMPLETED)", example = "READY") @NotNull Pickup.Readiness readiness,
            @Schema(description = "상태 전송 시퀀스 번호", example = "1") @Positive long sequence) { }

    public record ReadinessReceipt(
            @Schema(description = "확인할 참여자(승객) 식별자 (UUID)") @NotNull UUID participantId,
            @Schema(description = "확인한 시퀀스 번호", example = "1") @Positive long sequence) { }

    public record PlaceVersionRequest(
            @Schema(description = "확인할 장소 버전 번호", example = "1") @NotNull @Positive Long placeVersion) { }

    public record VehicleInvalidation(
            @Schema(description = "시퀀스 번호", example = "1") @Positive long sequence,
            @Schema(description = "장소 버전 번호", example = "1") @Positive Long placeVersion) {
        VehicleInvalidation(long sequence) { this(sequence, null); }
    }

    public record PlaceConfirmation(
            @Schema(description = "수락할 장소 제안 식별자 (UUID)") @NotNull UUID proposalId,
            @Schema(description = "예상 장소 버전", example = "1") @Positive long expectedPlaceVersion,
            @Schema(description = "확정할 장소 정보") @Valid @NotNull Place place,
            @Schema(description = "예상 이동 소요 시간 (초)", example = "300") @NotNull @PositiveOrZero Long durationSeconds,
            @Schema(description = "측정/전송 시각") @NotNull Instant sourceTime) { }

    public record VehicleRequest(
            @Schema(description = "차량 현재 위도", example = "37.4982") @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @Schema(description = "차량 현재 경도", example = "127.0280") @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Schema(description = "도착지까지 예상 소요 시간 (초)", example = "180") @NotNull @PositiveOrZero Long durationSeconds,
            @Schema(description = "위치 측정 시각") @NotNull Instant sourceTime,
            @Schema(description = "위치 갱신 시퀀스", example = "15") @Positive long sequence,
            @Schema(description = "장소 버전", example = "1") @Positive Long placeVersion) {
        public VehicleRequest(Double latitude, Double longitude, Long durationSeconds, Instant sourceTime, long sequence) {
            this(latitude, longitude, durationSeconds, sourceTime, sequence, null);
        }
    }

    public record Created(
            @Schema(description = "생성된 픽업 세션 식별자 (UUID)") UUID pickupId,
            @Schema(description = "운전자 인증 토큰 (Bearer)") String driverToken,
            @Schema(description = "탑승자 초대용 토큰") String inviteToken,
            @Schema(description = "초대 토큰 만료 시각") Instant inviteExpiresAt,
            @Schema(description = "픽업 세션 전체 만료 시각") Instant expiresAt) { }

    public record Invitation(
            @Schema(description = "새로 발급된 초대 토큰") String inviteToken,
            @Schema(description = "초대 토큰 만료 시각") Instant expiresAt) { }

    public record ArrivalPushRequest(
            @Schema(description = "브라우저/디바이스 웹 푸시 엔드포인트 URL", example = "https://fcm.googleapis.com/fcm/send/...") @NotBlank @Size(max = 1024) String endpoint) { }

    public record ArrivalPushState(
            @Schema(description = "웹 푸시 구독 여부", example = "true") boolean subscribed) { }

    public record Joined(
            @Schema(description = "참여자(승객) 식별자 (UUID)") UUID participantId,
            @Schema(description = "승객 전용 인증 토큰 (Bearer)") String participantToken,
            @Schema(description = "참여 상태", example = "JOINED") String status) { }

    public record Participant(
            @Schema(description = "참여자 식별자 (UUID)") UUID participantId,
            @Schema(description = "표시 닉네임", example = "승객1") String displayName,
            @Schema(description = "탑승 준비 상태 (NOT_READY, READY, COMPLETED)", example = "READY") Pickup.Readiness readiness,
            @Schema(description = "준비 상태 시퀀스", example = "1") long readinessSequence,
            @Schema(description = "수신 확인된 시퀀스", example = "1") long readinessReceivedSequence) { }

    public record PlaceProposal(
            @Schema(description = "제안된 장소") Place place,
            @Schema(description = "제안자 (DRIVER 또는 PARTICIPANT)", example = "DRIVER") Pickup.Proposer proposedBy,
            @Schema(description = "제안 시각") Instant proposedAt,
            @Schema(description = "제안 식별자 (UUID)") UUID proposalId) { }

    public record QuickMessage(
            @Schema(description = "메시지 본문", example = "곧 도착해요!") String text,
            @Schema(description = "발송 시각") Instant sentAt) { }

    public record QuickMessageRequest(
            @Schema(description = "전송할 빠른 메시지 문구 (최대 50자)", example = "지금 정류장 앞이에요!") @NotBlank @Size(max = 50) String message) { }

    public record Snapshot(
            @Schema(description = "픽업 식별자 (UUID)") UUID pickupId,
            @Schema(description = "픽업 진행 상태 (CREATED, MATCHED, ARRIVED, COMPLETED, CANCELLED)", example = "MATCHED") String status,
            @Schema(description = "확정된 픽업 장소") Place place,
            @Schema(description = "제안 대기 중인 장소") PlaceProposal placeProposal,
            @Schema(description = "현재 참여 중인 승객 정보") Participant participant,
            @Schema(description = "초대 만료 시각") Instant inviteExpiresAt,
            @Schema(description = "세션 만료 시각") Instant expiresAt,
            @Schema(description = "운행 종료 시각") Instant endedAt,
            @Schema(description = "장소 버전", example = "1") long placeVersion,
            @Schema(description = "최근 수신된 빠른 메시지") QuickMessage quickMessage,
            @Schema(description = "배정된 차량 명칭", example = "카니발 12가3456") String vehicleName) { }

    public record VehicleState(
            @Schema(description = "위치 데이터 신선도 (FRESH, STALE)", example = "FRESH") String freshness,
            @Schema(description = "차량 현재 위도", example = "37.4982") Double latitude,
            @Schema(description = "차량 현재 경도", example = "127.0280") Double longitude,
            @Schema(description = "도착지까지 예상 소요 시간 (초)", example = "120") Long durationSeconds,
            @Schema(description = "위치 측정 시각") Instant sourceTime,
            @Schema(description = "서버 수신 시각") Instant receivedAt,
            @Schema(description = "위치 전송 시퀀스", example = "42") long sequence,
            @Schema(description = "데이터 유효 시간 (밀리초)", example = "15000") long validForMillis,
            @Schema(description = "데이터 경과 시간 (밀리초)", example = "1200") Long ageMillis,
            @Schema(description = "장소 버전", example = "1") long placeVersion) { }

    private final PickupService pickupService;
    private final DeploymentPolicy deploymentPolicy;

    public PickupApi(PickupService pickupService, DeploymentPolicy deploymentPolicy) {
        this.pickupService = pickupService;
        this.deploymentPolicy = deploymentPolicy;
    }

    @Operation(summary = "픽업 세션 생성", description = "새로운 마중(픽업) 세션을 생성하고 운전자 권한 토큰 및 승객 초대 링크 토큰을 발급받습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "픽업 세션 생성 성공"),
            @ApiResponse(responseCode = "400", description = "장소 정보 입력 오류")
    })
    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@Valid @RequestBody CreateRequest request,
                   @Parameter(description = "차량/운전자 인증 토큰 (옵션)") @RequestHeader(value = "Authorization", required = false) String authorization) {
        return pickupService.createPickup(request.place(), authorization);
    }

    @Operation(summary = "초대 링크로 픽업 참여", description = "승객이 초대 토큰을 사용하여 픽업 세션에 참여하고 승객 전용 인증 토큰을 발급받습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "픽업 세션 참여 완료"),
            @ApiResponse(responseCode = "400", description = "유효하지 않거나 만료된 초대 토큰"),
            @ApiResponse(responseCode = "404", description = "픽업 세션을 찾을 수 없음")
    })
    @PostMapping(value = "/{id}/participants", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    Joined join(@Parameter(description = "픽업 고유 식별자 (UUID)") @PathVariable UUID id,
                @Valid @RequestBody JoinRequest request) {
        return pickupService.joinPickup(id, request);
    }

    @Operation(summary = "초대 토큰 재발급", description = "기존 초대 토큰을 즉시 만료시키고 새로운 초대 토큰을 발급합니다. (운전자 권한 필요)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "새 초대 토큰 발급 성공"),
            @ApiResponse(responseCode = "401", description = "운전자 인증 필요")
    })
    @PostMapping("/{id}/invitations")
    Invitation rotate(@Parameter(description = "픽업 고유 식별자 (UUID)") @PathVariable UUID id,
                      @Parameter(description = "운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.rotateInvitation(id, auth);
    }

    @Operation(summary = "도착 알림 웹 푸시 구독", description = "차량 도착 시 실시간 웹 푸시 알림을 수신하기 위해 엔드포인트를 등록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "푸시 알림 구독 성공")
    })
    @PutMapping(value = "/{id}/arrival-push", consumes = "application/json")
    ArrivalPushState subscribeArrivalPush(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                                          @Parameter(description = "인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                                          @Valid @RequestBody ArrivalPushRequest request) {
        return pickupService.subscribeArrivalPush(id, auth, request.endpoint());
    }

    @Operation(summary = "도착 알림 웹 푸시 구독 해제", description = "등록된 차량 도착 웹 푸시 알림을 해제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "구독 해제 성공")
    })
    @DeleteMapping("/{id}/arrival-push")
    ArrivalPushState unsubscribeArrivalPush(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                                            @Parameter(description = "인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.unsubscribeArrivalPush(id, auth);
    }

    @Operation(summary = "참여 승객 연결 해제 (승객 퇴장)", description = "현재 탑승 중인 승객을 퇴장시키고 초대 링크를 다시 오픈합니다. (운전자 전용)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "승객 연결 해제 완료"),
            @ApiResponse(responseCode = "401", description = "운전자 권한 필요")
    })
    @DeleteMapping("/{id}/participant")
    Invitation disconnect(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                          @Parameter(description = "운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.disconnectParticipant(id, auth);
    }

    @Operation(summary = "승객 탑승 승인", description = "대기열에서 특정 승객의 탑승을 운전자가 승인합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "탑승 승인 성공")
    })
    @PostMapping("/{id}/participants/{participantId}/approval")
    Snapshot approve(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                     @Parameter(description = "승객 식별자 (UUID)") @PathVariable UUID participantId,
                     @Parameter(description = "운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.approveParticipant(id, participantId, auth);
    }

    @Operation(summary = "픽업 상세 스냅샷 조회", description = "픽업 세션의 전체 진행 상태, 약속 장소, 승객 준비 상태 및 차량 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "스냅샷 조회 성공"),
            @ApiResponse(responseCode = "404", description = "픽업 세션을 찾을 수 없음")
    })
    @GetMapping("/{id}")
    Snapshot get(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                 @Parameter(description = "인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.getPickup(id, auth);
    }

    @Operation(summary = "승객 탑승 준비 상태 변경", description = "승객이 본인의 준비 상태(NOT_READY, READY, COMPLETED)를 갱신합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "준비 상태 변경 성공")
    })
    @PutMapping(value = "/{id}/readiness", consumes = "application/json")
    Snapshot readiness(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                       @Parameter(description = "인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                       @Valid @RequestBody ReadinessRequest request) {
        return pickupService.updateReadiness(id, auth, request);
    }

    @Operation(summary = "픽업 장소 제안 확정", description = "상대방이 제안한 픽업 장소 변경 요청을 최종 확정(수락)합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "장소 확정 성공")
    })
    @PutMapping(value = "/{id}/place-confirmation", consumes = "application/json")
    Snapshot confirmPlace(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                          @Parameter(description = "인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                          @Valid @RequestBody PlaceConfirmation request) {
        return pickupService.confirmPlace(id, auth, request);
    }

    @Operation(summary = "탑승 준비 상태 수신 확인", description = "승객의 준비 완료 신호를 운전자가 수신했음을 기록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수신 확인 완료")
    })
    @PutMapping(value = "/{id}/readiness-receipt", consumes = "application/json")
    Snapshot receiveReadiness(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                              @Parameter(description = "운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                              @Valid @RequestBody ReadinessReceipt request) {
        return pickupService.receiveReadiness(id, auth, request);
    }

    @Operation(summary = "차량 실시간 GPS 위치 전송", description = "운전자 모바일 앱에서 차량의 최신 위경도 좌표 및 도착 예정 남은 시간을 전송합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "위치 갱신 성공")
    })
    @PutMapping(value = "/{id}/vehicle-state", consumes = "application/json")
    VehicleState vehicle(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                         @Parameter(description = "운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                         @Valid @RequestBody VehicleRequest request) {
        return pickupService.updateVehicle(id, auth, request);
    }

    @Operation(summary = "차량 위치 정보 무효화", description = "GPS 수신 불량 또는 운행 일시 중지 시 차량 위치 정보를 무효화합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "위치 무효화 성공")
    })
    @DeleteMapping(value = "/{id}/vehicle-state", consumes = "application/json")
    VehicleState invalidateVehicle(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                                   @Parameter(description = "운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                                   @Valid @RequestBody VehicleInvalidation request) {
        return pickupService.invalidateVehicle(id, auth, request.sequence(), request.placeVersion());
    }

    @Operation(summary = "차량 최신 위치 및 상태 조회", description = "해당 픽업에 배정된 차량의 최신 GPS 좌표와 남은 시간(초)을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "차량 위치 조회 성공")
    })
    @GetMapping("/{id}/vehicle-state")
    VehicleState vehicle(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                         @Parameter(description = "인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.getVehicle(id, auth);
    }

    @Operation(summary = "픽업 장소 변경 제안", description = "새로운 픽업 약속 장소를 상대방에게 제안합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "장소 제안 성공")
    })
    @PutMapping(value = "/{id}/place-proposal", consumes = "application/json")
    Snapshot proposePlace(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                          @Parameter(description = "인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                          @Valid @RequestBody CreateRequest request) {
        return pickupService.proposePlace(id, auth, request.place());
    }

    @Operation(summary = "픽업 장소 제안 철회", description = "내가 제안했던 장소 변경 요청을 철회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "장소 제안 철회 성공")
    })
    @DeleteMapping("/{id}/place-proposal")
    Snapshot withdrawPlaceProposal(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                                   @Parameter(description = "인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.withdrawPlaceProposal(id, auth);
    }

    @Operation(summary = "차량 도착 완료 처리", description = "운전자가 픽업 장소에 도착했음을 기록하고 승객에게 도착 알림을 발송합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "도착 처리 완료")
    })
    @PostMapping("/{id}/arrival")
    Snapshot arrive(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                    @Parameter(description = "운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                    @Valid @RequestBody(required = false) PlaceVersionRequest request) {
        return pickupService.arrivePickup(id, auth, request == null ? null : request.placeVersion());
    }

    @Operation(summary = "픽업 운행 정상 종료", description = "승객 탑승 및 목적지 이동이 완료되어 픽업 세션을 종료(COMPLETED)합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "운행 완료 처리 성공")
    })
    @PostMapping("/{id}/completion")
    Snapshot complete(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                      @Parameter(description = "운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.endPickup(id, auth, Pickup.Status.COMPLETED);
    }

    @Operation(summary = "픽업 세션 취소", description = "운전자 또는 승객에 의해 픽업 세션을 취소(CANCELLED)합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "취소 처리 완료")
    })
    @PostMapping("/{id}/cancellation")
    Snapshot cancel(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                    @Parameter(description = "인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.endPickup(id, auth, Pickup.Status.CANCELLED);
    }

    @Operation(summary = "빠른 메시지 전송", description = "운전자와 승객 간 실시간 상용구(예: '지금 정류장 앞이에요')를 전송합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "메시지 전송 성공")
    })
    @PostMapping(value = "/{id}/quick-message", consumes = "application/json")
    Snapshot quickMessage(@Parameter(description = "픽업 식별자 (UUID)") @PathVariable UUID id,
                          @Parameter(description = "인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                          @Valid @RequestBody QuickMessageRequest request) {
        return pickupService.sendQuickMessage(id, auth, request.message());
    }
}
