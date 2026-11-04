package app.majung.server.pickup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PickupPlanApiTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T06:00:00Z"), ZoneId.of("UTC"));
    private final PickupPlanService service = new PickupPlanService(clock);
    private final PickupPlanApi api = new PickupPlanApi(service);

    @Test
    @DisplayName("사전 시딩된 샘플 다중 픽업 계획 조회가 정상 작동해야 한다")
    void getSeededPlan() {
        UUID sampleId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        PickupPlanApi.PlanView plan = api.getPlan(sampleId);

        assertNotNull(plan);
        assertEquals("출근길 판교행 셔틀", plan.planName());
        assertEquals("CONFIRMED", plan.status());
        assertEquals(3, plan.stops().size());
        assertNotNull(plan.activeRoute());
    }

    @Test
    @DisplayName("신규 다중 픽업 계획 생성 및 경유지 추가가 정상 동작해야 한다")
    void createPlanAndAddStop() {
        PickupApi.Place origin = new PickupApi.Place("출발지", 37.5000, 127.0300);
        PickupApi.Place dest = new PickupApi.Place("목적지", 37.4000, 127.1000);

        PickupPlanApi.CreatePlanRequest request = new PickupPlanApi.CreatePlanRequest(
                "테스트 픽업 계획", origin, dest, null);

        PickupPlanApi.PlanView created = api.createPlan(request, null);
        assertNotNull(created.planId());
        assertEquals("DRAFT", created.status());
        assertEquals(0, created.stops().size());

        // Add stop
        PickupPlanApi.AddStopRequest addStop = new PickupPlanApi.AddStopRequest(
                UUID.randomUUID(), "탑승자A",
                new PickupApi.Place("중간 경유지 A", 37.4500, 127.0600),
                null, 1);

        PickupPlanApi.PlanView withStop = api.addStop(created.planId(), addStop);
        assertEquals(1, withStop.stops().size());
        assertEquals("탑승자A", withStop.stops().get(0).passengerNickname());
    }

    @Test
    @DisplayName("경로 최적화(TSP) 알고리즘이 순열을 계산하고 절약 시간을 반환해야 한다")
    void optimizeAndConfirmRoute() {
        UUID sampleId = UUID.fromString("11111111-2222-3333-4444-555555555555");

        PickupPlanApi.OptimizeRequest optReq = new PickupPlanApi.OptimizeRequest("MIN_DURATION", true);
        PickupPlanApi.RouteView route = api.optimizeRoute(sampleId, optReq);

        assertNotNull(route);
        assertEquals(3, route.orderedStopIds().size());
        assertTrue(route.totalDurationSeconds() > 0);
        assertTrue(route.timeSavedSeconds() > 0);

        // Confirm
        PickupPlanApi.PlanView confirmed = api.confirmRoute(sampleId,
                new PickupPlanApi.ConfirmRouteRequest(route.revisionId()));
        assertEquals("CONFIRMED", confirmed.status());
        assertTrue(confirmed.activeRoute().confirmed());
    }

    @Test
    @DisplayName("탑승자별 개별 ETA 조회가 정상 작동해야 한다")
    void getPassengerEtas() {
        UUID sampleId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        PickupPlanApi.EtaOverview etas = api.getEtas(sampleId, null);

        assertNotNull(etas);
        assertEquals(3, etas.passengerEtas().size());
        assertEquals(0, etas.passengerEtas().get(0).peopleAhead());
        assertEquals(1, etas.passengerEtas().get(1).peopleAhead());
        assertEquals(2, etas.passengerEtas().get(2).peopleAhead());
    }

    @Test
    @DisplayName("경유지 탑승 완료 처리가 순차적으로 다음 경유지를 가리켜야 한다")
    void completeStopSequentially() {
        UUID sampleId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID stop1 = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444441");

        PickupPlanApi.StopCompletionResult result = api.completeStop(sampleId, stop1);
        assertEquals(sampleId, result.planId());
        assertEquals(stop1, result.completedStopId());
        assertNotNull(result.nextStopId());
        assertEquals("이탑승", result.nextStopPassengerNickname());
        assertEquals(2, result.remainingStopsCount());
    }
}
