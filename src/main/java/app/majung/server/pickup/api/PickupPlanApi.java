package app.majung.server.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "11. 다중 픽업 계획 & 경로 최적화 (Pickup Plans)", description = "다중 탑승자 픽업 계획 수립, 경로 최적화(TSP) 추천, 개별 ETA 및 경유지 순차 처리 API")
@RestController
@RequestMapping(value = "/api/v1/pickup-plans", produces = "application/json")
class PickupPlanApi {

    record CreatePlanRequest(
            @Schema(description = "픽업 계획 명칭", example = "출근길 판교행 카풀 셔틀") @Size(max = 64) String planName,
            @Schema(description = "운전자 출발지 장소 정보") @Valid @NotNull PickupApi.Place origin,
            @Schema(description = "최종 목적지 장소 정보") @Valid @NotNull PickupApi.Place destination,
            @Schema(description = "계획에 포함할 사전 마중 요청 식별자 목록 (UUIDs, 옵션)") List<UUID> rideRequestIds) {}

    record AddStopRequest(
            @Schema(description = "탑승자 회원 식별자 (UUID)", example = "bbbbbbbb-1111-2222-3333-444444444441") @NotNull UUID passengerId,
            @Schema(description = "탑승자 닉네임", example = "김승객") @NotBlank String passengerNickname,
            @Schema(description = "픽업 희망 장소") @Valid @NotNull PickupApi.Place place,
            @Schema(description = "연결된 마중 요청 식별자 (UUID, 옵션)") UUID rideRequestId,
            @Schema(description = "운전자가 희망하는 방문 순서 (1부터 시작, 미입력 시 자동 배정)", example = "1") Integer preferredOrder) {}

    record OptimizeRequest(
            @Schema(description = "최적화 기준 알고리즘 (MIN_DURATION: 전체 이동 시간 최소화, MIN_DISTANCE: 최단 거리)", example = "MIN_DURATION") String algorithm,
            @Schema(description = "탑승자의 준비 완료(READY) 여부를 최적화 순서에 우선 반영할지 여부", example = "true") Boolean considerReadiness) {}

    record ConfirmRouteRequest(
            @Schema(description = "확정할 경로 추천 리비전 식별자 (UUID)", example = "cccccccc-1111-2222-3333-444444444444") @NotNull UUID revisionId) {}

    record StopView(
            @Schema(description = "경유지 식별자 (UUID)") UUID stopId,
            @Schema(description = "탑승자 식별자 (UUID)") UUID passengerId,
            @Schema(description = "탑승자 닉네임", example = "김승객") String passengerNickname,
            @Schema(description = "픽업 장소 정보") PickupApi.Place place,
            @Schema(description = "연결된 마중 요청 ID") UUID rideRequestId,
            @Schema(description = "방문 순서 번호", example = "1") int order,
            @Schema(description = "경유지 상태 (PENDING, READY, ARRIVED, COMPLETED, CANCELLED)", example = "READY") String status,
            @Schema(description = "예상 도착 시각 (경로 확정 시 계산)") Instant estimatedArrivalAt) {}

    record RouteView(
            @Schema(description = "경로 추천 리비전 식별자 (UUID)") UUID revisionId,
            @Schema(description = "최적화된 경유지 ID 순서 목록") List<UUID> orderedStopIds,
            @Schema(description = "전체 예상 운행 시간 (초)", example = "1920") long totalDurationSeconds,
            @Schema(description = "최적화로 절약된 예상 시간 (초)", example = "360") long timeSavedSeconds,
            @Schema(description = "운전자 최종 확정 여부", example = "true") boolean confirmed,
            @Schema(description = "경로 계산 일시") Instant calculatedAt) {}

    record PlanView(
            @Schema(description = "픽업 계획 식별자 (UUID)") UUID planId,
            @Schema(description = "픽업 계획 명칭", example = "출근길 판교행 카풀 셔틀") String planName,
            @Schema(description = "운전자 회원 식별자 (UUID)") UUID driverId,
            @Schema(description = "계획 진행 상태 (DRAFT, OPTIMIZED, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED)", example = "CONFIRMED") String status,
            @Schema(description = "출발지 장소 정보") PickupApi.Place origin,
            @Schema(description = "최종 목적지 장소 정보") PickupApi.Place destination,
            @Schema(description = "등록된 경유지(탑승자) 목록") List<StopView> stops,
            @Schema(description = "현재 활성/추천된 경로 정보") RouteView activeRoute,
            @Schema(description = "전체 예상 운행 시간 (초)", example = "1920") long totalDurationSeconds,
            @Schema(description = "전체 예상 이동 거리 (미터)", example = "15400") long totalDistanceMeters,
            @Schema(description = "계획 생성 일시") Instant createdAt,
            @Schema(description = "최종 수정 일시") Instant updatedAt) {}

    record PassengerEta(
            @Schema(description = "탑승자 회원 식별자 (UUID)") UUID passengerId,
            @Schema(description = "탑승자 닉네임", example = "김승객") String nickname,
            @Schema(description = "내 픽업 방문 순서", example = "2") int stopOrder,
            @Schema(description = "픽업 장소") PickupApi.Place place,
            @Schema(description = "나에게 도착할 예상 시각") Instant estimatedArrivalAt,
            @Schema(description = "나에게 도착하기까지 남은 시간 (초)", example = "840") long remainingSeconds,
            @Schema(description = "내 앞에 먼저 탑승할 인원 수", example = "1") int peopleAhead,
            @Schema(description = "탑승자 현재 상태 (PENDING, READY, ARRIVED, COMPLETED)", example = "READY") String status) {}

    record EtaOverview(
            @Schema(description = "픽업 계획 식별자 (UUID)") UUID planId,
            @Schema(description = "계획 진행 상태", example = "CONFIRMED") String planStatus,
            @Schema(description = "탑승자별 개별 ETA 목록") List<PassengerEta> passengerEtas,
            @Schema(description = "ETA 갱신 시각") Instant updatedAt) {}

    record StopCompletionResult(
            @Schema(description = "픽업 계획 식별자 (UUID)") UUID planId,
            @Schema(description = "방금 탑승 완료된 경유지 식별자 (UUID)") UUID completedStopId,
            @Schema(description = "다음 픽업할 경유지 식별자 (남은 경유지가 없으면 null)") UUID nextStopId,
            @Schema(description = "다음 픽업 탑승자 닉네임", example = "이탑승") String nextStopPassengerNickname,
            @Schema(description = "다음 픽업 장소") PickupApi.Place nextStopPlace,
            @Schema(description = "남은 미탑승 경유지 수", example = "1") long remainingStopsCount) {}

    private final PickupPlanService planService;

    PickupPlanApi(PickupPlanService planService) {
        this.planService = planService;
    }

    private static StopView toStopView(PickupPlanService.StopData s) {
        return new StopView(s.stopId(), s.passengerId(), s.passengerNickname(),
                s.place(), s.rideRequestId(), s.order(), s.status(), s.estimatedArrivalAt());
    }

    private static RouteView toRouteView(PickupPlanService.RouteData r) {
        if (r == null) return null;
        return new RouteView(r.revisionId(), r.orderedStopIds(), r.totalDurationSeconds(),
                r.timeSavedSeconds(), r.confirmed(), r.calculatedAt());
    }

    private static PlanView toPlanView(PickupPlanService.PlanData p) {
        return new PlanView(p.planId(), p.planName(), p.driverId(), p.status(),
                p.origin(), p.destination(),
                p.stops().stream().map(PickupPlanApi::toStopView).toList(),
                toRouteView(p.activeRoute()), p.totalDurationSeconds(), p.totalDistanceMeters(),
                p.createdAt(), p.updatedAt());
    }

    @Operation(summary = "다중 픽업 계획 생성", description = "운전자가 출발지와 최종 목적지를 지정하고 여러 탑승자를 연결하기 위한 새로운 다중 픽업 계획(Pickup Plan)을 생성합니다. (차량 시동 Off 상태에서도 스마트폰으로 사전 생성 가능)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "다중 픽업 계획 생성 성공"),
            @ApiResponse(responseCode = "400", description = "장소 정보 입력 오류")
    })
    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    PlanView createPlan(@Valid @RequestBody CreatePlanRequest request,
                        @Parameter(description = "운전자 인증 토큰 (옵션)") @RequestHeader(value = "Authorization", required = false) String authorization) {
        return toPlanView(planService.createPlan(request.planName(), request.origin(), request.destination(),
                request.rideRequestIds(), authorization));
    }

    @Operation(summary = "다중 픽업 계획 상세 조회", description = "특정 픽업 계획의 상세 정보(출발지, 목적지, 경유지 탑승자 목록, 최적화 경로, 진행 상태)를 조회합니다. (차량 시동 On 시 자동 동기화용으로도 활용)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "계획 상세 조회 성공"),
            @ApiResponse(responseCode = "404", description = "픽업 계획을 찾을 수 없음")
    })
    @GetMapping("/{id}")
    PlanView getPlan(@Parameter(description = "픽업 계획 고유 식별자 (UUID)", example = "11111111-2222-3333-4444-555555555555") @PathVariable UUID id) {
        return toPlanView(planService.getPlan(id));
    }

    @Operation(summary = "픽업 계획에 경유지(탑승자) 추가", description = "생성된 픽업 계획에 새로운 탑승자와 픽업 장소를 경유지로 추가합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "경유지 추가 성공"),
            @ApiResponse(responseCode = "404", description = "픽업 계획을 찾을 수 없음")
    })
    @PostMapping(value = "/{id}/stops", consumes = "application/json")
    PlanView addStop(@Parameter(description = "픽업 계획 식별자 (UUID)") @PathVariable UUID id,
                     @Valid @RequestBody AddStopRequest request) {
        return toPlanView(planService.addStop(id, request.passengerId(), request.passengerNickname(),
                request.place(), request.rideRequestId(), request.preferredOrder()));
    }

    @Operation(summary = "픽업 계획에서 경유지(탑승자) 제외/삭제", description = "탑승자 취소 또는 경로 변경 시 특정 경유지를 픽업 계획에서 제외합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "경유지 삭제 성공"),
            @ApiResponse(responseCode = "404", description = "픽업 계획을 찾을 수 없음")
    })
    @DeleteMapping("/{id}/stops/{stopId}")
    PlanView removeStop(@Parameter(description = "픽업 계획 식별자 (UUID)") @PathVariable UUID id,
                        @Parameter(description = "삭제할 경유지 식별자 (UUID)") @PathVariable UUID stopId) {
        return toPlanView(planService.removeStop(id, stopId));
    }

    @Operation(summary = "이동 시간 최소화 최적 경로 추천 (TSP 연산)", description = "출발지, 등록된 경유지들, 최종 목적지 간의 이동 시간을 계산하여 전체 운행 시간이 가장 짧은 최적 방문 순서를 추천합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "최적 경로 추천 계산 성공"),
            @ApiResponse(responseCode = "400", description = "경유지가 등록되지 않음"),
            @ApiResponse(responseCode = "404", description = "픽업 계획을 찾을 수 없음")
    })
    @PostMapping(value = "/{id}/optimize", consumes = "application/json")
    RouteView optimizeRoute(@Parameter(description = "픽업 계획 식별자 (UUID)") @PathVariable UUID id,
                            @RequestBody(required = false) OptimizeRequest request) {
        String algo = request != null && request.algorithm() != null ? request.algorithm() : "MIN_DURATION";
        boolean readiness = request != null && request.considerReadiness() != null && request.considerReadiness();
        return toRouteView(planService.optimizeRoute(id, algo, readiness));
    }

    @Operation(summary = "추천 경로 최종 확정 (운전자 승인)", description = "서버가 추천한 최적 픽업 경로를 운전자가 확인하고 최종 확정(CONFIRMED)합니다. (차량 시동 On 시 내비게이션 길 안내의 기준 경로가 됨)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "경로 최종 확정 성공"),
            @ApiResponse(responseCode = "400", description = "사전 최적화되지 않은 경로"),
            @ApiResponse(responseCode = "404", description = "픽업 계획을 찾을 수 없음")
    })
    @PostMapping(value = "/{id}/confirm", consumes = "application/json")
    PlanView confirmRoute(@Parameter(description = "픽업 계획 식별자 (UUID)") @PathVariable UUID id,
                          @Valid @RequestBody ConfirmRouteRequest request) {
        return toPlanView(planService.confirmRoute(id, request.revisionId()));
    }

    @Operation(summary = "탑승자별 개별 ETA 및 대기 순번 조회", description = "확정된 픽업 계획을 바탕으로 각 탑승자에게 맞춤형 도착 예정 시간(ETA), 남은 대기 시간(초), 내 앞 탑승자 수를 제공합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "ETA 및 대기 정보 조회 성공"),
            @ApiResponse(responseCode = "404", description = "픽업 계획을 찾을 수 없음")
    })
    @GetMapping("/{id}/eta")
    EtaOverview getEtas(@Parameter(description = "픽업 계획 식별자 (UUID)") @PathVariable UUID id,
                        @Parameter(description = "특정 탑승자 필터링 (UUID, 옵션)") @RequestParam(required = false) UUID passengerId) {
        PickupPlanService.PlanData plan = planService.getPlan(id);
        Instant now = Instant.now();

        List<PassengerEta> etas = plan.stops().stream()
                .filter(s -> passengerId == null || s.passengerId().equals(passengerId))
                .map(s -> {
                    long remaining = s.estimatedArrivalAt() != null
                            ? Math.max(0, s.estimatedArrivalAt().getEpochSecond() - now.getEpochSecond())
                            : 300L * s.order();
                    int ahead = Math.max(0, s.order() - 1);
                    return new PassengerEta(s.passengerId(), s.passengerNickname(), s.order(),
                            s.place(), s.estimatedArrivalAt() != null ? s.estimatedArrivalAt() : now.plusSeconds(remaining),
                            remaining, ahead, s.status());
                }).toList();

        return new EtaOverview(plan.planId(), plan.status(), etas, now);
    }

    @Operation(summary = "특정 경유지 탑승 완료 처리 (다음 픽업 자동 전환)", description = "운전자가 특정 탑승자의 탑승을 완료 처리하면, 상태를 COMPLETED로 전환하고 자동으로 다음 경유지를 안내 대상으로 지정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "탑승 완료 처리 및 다음 경유지 반환 성공"),
            @ApiResponse(responseCode = "404", description = "경유지 또는 계획을 찾을 수 없음")
    })
    @PostMapping("/{id}/stops/{stopId}/complete")
    StopCompletionResult completeStop(@Parameter(description = "픽업 계획 식별자 (UUID)") @PathVariable UUID id,
                                      @Parameter(description = "탑승 완료할 경유지 식별자 (UUID)") @PathVariable UUID stopId) {
        PickupPlanService.PlanData updated = planService.completeStop(id, stopId);

        PickupPlanService.StopData nextStop = updated.stops().stream()
                .filter(s -> !"COMPLETED".equals(s.status()) && !"CANCELLED".equals(s.status()))
                .findFirst().orElse(null);

        long remaining = updated.stops().stream().filter(s -> !"COMPLETED".equals(s.status())).count();

        return new StopCompletionResult(
                updated.planId(), stopId,
                nextStop != null ? nextStop.stopId() : null,
                nextStop != null ? nextStop.passengerNickname() : null,
                nextStop != null ? nextStop.place() : null,
                remaining);
    }

    @Operation(summary = "픽업 계획 전체 취소", description = "운행 사정이나 일정 취소로 인해 다중 픽업 계획 전체를 취소(CANCELLED) 처리합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "계획 취소 완료"),
            @ApiResponse(responseCode = "404", description = "픽업 계획을 찾을 수 없음")
    })
    @PostMapping("/{id}/cancel")
    PlanView cancelPlan(@Parameter(description = "픽업 계획 식별자 (UUID)") @PathVariable UUID id) {
        return toPlanView(planService.cancelPlan(id));
    }
}
