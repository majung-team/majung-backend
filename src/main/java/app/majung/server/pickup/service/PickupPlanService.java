package app.majung.server.pickup.service;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PickupPlanService {

    private final Clock clock;
    private final Map<UUID, PlanData> plans = new ConcurrentHashMap<>();

    public PickupPlanService(Clock clock) {
        this.clock = clock;
        seedSamplePlan();
    }

    public static record StopData(
            UUID stopId,
            UUID passengerId,
            String passengerNickname,
            PickupApi.Place place,
            UUID rideRequestId,
            int order,
            String status, // PENDING, READY, ARRIVED, COMPLETED, CANCELLED
            Instant estimatedArrivalAt
    ) {}

    public static record RouteData(
            UUID revisionId,
            List<UUID> orderedStopIds,
            long totalDurationSeconds,
            long timeSavedSeconds,
            boolean confirmed,
            Instant calculatedAt
    ) {}

    public static record PlanData(
            UUID planId,
            String planName,
            UUID driverId,
            String status, // DRAFT, OPTIMIZED, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED
            PickupApi.Place origin,
            PickupApi.Place destination,
            List<StopData> stops,
            RouteData activeRoute,
            long totalDurationSeconds,
            long totalDistanceMeters,
            Instant createdAt,
            Instant updatedAt
    ) {}

    private void seedSamplePlan() {
        UUID sampleId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        PickupApi.Place origin = new PickupApi.Place("출발지 (역삼역 4번 출구)", 37.5006, 127.0365);
        PickupApi.Place destination = new PickupApi.Place("최종 목적지 (판교 테크노밸리)", 37.4020, 127.1086);

        List<StopData> stops = new ArrayList<>();
        stops.add(new StopData(UUID.fromString("aaaaaaaa-1111-2222-3333-444444444441"),
                UUID.fromString("bbbbbbbb-1111-2222-3333-444444444441"), "김승객",
                new PickupApi.Place("경유지 1 (강남역 2번 출구)", 37.4979, 127.0276),
                UUID.randomUUID(), 1, "READY", clock.instant().plusSeconds(420)));
        stops.add(new StopData(UUID.fromString("aaaaaaaa-1111-2222-3333-444444444442"),
                UUID.fromString("bbbbbbbb-1111-2222-3333-444444444442"), "이탑승",
                new PickupApi.Place("경유지 2 (양재역 9번 출구)", 37.4843, 127.0346),
                UUID.randomUUID(), 2, "PENDING", clock.instant().plusSeconds(960)));
        stops.add(new StopData(UUID.fromString("aaaaaaaa-1111-2222-3333-444444444443"),
                UUID.fromString("bbbbbbbb-1111-2222-3333-444444444443"), "박동승",
                new PickupApi.Place("경유지 3 (청계산입구역 1번 출구)", 37.4475, 127.0543),
                UUID.randomUUID(), 3, "PENDING", clock.instant().plusSeconds(1620)));

        RouteData route = new RouteData(UUID.randomUUID(),
                List.of(stops.get(0).stopId(), stops.get(1).stopId(), stops.get(2).stopId()),
                2100, 360, true, clock.instant());

        plans.put(sampleId, new PlanData(sampleId, "출근길 판교행 셔틀",
                UUID.fromString("99999999-8888-7777-6666-555555555555"),
                "CONFIRMED", origin, destination, stops, route, 2100, 16800,
                clock.instant().minusSeconds(600), clock.instant()));
    }

    public PlanData createPlan(String planName, PickupApi.Place origin, PickupApi.Place destination,
                               List<UUID> rideRequestIds, String auth) {
        UUID planId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        Instant now = clock.instant();

        List<StopData> stops = new ArrayList<>();
        if (rideRequestIds != null) {
            int order = 1;
            for (UUID reqId : rideRequestIds) {
                stops.add(new StopData(UUID.randomUUID(), UUID.randomUUID(), "탑승자 " + order,
                        new PickupApi.Place("요청 경유지 " + order, 37.4980 + (order * 0.005), 127.0280 + (order * 0.005)),
                        reqId, order, "PENDING", null));
                order++;
            }
        }

        PlanData plan = new PlanData(planId, planName != null ? planName : "다중 픽업 계획",
                driverId, "DRAFT", origin, destination, stops, null, 0, 0, now, now);
        plans.put(planId, plan);
        return plan;
    }

    public PlanData getPlan(UUID id) {
        PlanData plan = plans.get(id);
        if (plan == null) throw new ApiException(404, "PLAN_NOT_FOUND", "픽업 계획을 찾을 수 없습니다.");
        return plan;
    }

    public PlanData addStop(UUID planId, UUID passengerId, String nickname, PickupApi.Place place,
                            UUID rideRequestId, Integer preferredOrder) {
        PlanData plan = getPlan(planId);
        List<StopData> updatedStops = new ArrayList<>(plan.stops());
        int order = (preferredOrder != null && preferredOrder > 0) ? preferredOrder : updatedStops.size() + 1;
        UUID stopId = UUID.randomUUID();
        updatedStops.add(new StopData(stopId, passengerId, nickname, place, rideRequestId, order, "PENDING", null));

        PlanData updated = new PlanData(plan.planId(), plan.planName(), plan.driverId(), "DRAFT",
                plan.origin(), plan.destination(), updatedStops, null, 0, 0, plan.createdAt(), clock.instant());
        plans.put(planId, updated);
        return updated;
    }

    public PlanData removeStop(UUID planId, UUID stopId) {
        PlanData plan = getPlan(planId);
        List<StopData> updatedStops = new ArrayList<>();
        int order = 1;
        for (StopData stop : plan.stops()) {
            if (!stop.stopId().equals(stopId)) {
                updatedStops.add(new StopData(stop.stopId(), stop.passengerId(), stop.passengerNickname(),
                        stop.place(), stop.rideRequestId(), order++, stop.status(), stop.estimatedArrivalAt()));
            }
        }

        PlanData updated = new PlanData(plan.planId(), plan.planName(), plan.driverId(), "DRAFT",
                plan.origin(), plan.destination(), updatedStops, null, 0, 0, plan.createdAt(), clock.instant());
        plans.put(planId, updated);
        return updated;
    }

    public RouteData optimizeRoute(UUID planId, String algorithm, boolean considerReadiness) {
        PlanData plan = getPlan(planId);
        if (plan.stops().isEmpty()) {
            throw new ApiException(400, "NO_STOPS_TO_OPTIMIZE", "경로를 최적화할 경유지가 없습니다.");
        }

        List<StopData> originalStops = plan.stops();
        int n = originalStops.size();

        // Brute-force TSP permutation search (N <= 5 is at most 120 permutations, evaluates in <1ms)
        List<Integer> bestPermutation = null;
        double minDistance = Double.MAX_VALUE;

        List<List<Integer>> permutations = generatePermutations(n);
        for (List<Integer> perm : permutations) {
            double distance = 0.0;
            double curLat = plan.origin().latitude();
            double curLng = plan.origin().longitude();

            for (int idx : perm) {
                StopData stop = originalStops.get(idx);
                distance += haversineDistanceMeters(curLat, curLng, stop.place().latitude(), stop.place().longitude());
                curLat = stop.place().latitude();
                curLng = stop.place().longitude();
            }
            distance += haversineDistanceMeters(curLat, curLng, plan.destination().latitude(), plan.destination().longitude());

            if (distance < minDistance) {
                minDistance = distance;
                bestPermutation = perm;
            }
        }

        List<UUID> orderedStopIds = new ArrayList<>();
        if (bestPermutation != null) {
            for (int idx : bestPermutation) {
                orderedStopIds.add(originalStops.get(idx).stopId());
            }
        }

        // Realistic estimated duration: 30 km/h average speed in city + 90s boarding buffer per stop
        long totalDurationSeconds = Math.round((minDistance / 8.33) + (n * 90));
        long timeSavedSeconds = Math.max(120, Math.round(totalDurationSeconds * 0.15));

        RouteData route = new RouteData(UUID.randomUUID(), orderedStopIds, totalDurationSeconds,
                timeSavedSeconds, false, clock.instant());

        PlanData updated = new PlanData(plan.planId(), plan.planName(), plan.driverId(), "OPTIMIZED",
                plan.origin(), plan.destination(), plan.stops(), route, totalDurationSeconds,
                Math.round(minDistance), plan.createdAt(), clock.instant());
        plans.put(planId, updated);
        return route;
    }

    public PlanData confirmRoute(UUID planId, UUID revisionId) {
        PlanData plan = getPlan(planId);
        if (plan.activeRoute() == null) {
            throw new ApiException(400, "ROUTE_NOT_OPTIMIZED", "먼저 경로 최적화를 진행해주세요.");
        }

        Instant now = clock.instant();
        Instant currentEta = now;
        Map<UUID, StopData> stopMap = new HashMap<>();
        for (StopData s : plan.stops()) stopMap.put(s.stopId(), s);

        List<StopData> reorderedStops = new ArrayList<>();
        int order = 1;
        for (UUID sId : plan.activeRoute().orderedStopIds()) {
            StopData s = stopMap.get(sId);
            if (s != null) {
                currentEta = currentEta.plusSeconds(300 + (order * 180));
                reorderedStops.add(new StopData(s.stopId(), s.passengerId(), s.passengerNickname(),
                        s.place(), s.rideRequestId(), order++, s.status(), currentEta));
            }
        }

        RouteData confirmedRoute = new RouteData(plan.activeRoute().revisionId(),
                plan.activeRoute().orderedStopIds(), plan.activeRoute().totalDurationSeconds(),
                plan.activeRoute().timeSavedSeconds(), true, plan.activeRoute().calculatedAt());

        PlanData updated = new PlanData(plan.planId(), plan.planName(), plan.driverId(), "CONFIRMED",
                plan.origin(), plan.destination(), reorderedStops, confirmedRoute,
                plan.totalDurationSeconds(), plan.totalDistanceMeters(), plan.createdAt(), now);
        plans.put(planId, updated);
        return updated;
    }

    public PlanData completeStop(UUID planId, UUID stopId) {
        PlanData plan = getPlan(planId);
        List<StopData> updatedStops = new ArrayList<>();
        boolean found = false;

        for (StopData s : plan.stops()) {
            if (s.stopId().equals(stopId)) {
                updatedStops.add(new StopData(s.stopId(), s.passengerId(), s.passengerNickname(),
                        s.place(), s.rideRequestId(), s.order(), "COMPLETED", s.estimatedArrivalAt()));
                found = true;
            } else {
                updatedStops.add(s);
            }
        }

        if (!found) throw new ApiException(404, "STOP_NOT_FOUND", "해당 경유지를 찾을 수 없습니다.");

        long remaining = updatedStops.stream().filter(s -> !"COMPLETED".equals(s.status())).count();
        String newStatus = remaining == 0 ? "COMPLETED" : "IN_PROGRESS";

        PlanData updated = new PlanData(plan.planId(), plan.planName(), plan.driverId(), newStatus,
                plan.origin(), plan.destination(), updatedStops, plan.activeRoute(),
                plan.totalDurationSeconds(), plan.totalDistanceMeters(), plan.createdAt(), clock.instant());
        plans.put(planId, updated);
        return updated;
    }

    public PlanData cancelPlan(UUID planId) {
        PlanData plan = getPlan(planId);
        PlanData updated = new PlanData(plan.planId(), plan.planName(), plan.driverId(), "CANCELLED",
                plan.origin(), plan.destination(), plan.stops(), plan.activeRoute(),
                plan.totalDurationSeconds(), plan.totalDistanceMeters(), plan.createdAt(), clock.instant());
        plans.put(planId, updated);
        return updated;
    }

    private static double haversineDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private static List<List<Integer>> generatePermutations(int n) {
        List<List<Integer>> result = new ArrayList<>();
        List<Integer> initial = new ArrayList<>();
        for (int i = 0; i < n; i++) initial.add(i);
        permute(initial, 0, result);
        return result;
    }

    private static void permute(List<Integer> arr, int k, List<List<Integer>> result) {
        for (int i = k; i < arr.size(); i++) {
            Collections.swap(arr, i, k);
            permute(arr, k + 1, result);
            Collections.swap(arr, k, i);
        }
        if (k == arr.size() - 1) {
            result.add(new ArrayList<>(arr));
        }
    }
}
