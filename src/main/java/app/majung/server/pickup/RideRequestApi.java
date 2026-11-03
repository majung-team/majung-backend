package app.majung.server.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "02. 마중 요청 & 대기열 (Ride Requests)", description = "탑승자의 마중 요청, 운전자 수락/거절, 대기열 순번 및 실시간 트래킹 API")
@RestController
class RideRequestApi {
    record Submit(
            @Schema(description = "마중을 요청할 운전자 회원 식별자 (UUID)") @NotNull UUID driverId,
            @Schema(description = "탑승 희망 장소") @NotNull @Valid PickupApi.Place place,
            @Schema(description = "클라이언트 멱등성 요청 식별자 (UUID)") @NotNull UUID requestId) { }

    record Invite(
            @Schema(description = "초대할 승객과의 연결 식별자 (UUID)") @NotNull UUID connectionId,
            @Schema(description = "픽업 약속 장소") @NotNull @Valid PickupApi.Place place,
            @Schema(description = "클라이언트 멱등성 요청 식별자 (UUID)") @NotNull UUID requestId) { }

    record Contacts(
            @Schema(description = "연결된 대상 목록") List<Contact> items) { }

    record Contact(
            @Schema(description = "연결 식별자 (UUID)") UUID connectionId,
            @Schema(description = "상대방 닉네임", example = "홍길동") String nickname,
            @Schema(description = "상대방 마중 아이디", example = "passenger_lee") String majungId) { }

    record View(
            @Schema(description = "마중 요청 식별자 (UUID)") UUID requestId,
            @Schema(description = "요청 상태 (PENDING, ACCEPTED, REJECTED, CANCELLED, STARTED)", example = "PENDING") String status,
            @Schema(description = "상대방 닉네임", example = "김운전") String nickname,
            @Schema(description = "상대방 마중 아이디", example = "driver_kim") String majungId,
            @Schema(description = "픽업 장소") PickupApi.Place place,
            @Schema(description = "요청 만료 시각") Instant expiresAt,
            @Schema(description = "내 앞 대기 순번 인원 수 (대기열 위치)", example = "1") Integer peopleAhead,
            @Schema(description = "현재 운행 중인 픽업 잔여 시간 (초)", example = "300") Long currentPickupRemainingSeconds,
            @Schema(description = "잔여 시간 데이터 유효 기간 (밀리초)", example = "10000") long currentPickupValidForMillis,
            @Schema(description = "운전자가 먼저 보낸 초대인지 여부", example = "false") boolean driverInvitation) { }

    record ListView(
            @Schema(description = "마중 요청 목록") List<View> requests) { }

    record Started(
            @Schema(description = "생성된 픽업 세션 식별자 (UUID)") UUID pickupId,
            @Schema(description = "운전자 인증 토큰 (Bearer)") String driverToken,
            @Schema(description = "픽업 상세 스냅샷") PickupApi.Snapshot pickup) { }

    record Tracking(
            @Schema(description = "픽업 상세 스냅샷") PickupApi.Snapshot pickup,
            @Schema(description = "차량 실시간 GPS 위치 및 도착 잔여 시간") PickupApi.VehicleState vehicle) { }

    private final RideRequestService service;

    RideRequestApi(RideRequestService service) {
        this.service = service;
    }

    @Operation(summary = "마중 요청 생성", description = "탑승자가 특정 운전자에게 자신이 위치한 곳으로 마중을 요청합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "마중 요청 생성 완료"),
            @ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @PostMapping("/api/v1/ride-requests")
    View submit(@Parameter(hidden = true) Authentication auth, @Valid @RequestBody Submit body) {
        return service.submit(AccountIdentity.requireMember(auth), body);
    }

    @Operation(summary = "내가 보낸 마중 요청 목록 조회", description = "탑승자가 요청한 마중 내역과 현재 대기 순번 및 도착 예상 시간을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "마중 요청 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @GetMapping("/api/v1/ride-requests")
    ListView list(@Parameter(hidden = true) Authentication auth) {
        return new ListView(service.passenger(AccountIdentity.requireMember(auth)));
    }

    @Operation(summary = "마중 요청 취소", description = "탑승자가 대기 중이거나 요청했던 마중을 취소합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "마중 요청 취소 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @PostMapping("/api/v1/ride-requests/{id}/cancel")
    View cancel(@Parameter(hidden = true) Authentication auth,
                @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id) {
        return service.cancel(AccountIdentity.requireMember(auth), id);
    }

    @Operation(summary = "수락된 마중 요청 실시간 트래킹", description = "운전자가 수락한 마중 요청의 픽업 스냅샷과 차량 실시간 위치(위경도, 잔여 시간)를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "트래킹 정보 조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })
    @GetMapping("/api/v1/ride-requests/{id}/tracking")
    Tracking tracking(@Parameter(hidden = true) Authentication auth,
                      @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id) {
        return service.tracking(AccountIdentity.requireMember(auth), id);
    }

    @Operation(summary = "마중 요청 도착 알림 푸시 구독", description = "마중 차량 도착 알림을 받기 위한 웹 푸시 엔드포인트를 등록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "푸시 알림 구독 성공")
    })
    @PutMapping("/api/v1/ride-requests/{id}/arrival-push")
    PickupApi.ArrivalPushState subscribeArrivalPush(@Parameter(hidden = true) Authentication auth,
                                                    @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id,
                                                    @Valid @RequestBody PickupApi.ArrivalPushRequest body) {
        return service.subscribeArrivalPush(AccountIdentity.requireMember(auth), id, body.endpoint());
    }

    @Operation(summary = "마중 요청 도착 알림 푸시 해제", description = "등록된 마중 도착 알림 푸시를 해제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "구독 해제 성공")
    })
    @DeleteMapping("/api/v1/ride-requests/{id}/arrival-push")
    PickupApi.ArrivalPushState unsubscribeArrivalPush(@Parameter(hidden = true) Authentication auth,
                                                      @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id) {
        return service.unsubscribeArrivalPush(AccountIdentity.requireMember(auth), id);
    }

    @Operation(summary = "마중 요청 장소 변경 제안", description = "탑승자가 마중 약속 장소 변경을 제안합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "장소 제안 성공")
    })
    @PutMapping("/api/v1/ride-requests/{id}/place-proposal")
    PickupApi.Snapshot proposePlace(@Parameter(hidden = true) Authentication auth,
                                    @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id,
                                    @Valid @RequestBody PickupApi.CreateRequest body) {
        return service.proposePlace(AccountIdentity.requireMember(auth), id, body.place());
    }

    @Operation(summary = "마중 요청 장소 제안 철회", description = "제안했던 장소 변경을 철회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "제안 철회 성공")
    })
    @DeleteMapping("/api/v1/ride-requests/{id}/place-proposal")
    PickupApi.Snapshot withdrawPlaceProposal(@Parameter(hidden = true) Authentication auth,
                                             @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id) {
        return service.withdrawPlaceProposal(AccountIdentity.requireMember(auth), id);
    }

    @Operation(summary = "탑승 준비 완료 상태 변경 (마중 요청)", description = "탑승자가 본인의 탑승 준비 완료 여부를 변경합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상태 변경 성공")
    })
    @PutMapping("/api/v1/ride-requests/{id}/readiness")
    PickupApi.Snapshot readiness(@Parameter(hidden = true) Authentication auth,
                                 @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id,
                                 @Valid @RequestBody PickupApi.ReadinessRequest body) {
        return service.readiness(AccountIdentity.requireMember(auth), id, body);
    }

    @Operation(summary = "마중 요청 빠른 메시지 전송", description = "운전자에게 실시간 상용구 빠른 메시지를 전송합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "메시지 전송 성공")
    })
    @PostMapping("/api/v1/ride-requests/{id}/quick-message")
    PickupApi.Snapshot quickMessage(@Parameter(hidden = true) Authentication auth,
                                    @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id,
                                    @Valid @RequestBody PickupApi.QuickMessageRequest body) {
        return service.quickMessage(AccountIdentity.requireMember(auth), id, body.message());
    }

    @Operation(summary = "마중 가능한 연결 목록(연락처) 조회", description = "차량/운전자가 마중 초대를 보낼 수 있는 연결된 사람 목록을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "목록 조회 성공")
    })
    @GetMapping("/api/v1/vehicles/connections")
    Contacts contacts(@Parameter(description = "차량/운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                      @Parameter(description = "페이지 번호 (0부터 시작)") @RequestParam(defaultValue = "0") int page) {
        return new Contacts(service.contacts(auth, page));
    }

    @Operation(summary = "탑승자에게 마중 초대 전송", description = "운전자가 연결된 사람에게 '지금 태워줄게요' 마중 초대를 보냅니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "초대 전송 성공")
    })
    @PostMapping("/api/v1/vehicles/invitations")
    View invite(@Parameter(description = "차량/운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                @Valid @RequestBody Invite body) {
        return service.invite(auth, body);
    }

    @Operation(summary = "운전자의 마중 초대 수락", description = "운전자가 보낸 마중 탑승 초대를 수락합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "초대 수락 성공")
    })
    @PostMapping("/api/v1/ride-requests/{id}/accept-invitation")
    View acceptInvitation(@Parameter(hidden = true) Authentication auth,
                          @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id) {
        return service.answerInvitation(AccountIdentity.requireMember(auth), id, true);
    }

    @Operation(summary = "운전자의 마중 초대 거절", description = "운전자가 보낸 마중 탑승 초대를 거절합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "초대 거절 성공")
    })
    @PostMapping("/api/v1/ride-requests/{id}/reject-invitation")
    View rejectInvitation(@Parameter(hidden = true) Authentication auth,
                          @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id) {
        return service.answerInvitation(AccountIdentity.requireMember(auth), id, false);
    }

    @Operation(summary = "보낸 마중 초대 취소", description = "운전자가 보냈던 마중 초대를 취소합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "초대 취소 성공")
    })
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/cancel-invitation")
    View cancelInvitation(@Parameter(description = "차량/운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                          @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id) {
        return service.cancelInvitation(auth, id);
    }

    @Operation(summary = "수신된 마중 요청 대기열 목록 조회", description = "운전자에게 들어온 탑승자들의 마중 요청 대기열 목록을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "대기열 목록 조회 성공")
    })
    @GetMapping("/api/v1/vehicles/ride-requests")
    ListView received(@Parameter(description = "차량/운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth) {
        return new ListView(service.received(auth));
    }

    @Operation(summary = "마중 요청 수락 (대기열 진입)", description = "운전자가 탑승자의 마중 요청을 수락하여 대기열에 등록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 수락 성공")
    })
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/accept")
    View accept(@Parameter(description = "차량/운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id) {
        return service.accept(auth, id);
    }

    @Operation(summary = "마중 요청 거절", description = "운전자가 탑승자의 마중 요청을 거절합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "요청 거절 성공")
    })
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/reject")
    View reject(@Parameter(description = "차량/운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id) {
        return service.reject(auth, id);
    }

    @Operation(summary = "마중 운행 시작 (픽업 세션 전환)", description = "수락된 마중 요청을 정식 픽업 운행 세션으로 전환하고 운행을 시작합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "마중 운행 시작 성공")
    })
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/start")
    Started start(@Parameter(description = "차량/운전자 인증 헤더") @RequestHeader(value = "Authorization", required = false) String auth,
                  @Parameter(description = "마중 요청 식별자 (UUID)") @PathVariable UUID id) {
        return service.start(auth, id);
    }
}
