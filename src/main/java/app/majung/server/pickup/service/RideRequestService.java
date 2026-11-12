package app.majung.server.pickup.service;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Requests target either an approved driver account or an active vehicle device directly. */
@Service
@Transactional
public class RideRequestService {
    private static final String TRUST_TRUSTED = "TRUSTED";
    private static final String TRUST_BLOCKED = "BLOCKED";
    private static final String TRUST_CONNECTED = "CONNECTED";
    private static final String TRUST_FIRST_TIME = "FIRST_TIME";

    private final RideRequestRepository requests;
    private final MemberService members;
    private final MemberRepository memberRepository;
    private final VehicleAccountLinkRepository links;
    private final VehicleRegistrationService registration;
    private final RegisteredVehicleRepository vehicles;
    private final VehiclePassengerTrustRepository trustRepo;
    private final PickupRepository pickups;
    private final PickupService pickupService;
    private final TokenService tokens;
    private final Clock clock;
    private final MemberConnectionRepository connections;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Value("${majung.vehicle-freshness}") private Duration vehicleFreshness;

    public RideRequestService(RideRequestRepository requests, MemberService members, MemberRepository memberRepository,
            VehicleAccountLinkRepository links, VehicleRegistrationService registration, RegisteredVehicleRepository vehicles,
            VehiclePassengerTrustRepository trustRepo, PickupRepository pickups, PickupService pickupService,
            TokenService tokens, Clock clock, MemberConnectionRepository connections) {
        this.requests = requests;
        this.members = members;
        this.memberRepository = memberRepository;
        this.links = links;
        this.registration = registration;
        this.vehicles = vehicles;
        this.trustRepo = trustRepo;
        this.pickups = pickups;
        this.pickupService = pickupService;
        this.tokens = tokens;
        this.clock = clock;
        this.connections = connections;
    }

    public List<RideRequestApi.Contact> contacts(String auth, int page) {
        UUID driver = deviceDriver(auth);
        if (page < 0 || page > 50) throw error(400, "PAGE_INVALID", "페이지를 확인해주세요.");
        return connections.accepted(driver, PageRequest.of(page, 20)).stream().map(connection -> {
            UUID otherId = connection.firstMember.equals(driver) ? connection.secondMember : connection.firstMember;
            try {
                Member other = members.current(otherId);
                return new RideRequestApi.Contact(connection.id, other.nickname, other.majungId);
            } catch (ApiException failure) {
                if (failure.status == 401) return null;
                throw failure;
            }
        }).filter(Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public RideRequestApi.VehicleLookupView lookupVehicleByCode(UUID self, String rawCode) {
        ready(self);
        String code = VehicleCodeGenerator.normalizeLookupCode(rawCode);
        if (code == null) throw error(400, "VEHICLE_CODE_INVALID", "차량 코드를 정확히 입력해주세요. (예: VEH-7K2M9A)");
        RegisteredVehicle vehicle = vehicles.findByVehicleCode(code)
                .filter(v -> v.revokedAt == null)
                .orElseThrow(() -> error(404, "VEHICLE_NOT_FOUND", "입력한 코드와 일치하는 차량을 찾을 수 없어요."));
        if (!vehicle.directRequestEnabled) {
            throw error(409, "VEHICLE_DIRECT_DISABLED", "이 차량은 현재 직접 마중 요청을 받지 않고 있어요.");
        }
        VehiclePassengerTrust trust = trustRepo.findByVehicleIdAndPassengerId(vehicle.id, self).orElse(null);
        if (trust != null && TRUST_BLOCKED.equals(trust.status)) {
            throw error(403, "REQUEST_BLOCKED", "이 차량에는 마중 요청을 보낼 수 없어요.");
        }
        return toVehicleLookupView(vehicle, trust != null && TRUST_TRUSTED.equals(trust.status));
    }

    @Transactional(readOnly = true)
    public List<RideRequestApi.VehicleLookupView> favoriteVehicles(UUID self) {
        members.current(self);
        return trustRepo.findByPassengerIdAndStatusOrderByUpdatedAtDesc(self, TRUST_TRUSTED).stream()
                .map(trust -> vehicles.findById(trust.vehicleId).orElse(null))
                .filter(v -> v != null && v.revokedAt == null && v.directRequestEnabled)
                .map(v -> toVehicleLookupView(v, true))
                .toList();
    }

    public void removeFavoriteVehicle(UUID self, UUID vehicleId) {
        members.current(self);
        VehiclePassengerTrust trust = trustRepo.findByVehicleIdAndPassengerId(vehicleId, self).orElse(null);
        if (trust != null && TRUST_TRUSTED.equals(trust.status)) {
            trustRepo.delete(trust);
        }
    }

    public RideRequestApi.View invite(String auth, RideRequestApi.Invite input) {
        RegisteredVehicle vehicle = registration.requireActive(auth);
        UUID driver = driverForVehicle(vehicle.id);
        driverLock(driver);
        ready(driver);
        MemberConnection connection = connections.findById(input.connectionId()).orElseThrow(RideRequestService::notFound);
        UUID passenger = connectedPassenger(connection, driver);
        ready(passenger);
        validatePlace(input.place(), "내비게이션에서 장소를 다시 확인해주세요.");
        RideRequest existing = requests.findById(input.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.driverInvitation || !Objects.equals(existing.driverId, driver) || !existing.passengerId.equals(passenger)
                    || !vehicle.id.equals(existing.vehicleId) || !existing.placeLabel.equals(input.place().label().strip())
                    || existing.placeLatitude != input.place().latitude() || existing.placeLongitude != input.place().longitude()) {
                throw conflict("초대 번호가 이미 사용됐어요. 목록을 확인해주세요.");
            }
            return view(existing, true, vehicle, driver);
        }
        List<RideRequest> all = vehicleOrDriverScope(vehicle.id, driver);
        if (all.stream().anyMatch(request -> request.passengerId.equals(passenger) && open(request))) {
            throw conflict("이 사람과 진행 중인 요청이나 초대가 있어요. 목록을 확인해주세요.");
        }
        if (all.stream().filter(this::open).count() >= 50) throw conflict("대기 중인 요청이 많아요. 기존 요청을 먼저 확인해주세요.");
        RideRequest request = new RideRequest();
        request.id = input.requestId();
        request.driverId = driver;
        request.passengerId = passenger;
        request.vehicleId = vehicle.id;
        request.driverInvitation = true;
        request.status = "INVITED";
        request.placeLabel = input.place().label().strip();
        request.placeLatitude = input.place().latitude();
        request.placeLongitude = input.place().longitude();
        request.createdAt = clock.instant();
        request.expiresAt = clock.instant().plus(Duration.ofMinutes(30));
        try {
            requests.saveAndFlush(request);
        } catch (org.springframework.dao.DataIntegrityViolationException collision) {
            throw conflict("초대 번호가 이미 사용됐어요. 목록을 확인해주세요.");
        }
        return view(request, true, vehicle, driver);
    }

    public RideRequestApi.View answerInvitation(UUID self, UUID id, boolean accept) {
        ready(self);
        RideRequest hint = requests.findById(id).orElseThrow(RideRequestService::notFound);
        if (!hint.passengerId.equals(self) || !hint.driverInvitation) throw notFound();
        lockTarget(hint);
        entityManager.refresh(hint);
        String state = state(hint);
        if (accept && List.of("QUEUED", "ACTIVE").contains(state) || !accept && state.equals("REJECTED")) {
            return view(hint, false);
        }
        if (!state.equals("INVITED")) throw conflict("응답할 수 없는 초대예요. 목록을 다시 확인해주세요.");
        if (accept) {
            enqueue(hint, hint.vehicleId);
        } else {
            hint.status = "REJECTED";
            hint.endedAt = clock.instant();
        }
        return view(hint, false);
    }

    public RideRequestApi.View cancelInvitation(String auth, UUID id) {
        RegisteredVehicle vehicle = registration.requireActive(auth);
        UUID driver = driverForVehicle(vehicle.id);
        driverLock(driver);
        ready(driver);
        RideRequest request = authorizeRequest(vehicle, driver, id);
        if (!request.driverInvitation) throw notFound();
        if (state(request).equals("CANCELLED")) return view(request, true, vehicle, driver);
        if (!List.of("INVITED", "QUEUED").contains(state(request))) throw conflict("초대 대기나 픽업 대기 중에만 취소할 수 있어요.");
        request.status = "CANCELLED";
        request.endedAt = clock.instant();
        return view(request, true, vehicle, driver);
    }

    private UUID connectedPassenger(MemberConnection connection, UUID driver) {
        if (!connection.status.equals("ACCEPTED") || !connection.firstMember.equals(driver) && !connection.secondMember.equals(driver)) {
            throw error(409, "CONNECTION_REQUIRED", "연결된 사람에게만 초대를 보낼 수 있어요.");
        }
        return connection.firstMember.equals(driver) ? connection.secondMember : connection.firstMember;
    }

    private boolean invitationAvailable(RideRequest request) {
        if (!request.driverInvitation) return true;
        if (request.vehicleId == null || request.driverId == null) return false;
        VehicleAccountLink link = links.findById(request.vehicleId).orElse(null);
        if (link == null || !link.member.id.equals(request.driverId)
                || vehicles.findById(request.vehicleId).map(vehicle -> vehicle.revokedAt != null).orElse(true)) return false;
        UUID first = request.driverId.compareTo(request.passengerId) < 0 ? request.driverId : request.passengerId;
        UUID second = first.equals(request.driverId) ? request.passengerId : request.driverId;
        return connections.findByFirstMemberAndSecondMember(first, second).map(c -> c.status.equals("ACCEPTED")).orElse(false);
    }

    private void enqueue(RideRequest request, UUID vehicleId) {
        UUID effectiveVehicleId = request.vehicleId != null ? request.vehicleId : vehicleId;
        if (request.vehicleId == null) request.vehicleId = effectiveVehicleId;
        request.queueOrder = vehicleOrDriverScope(effectiveVehicleId, request.driverId).stream()
                .map(r -> r.queueOrder).filter(Objects::nonNull).max(Long::compare).orElse(0L) + 1;
        request.status = "QUEUED";
        request.expiresAt = clock.instant().plus(Duration.ofHours(2));
    }

    public RideRequestApi.View submit(UUID self, RideRequestApi.Submit input) {
        ready(self);
        if (input.driverId() == null && input.vehicleId() == null) {
            throw error(400, "TARGET_REQUIRED", "요청할 운전자 또는 차량을 선택해주세요.");
        }
        validatePlace(input.place(), "지도에서 만날 장소를 다시 선택해주세요.");
        if (input.vehicleId() != null) {
            return submitToVehicle(self, input);
        }
        if (self.equals(input.driverId())) throw error(400, "SELF_REQUEST", "본인에게 마중을 요청할 수 없어요.");
        driverLock(input.driverId());
        ready(input.driverId());
        List<VehicleAccountLink> activeLinks = links.findByMemberIdOrderByLinkedAtDesc(input.driverId()).stream()
                .filter(link -> vehicles.findById(link.vehicleId).map(vehicle -> vehicle.revokedAt == null).orElse(false))
                .toList();
        if (activeLinks.isEmpty()) throw error(409, "DRIVER_UNAVAILABLE", "상대가 아직 차량을 연결하지 않았어요.");
        for (VehicleAccountLink link : activeLinks) {
            VehiclePassengerTrust trust = trustRepo.findByVehicleIdAndPassengerId(link.vehicleId, self).orElse(null);
            if (trust != null && TRUST_BLOCKED.equals(trust.status)) {
                throw error(403, "REQUEST_BLOCKED", "이 차량에는 마중 요청을 보낼 수 없어요.");
            }
        }
        RideRequest existing = requests.findById(input.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.passengerId.equals(self) || !Objects.equals(existing.driverId, input.driverId())
                    || !existing.placeLabel.equals(input.place().label().strip())
                    || existing.placeLatitude != input.place().latitude()
                    || existing.placeLongitude != input.place().longitude()) {
                throw conflict("요청 번호가 이미 사용됐어요. 목록을 확인해주세요.");
            }
            return view(existing, false);
        }
        List<RideRequest> all = requests.findByDriverIdOrderByCreatedAtAscIdAsc(input.driverId());
        if (all.stream().anyMatch(request -> request.passengerId.equals(self) && open(request))) {
            throw conflict("이미 이 운전자에게 보낸 요청이 있어요. 기존 요청을 확인해주세요.");
        }
        if (all.stream().filter(this::open).count() >= 50) throw conflict("운전자의 대기 요청이 많아요. 잠시 후 다시 요청해주세요.");
        RideRequest request = buildNewRequest(input.requestId(), input.driverId(), null, self, input.place());
        try {
            requests.saveAndFlush(request);
        } catch (org.springframework.dao.DataIntegrityViolationException collision) {
            throw conflict("요청 번호가 이미 사용됐어요. 목록을 확인해주세요.");
        }
        return view(request, false);
    }

    private RideRequestApi.View submitToVehicle(UUID self, RideRequestApi.Submit input) {
        RegisteredVehicle vehicle = vehicles.findLockedById(input.vehicleId())
                .filter(v -> v.revokedAt == null)
                .orElseThrow(() -> error(409, "DRIVER_UNAVAILABLE", "요청할 수 없는 차량이에요."));
        if (!vehicle.directRequestEnabled) {
            throw error(409, "VEHICLE_DIRECT_DISABLED", "이 차량은 현재 직접 마중 요청을 받지 않고 있어요.");
        }
        VehicleAccountLink link = links.findById(vehicle.id).orElse(null);
        UUID linkedDriver = null;
        if (link != null) {
            if (link.member.id.equals(self)) throw error(400, "SELF_REQUEST", "본인 차량에는 마중을 요청할 수 없어요.");
            driverLock(link.member.id);
            if (isMemberReady(link.member.id)) linkedDriver = link.member.id;
        }
        VehiclePassengerTrust trust = trustRepo.findByVehicleIdAndPassengerId(vehicle.id, self).orElse(null);
        if (trust != null && TRUST_BLOCKED.equals(trust.status)) {
            throw error(403, "REQUEST_BLOCKED", "이 차량에는 마중 요청을 보낼 수 없어요.");
        }
        RideRequest existing = requests.findById(input.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.passengerId.equals(self) || !vehicle.id.equals(existing.vehicleId)
                    || !existing.placeLabel.equals(input.place().label().strip())
                    || existing.placeLatitude != input.place().latitude()
                    || existing.placeLongitude != input.place().longitude()) {
                throw conflict("요청 번호가 이미 사용됐어요. 목록을 확인해주세요.");
            }
            return view(existing, false);
        }
        List<RideRequest> all = vehicleOrDriverScope(vehicle.id, linkedDriver);
        if (all.stream().anyMatch(r -> r.passengerId.equals(self) && open(r))) {
            throw conflict("이미 이 차량에 보낸 요청이 있어요. 기존 요청을 확인해주세요.");
        }
        if (all.stream().filter(this::open).count() >= 50) throw conflict("차량의 대기 요청이 많아요. 잠시 후 다시 요청해주세요.");
        RideRequest request = buildNewRequest(input.requestId(), linkedDriver, vehicle.id, self, input.place());
        try {
            requests.saveAndFlush(request);
        } catch (org.springframework.dao.DataIntegrityViolationException collision) {
            throw conflict("요청 번호가 이미 사용됐어요. 목록을 확인해주세요.");
        }
        return view(request, false);
    }

    private RideRequest buildNewRequest(UUID requestId, UUID driverId, UUID vehicleId, UUID passengerId, PickupApi.Place place) {
        RideRequest request = new RideRequest();
        request.id = requestId;
        request.driverId = driverId;
        request.vehicleId = vehicleId;
        request.passengerId = passengerId;
        request.status = "PENDING";
        request.placeLabel = place.label().strip();
        request.placeLatitude = place.latitude();
        request.placeLongitude = place.longitude();
        request.createdAt = clock.instant();
        request.expiresAt = clock.instant().plus(Duration.ofMinutes(30));
        return request;
    }

    public List<RideRequestApi.View> passenger(UUID self) {
        members.current(self);
        return requests.passengerRequests(self, PageRequest.of(0, 50)).stream().map(request -> view(request, false)).toList();
    }

    @Transactional(readOnly = true)
    public List<Object> passengerRevision(UUID self) {
        return passenger(self).stream().map(v -> (Object) List.of(
                v.requestId(), v.status(), v.nickname(), String.valueOf(v.majungId()), v.place(),
                String.valueOf(v.peopleAhead()), v.currentPickupRemainingSeconds() != null, v.driverInvitation()
        )).toList();
    }

    public List<RideRequestApi.View> received(String auth) {
        RegisteredVehicle vehicle = registration.requireActive(auth);
        UUID linkedDriver = readyLinkedDriverId(vehicle.id);
        return vehicleOrDriverScope(vehicle.id, linkedDriver).stream()
                .filter(this::open)
                .sorted(Comparator.comparing((RideRequest r) -> r.queueOrder == null ? Long.MAX_VALUE : r.queueOrder)
                        .thenComparing(r -> r.createdAt).thenComparing(r -> r.id))
                .map(request -> view(request, true, vehicle, linkedDriver))
                .toList();
    }

    public RideRequestApi.View accept(String auth, UUID id) {
        RegisteredVehicle vehicle = registration.requireActive(auth);
        UUID linkedDriver = readyLinkedDriverId(vehicle.id);
        RideRequest request = authorizeRequest(vehicle, linkedDriver, id);
        ready(request.passengerId);
        String state = state(request);
        if (state.equals("QUEUED") || state.equals("ACTIVE")) {
            markTrust(vehicle.id, request.passengerId, TRUST_TRUSTED);
            return view(request, true, vehicle, linkedDriver);
        }
        if (!state.equals("PENDING")) throw conflict("수락할 수 없는 요청이에요. 목록을 다시 확인해주세요.");
        enqueue(request, vehicle.id);
        markTrust(vehicle.id, request.passengerId, TRUST_TRUSTED);
        return view(request, true, vehicle, linkedDriver);
    }

    public RideRequestApi.View reject(String auth, UUID id) {
        return reject(auth, id, false);
    }

    public RideRequestApi.View reject(String auth, UUID id, boolean block) {
        RegisteredVehicle vehicle = registration.requireActive(auth);
        UUID linkedDriver = readyLinkedDriverId(vehicle.id);
        RideRequest request = authorizeRequest(vehicle, linkedDriver, id);
        if (state(request).equals("REJECTED")) {
            if (block) markTrust(vehicle.id, request.passengerId, TRUST_BLOCKED);
            return view(request, true, vehicle, linkedDriver);
        }
        if (!state(request).equals("PENDING")) throw conflict("응답 대기 중인 요청만 거절할 수 있어요.");
        request.status = "REJECTED";
        request.endedAt = clock.instant();
        if (block) markTrust(vehicle.id, request.passengerId, TRUST_BLOCKED);
        return view(request, true, vehicle, linkedDriver);
    }

    public RideRequestApi.View cancel(UUID self, UUID id) {
        members.current(self);
        RideRequest hint = requests.findById(id).orElseThrow(RideRequestService::notFound);
        if (!hint.passengerId.equals(self)) throw notFound();
        lockTarget(hint);
        entityManager.refresh(hint);
        RideRequest request = hint;
        if (state(request).equals("CANCELLED")) return view(request, false);
        if (!open(request)) throw conflict("이미 종료된 요청이에요.");
        // Active cancellation remains a driver's explicit action; never lock a pickup before a vehicle row.
        if (state(request).equals("ACTIVE")) throw conflict("진행 중인 픽업의 취소는 운전자에게 요청해주세요.");
        request.status = "CANCELLED";
        request.endedAt = clock.instant();
        return view(request, false);
    }

    public RideRequestApi.Started start(String auth, UUID id) {
        RegisteredVehicle vehicle = registration.requireActive(auth);
        UUID linkedDriver = readyLinkedDriverId(vehicle.id);
        RideRequest request = authorizeRequest(vehicle, linkedDriver, id);
        ready(request.passengerId);
        String state = state(request);
        if (state.equals("ACTIVE") && vehicle.id.equals(request.vehicleId)) {
            Pickup pickup = pickups.findLockedById(request.pickupId).orElseThrow(RideRequestService::notFound);
            String owner = tokens.issue();
            pickup.ownerHash = tokens.hash(owner);
            return new RideRequestApi.Started(pickup.id, owner, pickupService.snapshotForAccount(pickup.id));
        }
        if (request.driverInvitation && !vehicle.id.equals(request.vehicleId)) throw conflict("초대를 보낸 차량에서 픽업을 시작해주세요.");
        if (!state.equals("QUEUED")) throw conflict("대기 중인 요청만 시작할 수 있어요.");
        if (vehicleOrDriverScope(vehicle.id, request.driverId).stream().anyMatch(other ->
                state(other).equals("ACTIVE") || (state(other).equals("QUEUED") && other.queueOrder != null && request.queueOrder != null && other.queueOrder < request.queueOrder))) {
            throw conflict("먼저 수락한 픽업을 마친 뒤 시작해주세요.");
        }
        if (pickups.findLockedByVehicleId(vehicle.id).stream().anyMatch(p -> !p.isTerminal() && !p.isExpired(clock.instant()))) {
            throw conflict("차량에서 진행 중인 픽업을 먼저 마쳐주세요.");
        }
        PickupApi.Created created = pickupService.createPickup(place(request), auth);
        PickupApi.Joined joined = pickupService.joinPickup(created.pickupId(), new PickupApi.JoinRequest(created.inviteToken(), displayName(request.passengerId)));
        PickupApi.Snapshot snapshot = pickupService.approveParticipant(created.pickupId(), joined.participantId(), "Bearer " + created.driverToken());
        request.pickupId = created.pickupId();
        request.vehicleId = vehicle.id;
        request.status = "ACTIVE";
        markTrust(vehicle.id, request.passengerId, TRUST_TRUSTED);
        return new RideRequestApi.Started(created.pickupId(), created.driverToken(), snapshot);
    }

    public RideRequestApi.Tracking tracking(UUID self, UUID id) {
        RideRequest request = passengerActive(self, id);
        return new RideRequestApi.Tracking(pickupService.snapshotForAccount(request.pickupId), pickupService.vehicleForAccount(request.pickupId));
    }

    public PickupApi.ArrivalPushState subscribeArrivalPush(UUID self, UUID id, String endpoint) {
        return subscribeArrivalPush(self, id, new PickupApi.ArrivalPushRequest(endpoint));
    }

    public PickupApi.ArrivalPushState subscribeArrivalPush(UUID self, UUID id, PickupApi.ArrivalPushRequest request) {
        return pickupService.subscribeArrivalPushForAccount(passengerActive(self, id).pickupId, request, id);
    }

    public PickupApi.ArrivalPushState unsubscribeArrivalPush(UUID self, UUID id) {
        return pickupService.unsubscribeArrivalPushForAccount(passengerActive(self, id).pickupId);
    }

    public PickupApi.Snapshot proposePlace(UUID self, UUID id, PickupApi.Place place) {
        return pickupService.proposePlaceForAccount(passengerActive(self, id).pickupId, place);
    }

    public PickupApi.Snapshot withdrawPlaceProposal(UUID self, UUID id) {
        return pickupService.withdrawPlaceProposalForAccount(passengerActive(self, id).pickupId);
    }

    public PickupApi.Snapshot readiness(UUID self, UUID id, PickupApi.ReadinessRequest input) {
        RideRequest request = passengerActive(self, id);
        return pickupService.readinessForAccount(request.pickupId, input);
    }

    public PickupApi.Snapshot quickMessage(UUID self, UUID id, String message) {
        RideRequest request = passengerActive(self, id);
        return pickupService.sendQuickMessageForAccount(request.pickupId, message);
    }

    private RideRequest passengerActive(UUID self, UUID id) {
        members.current(self);
        RideRequest request = requests.findById(id).orElseThrow(RideRequestService::notFound);
        if (!request.passengerId.equals(self)) throw notFound();
        if (request.driverId != null) {
            members.current(request.driverId);
        } else if (request.vehicleId == null || vehicles.findById(request.vehicleId).map(v -> v.revokedAt != null).orElse(true)) {
            throw error(403, "PICKUP_NOT_STARTED", "내 픽업이 시작된 뒤 확인할 수 있어요.");
        }
        if (!state(request).equals("ACTIVE")) throw error(403, "PICKUP_NOT_STARTED", "내 픽업이 시작된 뒤 확인할 수 있어요.");
        return request;
    }

    private void lockTarget(RideRequest request) {
        if (request.vehicleId != null) vehicles.findLockedById(request.vehicleId);
        if (request.driverId != null) driverLock(request.driverId);
    }

    private RideRequest authorizeRequest(RegisteredVehicle vehicle, UUID linkedDriver, UUID requestId) {
        RideRequest request = requests.findById(requestId).orElseThrow(RideRequestService::notFound);
        boolean ownsByVehicle = vehicle.id.equals(request.vehicleId);
        boolean ownsByDriver = request.vehicleId == null && linkedDriver != null && linkedDriver.equals(request.driverId);
        if (!ownsByVehicle && !ownsByDriver) throw notFound();
        if (request.driverId != null) driverLock(request.driverId);
        return request;
    }

    private List<RideRequest> vehicleOrDriverScope(UUID vehicleId, UUID driverId) {
        LinkedHashMap<UUID, RideRequest> merged = new LinkedHashMap<>();
        if (vehicleId != null) {
            for (RideRequest r : requests.findByVehicleIdOrderByCreatedAtAscIdAsc(vehicleId)) merged.put(r.id, r);
        }
        if (driverId != null) {
            for (RideRequest r : requests.findByDriverIdOrderByCreatedAtAscIdAsc(driverId)) merged.putIfAbsent(r.id, r);
        }
        return List.copyOf(merged.values());
    }

    private void markTrust(UUID vehicleId, UUID passengerId, String status) {
        if (vehicleId == null || passengerId == null) return;
        VehiclePassengerTrust row = trustRepo.findByVehicleIdAndPassengerId(vehicleId, passengerId)
                .orElseGet(() -> {
                    VehiclePassengerTrust created = new VehiclePassengerTrust();
                    created.id = UUID.randomUUID();
                    created.vehicleId = vehicleId;
                    created.passengerId = passengerId;
                    return created;
                });
        row.status = status;
        row.updatedAt = clock.instant();
        trustRepo.save(row);
    }

    private RideRequestApi.VehicleLookupView toVehicleLookupView(RegisteredVehicle vehicle, boolean trusted) {
        VehicleAccountLink link = links.findById(vehicle.id).orElse(null);
        Member linkedMember = null;
        if (link != null) {
            try {
                linkedMember = members.current(link.member.id);
            } catch (ApiException failure) {
                if (failure.status != 401) throw failure;
            }
        }
        return new RideRequestApi.VehicleLookupView(
                vehicle.id,
                vehicle.vehicleCode,
                vehicle.summaryDisplayName(),
                vehicle.vehicleModel,
                vehicle.licensePlate,
                vehicle.ownerHint,
                vehicle.directRequestEnabled,
                linkedMember != null,
                trusted,
                linkedMember == null ? null : linkedMember.nickname,
                linkedMember == null ? null : linkedMember.majungId);
    }

    private UUID deviceDriver(String auth) {
        RegisteredVehicle vehicle = registration.requireActive(auth);
        UUID driver = driverForVehicle(vehicle.id);
        driverLock(driver);
        ready(driver);
        return driver;
    }

    private UUID driverForVehicle(UUID id) {
        return links.findById(id).orElseThrow(() -> error(409, "DRIVER_ACCOUNT_REQUIRED", "차량에 운전자 계정을 먼저 연결해주세요.")).member.id;
    }

    private UUID readyLinkedDriverId(UUID vehicleId) {
        VehicleAccountLink link = links.findById(vehicleId).orElse(null);
        if (link == null || !isMemberReady(link.member.id)) return null;
        driverLock(link.member.id);
        return link.member.id;
    }

    private boolean isMemberReady(UUID id) {
        try {
            return members.current(id).profileReady;
        } catch (ApiException failure) {
            if (failure.status == 401) return false;
            throw failure;
        }
    }

    private void driverLock(UUID id) { memberRepository.lockById(id).orElseThrow(RideRequestService::notFound); }

    private void ready(UUID id) {
        if (!members.current(id).profileReady) throw error(409, "PROFILE_REQUIRED", "먼저 닉네임을 설정해주세요.");
    }

    private void validatePlace(PickupApi.Place place, String message) {
        if (Math.abs(place.latitude()) < 0.000001 && Math.abs(place.longitude()) < 0.000001) {
            throw error(400, "PLACE_INVALID", message);
        }
    }

    private String displayName(UUID id) {
        return members.current(id).nickname.codePoints().limit(12).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private boolean open(RideRequest request) { return List.of("INVITED", "PENDING", "QUEUED", "ACTIVE").contains(state(request)); }

    private String state(RideRequest request) {
        if (List.of("INVITED", "QUEUED").contains(request.status) && !invitationAvailable(request)) return "CANCELLED";
        if (request.pickupId != null) {
            Pickup pickup = pickups.findById(request.pickupId).orElse(null);
            if (pickup == null) return "EXPIRED";
            if (pickup.isTerminal()) return pickup.status.name();
            if (pickup.isExpired(clock.instant())) return "EXPIRED";
        }
        if (List.of("INVITED", "PENDING", "QUEUED").contains(request.status) && !clock.instant().isBefore(request.expiresAt)) return "EXPIRED";
        try {
            members.current(request.passengerId);
            if (request.driverId != null) {
                members.current(request.driverId);
            } else if (request.vehicleId == null || vehicles.findById(request.vehicleId).map(v -> v.revokedAt != null).orElse(true)) {
                return "CANCELLED";
            }
        } catch (ApiException failure) {
            if (failure.status == 401) return "CANCELLED";
            throw failure;
        }
        return request.status;
    }

    private RideRequestApi.View view(RideRequest request, boolean driver) {
        RegisteredVehicle vehicle = request.vehicleId == null ? null : vehicles.findById(request.vehicleId).orElse(null);
        return view(request, driver, vehicle, request.driverId);
    }

    private RideRequestApi.View view(RideRequest request, boolean driver, RegisteredVehicle scopedVehicle, UUID linkedDriverId) {
        String state = state(request);
        Member other = null;
        UUID targetMemberId = driver ? request.passengerId : request.driverId;
        if (targetMemberId != null) {
            try {
                other = members.current(targetMemberId);
            } catch (ApiException failure) {
                if (failure.status != 401) throw failure;
            }
        }
        RegisteredVehicle vehicle = scopedVehicle != null ? scopedVehicle
                : (request.vehicleId != null ? vehicles.findById(request.vehicleId).orElse(null) : resolveLinkedVehicle(request.driverId));

        Integer ahead = null;
        Long remaining = null;
        long remainingValidForMillis = 0;
        if (!driver && state.equals("QUEUED")) {
            List<RideRequest> all = vehicleOrDriverScope(request.vehicleId, request.driverId);
            ahead = (int) all.stream().filter(r -> state(r).equals("ACTIVE")
                    || (state(r).equals("QUEUED") && r.queueOrder != null && request.queueOrder != null && r.queueOrder < request.queueOrder)).count();
            if (ahead == 0 && request.driverId != null) {
                boolean legacyActive = links.findByMemberIdOrderByLinkedAtDesc(request.driverId).stream().anyMatch(link ->
                        pickups.findByVehicleId(link.vehicleId).stream().anyMatch(p -> !p.isTerminal() && !p.isExpired(clock.instant())
                                && all.stream().noneMatch(r -> p.id.equals(r.pickupId))));
                if (legacyActive) ahead = 1;
            }
            // Remaining time is for the CURRENT pickup, never an ETA to this waiting passenger.
            for (RideRequest active : all) if (state(active).equals("ACTIVE")) {
                Pickup p = pickups.findById(active.pickupId).orElse(null);
                if (p != null && p.vehicleSourceTime != null && p.vehicleSourceTime.plus(vehicleFreshness).isAfter(clock.instant())
                        && p.vehicleReceivedAt != null && p.vehicleReceivedAt.plus(vehicleFreshness).isAfter(clock.instant())) {
                    remaining = p.durationSeconds;
                    java.time.Instant sourceExpiry = p.vehicleSourceTime.plus(vehicleFreshness);
                    java.time.Instant receivedExpiry = p.vehicleReceivedAt.plus(vehicleFreshness);
                    remainingValidForMillis = Math.max(0, Duration.between(clock.instant(), sourceExpiry.isBefore(receivedExpiry) ? sourceExpiry : receivedExpiry).toMillis());
                }
            }
        }

        String displayNickname;
        String displayMajungId;
        if (other != null) {
            displayNickname = other.nickname;
            displayMajungId = other.majungId;
        } else if (!driver && request.driverId == null && vehicle != null) {
            displayNickname = vehicle.summaryDisplayName();
            displayMajungId = vehicle.vehicleCode;
        } else {
            displayNickname = "연결이 종료된 회원";
            displayMajungId = null;
        }

        String trustStatus = resolveTrustStatus(vehicle == null ? request.vehicleId : vehicle.id, request.passengerId, linkedDriverId);

        return new RideRequestApi.View(
                request.id,
                state,
                displayNickname,
                displayMajungId,
                place(request),
                request.expiresAt,
                ahead,
                remaining,
                remainingValidForMillis,
                request.driverInvitation,
                vehicle == null ? null : vehicle.id,
                vehicle == null ? null : vehicle.vehicleCode,
                vehicle == null ? null : vehicle.vehicleModel,
                vehicle == null ? null : vehicle.licensePlate,
                vehicle == null ? null : vehicle.ownerHint,
                trustStatus);
    }

    private RegisteredVehicle resolveLinkedVehicle(UUID driverId) {
        if (driverId == null) return null;
        return links.findByMemberIdOrderByLinkedAtDesc(driverId).stream()
                .map(link -> vehicles.findById(link.vehicleId).orElse(null))
                .filter(v -> v != null && v.revokedAt == null)
                .findFirst().orElse(null);
    }

    private String resolveTrustStatus(UUID vehicleId, UUID passengerId, UUID linkedDriverId) {
        if (vehicleId != null) {
            VehiclePassengerTrust trust = trustRepo.findByVehicleIdAndPassengerId(vehicleId, passengerId).orElse(null);
            if (trust != null) return trust.status;
        }
        if (linkedDriverId != null) {
            UUID first = linkedDriverId.compareTo(passengerId) < 0 ? linkedDriverId : passengerId;
            UUID second = first.equals(linkedDriverId) ? passengerId : linkedDriverId;
            if (connections.findByFirstMemberAndSecondMember(first, second).map(c -> "ACCEPTED".equals(c.status)).orElse(false)) {
                return TRUST_CONNECTED;
            }
        }
        return TRUST_FIRST_TIME;
    }

    private PickupApi.Place place(RideRequest r) {
        if (r.pickupId != null) {
            Pickup pickup = pickups.findById(r.pickupId).orElse(null);
            if (pickup != null) return new PickupApi.Place(pickup.placeLabel, pickup.placeLatitude, pickup.placeLongitude);
        }
        return new PickupApi.Place(r.placeLabel, r.placeLatitude, r.placeLongitude);
    }

    private static ApiException notFound() { return error(404, "RIDE_REQUEST_NOT_FOUND", "마중 요청을 찾을 수 없어요."); }
    private static ApiException conflict(String message) { return error(409, "RIDE_REQUEST_STATE", message); }
    private static ApiException error(int status, String code, String message) { return new ApiException(status, code, message); }
}
