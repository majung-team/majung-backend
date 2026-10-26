package app.majung.server.pickup;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Requests belong to people; only an approved driver account can assign a vehicle. */
@Service
@Transactional
class RideRequestService {
    private final RideRequestRepository requests;
    private final MemberService members;
    private final MemberRepository memberRepository;
    private final VehicleAccountLinkRepository links;
    private final VehicleRegistrationService registration;
    private final RegisteredVehicleRepository vehicles;
    private final PickupRepository pickups;
    private final PickupService pickupService;
    private final TokenService tokens;
    private final Clock clock;
    private final MemberConnectionRepository connections;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Value("${majung.vehicle-freshness}") private Duration vehicleFreshness;
    RideRequestService(RideRequestRepository requests, MemberService members, MemberRepository memberRepository,
            VehicleAccountLinkRepository links, VehicleRegistrationService registration, RegisteredVehicleRepository vehicles,
            PickupRepository pickups, PickupService pickupService, TokenService tokens, Clock clock, MemberConnectionRepository connections) {
        this.requests=requests; this.members=members; this.memberRepository=memberRepository; this.links=links;
        this.registration=registration; this.vehicles=vehicles; this.pickups=pickups; this.pickupService=pickupService;
        this.tokens=tokens; this.clock=clock; this.connections=connections;
    }
    List<RideRequestApi.Contact> contacts(String auth, int page) {
        UUID driver = deviceDriver(auth);
        if (page < 0 || page > 50) throw error(400, "PAGE_INVALID", "페이지를 확인해주세요.");
        return connections.accepted(driver, PageRequest.of(page, 20)).stream().map(connection -> {
            UUID otherId = connection.firstMember.equals(driver) ? connection.secondMember : connection.firstMember;
            try {
                Member other = members.current(otherId);
                return new RideRequestApi.Contact(connection.id, other.nickname, other.majungId);
            } catch (ApiException failure) { if (failure.status == 401) return null; throw failure; }
        }).filter(Objects::nonNull).toList();
    }
    RideRequestApi.View invite(String auth, RideRequestApi.Invite input) {
        RegisteredVehicle vehicle = registration.requireActive(auth);
        UUID driver = driverForVehicle(vehicle.id); driverLock(driver); ready(driver);
        MemberConnection connection = connections.findById(input.connectionId()).orElseThrow(RideRequestService::notFound);
        UUID passenger = connectedPassenger(connection, driver);
        ready(passenger);
        if (Math.abs(input.place().latitude()) < 0.000001 && Math.abs(input.place().longitude()) < 0.000001)
            throw error(400, "PLACE_INVALID", "내비게이션에서 장소를 다시 확인해주세요.");
        RideRequest existing = requests.findById(input.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.driverInvitation || !existing.driverId.equals(driver) || !existing.passengerId.equals(passenger)
                    || !vehicle.id.equals(existing.vehicleId) || !existing.placeLabel.equals(input.place().label().strip())
                    || existing.placeLatitude != input.place().latitude() || existing.placeLongitude != input.place().longitude())
                throw conflict("초대 번호가 이미 사용됐어요. 목록을 확인해주세요.");
            return view(existing, true);
        }
        List<RideRequest> all = requests.findByDriverIdOrderByCreatedAtAscIdAsc(driver);
        if (all.stream().anyMatch(request -> request.passengerId.equals(passenger) && open(request)))
            throw conflict("이 사람과 진행 중인 요청이나 초대가 있어요. 목록을 확인해주세요.");
        if (all.stream().filter(this::open).count() >= 50) throw conflict("대기 중인 요청이 많아요. 기존 요청을 먼저 확인해주세요.");
        RideRequest request = new RideRequest(); request.id = input.requestId(); request.driverId = driver;
        request.passengerId = passenger; request.vehicleId = vehicle.id; request.driverInvitation = true;
        request.status = "INVITED"; request.placeLabel = input.place().label().strip();
        request.placeLatitude = input.place().latitude(); request.placeLongitude = input.place().longitude();
        request.createdAt = clock.instant(); request.expiresAt = clock.instant().plus(Duration.ofMinutes(30));
        try { requests.saveAndFlush(request); }
        catch (org.springframework.dao.DataIntegrityViolationException collision) { throw conflict("초대 번호가 이미 사용됐어요. 목록을 확인해주세요."); }
        return view(request, true);
    }
    RideRequestApi.View answerInvitation(UUID self, UUID id, boolean accept) {
        ready(self);
        RideRequest hint = requests.findById(id).orElseThrow(RideRequestService::notFound);
        if (!hint.passengerId.equals(self) || !hint.driverInvitation) throw notFound();
        driverLock(hint.driverId); entityManager.refresh(hint);
        String state = state(hint);
        if (accept && List.of("QUEUED", "ACTIVE").contains(state) || !accept && state.equals("REJECTED")) return view(hint, false);
        if (!state.equals("INVITED")) throw conflict("응답할 수 없는 초대예요. 목록을 다시 확인해주세요.");
        if (accept) enqueue(hint); else { hint.status = "REJECTED"; hint.endedAt = clock.instant(); }
        return view(hint, false);
    }
    RideRequestApi.View cancelInvitation(String auth, UUID id) {
        UUID driver = deviceDriver(auth); RideRequest request = owned(driver, id);
        if (!request.driverInvitation) throw notFound();
        if (state(request).equals("CANCELLED")) return view(request, true);
        if (!List.of("INVITED", "QUEUED").contains(state(request))) throw conflict("초대 대기나 픽업 대기 중에만 취소할 수 있어요.");
        request.status = "CANCELLED"; request.endedAt = clock.instant();
        return view(request, true);
    }
    private UUID connectedPassenger(MemberConnection connection, UUID driver) {
        if (!connection.status.equals("ACCEPTED") || !connection.firstMember.equals(driver) && !connection.secondMember.equals(driver))
            throw error(409, "CONNECTION_REQUIRED", "연결된 사람에게만 초대를 보낼 수 있어요.");
        return connection.firstMember.equals(driver) ? connection.secondMember : connection.firstMember;
    }
    private boolean invitationAvailable(RideRequest request) {
        if (!request.driverInvitation) return true;
        VehicleAccountLink link = links.findById(request.vehicleId).orElse(null);
        if (link == null || !link.member.id.equals(request.driverId)
                || vehicles.findById(request.vehicleId).map(vehicle -> vehicle.revokedAt != null).orElse(true)) return false;
        UUID first = request.driverId.compareTo(request.passengerId) < 0 ? request.driverId : request.passengerId;
        UUID second = first.equals(request.driverId) ? request.passengerId : request.driverId;
        return connections.findByFirstMemberAndSecondMember(first, second).map(c -> c.status.equals("ACCEPTED")).orElse(false);
    }
    private void enqueue(RideRequest request) {
        request.queueOrder = requests.findByDriverIdOrderByCreatedAtAscIdAsc(request.driverId).stream()
                .map(r -> r.queueOrder).filter(Objects::nonNull).max(Long::compare).orElse(0L) + 1;
        request.status = "QUEUED";
        request.expiresAt = clock.instant().plus(Duration.ofHours(2));
    }
    RideRequestApi.View submit(UUID self, RideRequestApi.Submit input) {
        ready(self);
        if (self.equals(input.driverId())) throw error(400, "SELF_REQUEST", "본인에게 마중을 요청할 수 없어요.");
        driverLock(input.driverId());
        ready(input.driverId());
        boolean registered = links.findByMemberIdOrderByLinkedAtDesc(input.driverId()).stream().anyMatch(link ->
                vehicles.findById(link.vehicleId).map(vehicle -> vehicle.revokedAt == null).orElse(false));
        if (!registered) throw error(409, "DRIVER_UNAVAILABLE", "상대가 아직 차량을 연결하지 않았어요.");
        if (Math.abs(input.place().latitude()) < 0.000001 && Math.abs(input.place().longitude()) < 0.000001)
            throw error(400, "PLACE_INVALID", "지도에서 만날 장소를 다시 선택해주세요.");
        RideRequest existing = requests.findById(input.requestId()).orElse(null);
        if (existing != null) {
            if (!existing.passengerId.equals(self) || !existing.driverId.equals(input.driverId())
                    || !existing.placeLabel.equals(input.place().label().strip()) || existing.placeLatitude != input.place().latitude()
                    || existing.placeLongitude != input.place().longitude()) throw conflict("요청 번호가 이미 사용됐어요. 목록을 확인해주세요.");
            return view(existing, false);
        }
        List<RideRequest> all = requests.findByDriverIdOrderByCreatedAtAscIdAsc(input.driverId());
        if (all.stream().anyMatch(request -> request.passengerId.equals(self) && open(request)))
            throw conflict("이미 이 운전자에게 보낸 요청이 있어요. 기존 요청을 확인해주세요.");
        if (all.stream().filter(this::open).count() >= 50) throw conflict("운전자의 대기 요청이 많아요. 잠시 후 다시 요청해주세요.");
        RideRequest request = new RideRequest(); request.id=input.requestId(); request.driverId=input.driverId();
        request.passengerId=self; request.status="PENDING"; request.placeLabel=input.place().label().strip();
        request.placeLatitude=input.place().latitude(); request.placeLongitude=input.place().longitude();
        request.createdAt=clock.instant(); request.expiresAt=clock.instant().plus(Duration.ofMinutes(30));
        try { requests.saveAndFlush(request); }
        catch (org.springframework.dao.DataIntegrityViolationException collision) { throw conflict("요청 번호가 이미 사용됐어요. 목록을 확인해주세요."); }
        return view(request, false);
    }
    List<RideRequestApi.View> passenger(UUID self) {
        members.current(self);
        return requests.passengerRequests(self, PageRequest.of(0, 50)).stream().map(request -> view(request, false)).toList();
    }
    List<RideRequestApi.View> received(String auth) {
        UUID driver = deviceDriver(auth);
        return requests.findByDriverIdOrderByCreatedAtAscIdAsc(driver).stream().filter(this::open)
                .sorted(Comparator.comparing((RideRequest r) -> r.queueOrder == null ? Long.MAX_VALUE : r.queueOrder)
                        .thenComparing(r -> r.createdAt).thenComparing(r -> r.id))
                .map(request -> view(request, true)).toList();
    }
    RideRequestApi.View accept(String auth, UUID id) {
        UUID driver=deviceDriver(auth); RideRequest request=owned(driver,id);
        ready(request.passengerId);
        String state=state(request);
        if (state.equals("QUEUED") || state.equals("ACTIVE")) return view(request,true);
        if (!state.equals("PENDING")) throw conflict("수락할 수 없는 요청이에요. 목록을 다시 확인해주세요.");
        enqueue(request);
        return view(request,true);
    }
    RideRequestApi.View reject(String auth, UUID id) {
        UUID driver=deviceDriver(auth); RideRequest request=owned(driver,id);
        if (state(request).equals("REJECTED")) return view(request,true);
        if (!state(request).equals("PENDING")) throw conflict("응답 대기 중인 요청만 거절할 수 있어요.");
        request.status="REJECTED"; request.endedAt=clock.instant(); return view(request,true);
    }
    RideRequestApi.View cancel(UUID self, UUID id) {
        members.current(self); RideRequest hint=requests.findById(id).orElseThrow(RideRequestService::notFound);
        if (!hint.passengerId.equals(self)) throw notFound();
        driverLock(hint.driverId); entityManager.refresh(hint); RideRequest request=hint;
        if (state(request).equals("CANCELLED")) return view(request,false);
        if (!open(request)) throw conflict("이미 종료된 요청이에요.");
        // Active cancellation remains a driver's explicit action; never lock a pickup before a vehicle row.
        if (state(request).equals("ACTIVE")) throw conflict("진행 중인 픽업의 취소는 운전자에게 요청해주세요.");
        request.status="CANCELLED"; request.endedAt=clock.instant(); return view(request,false);
    }
    RideRequestApi.Started start(String auth, UUID id) {
        RegisteredVehicle vehicle=registration.requireActive(auth);
        UUID driver=driverForVehicle(vehicle.id); driverLock(driver); ready(driver);
        RideRequest request=owned(driver,id); ready(request.passengerId);
        String state=state(request);
        if (state.equals("ACTIVE") && vehicle.id.equals(request.vehicleId)) {
            Pickup pickup=pickups.findLockedById(request.pickupId).orElseThrow(RideRequestService::notFound);
            String owner=tokens.issue(); pickup.ownerHash=tokens.hash(owner);
            return new RideRequestApi.Started(pickup.id,owner,pickupService.snapshotForAccount(pickup.id));
        }
        if (request.driverInvitation && !vehicle.id.equals(request.vehicleId)) throw conflict("초대를 보낸 차량에서 픽업을 시작해주세요.");
        if (!state.equals("QUEUED")) throw conflict("대기 중인 요청만 시작할 수 있어요.");
        if (requests.findByDriverIdOrderByCreatedAtAscIdAsc(driver).stream().anyMatch(other ->
                state(other).equals("ACTIVE") || state(other).equals("QUEUED") && other.queueOrder < request.queueOrder))
            throw conflict("먼저 수락한 픽업을 마친 뒤 시작해주세요.");
        if (pickups.findLockedByVehicleId(vehicle.id).stream().anyMatch(p -> !p.isTerminal() && !p.isExpired(clock.instant())))
            throw conflict("차량에서 진행 중인 픽업을 먼저 마쳐주세요.");
        PickupApi.Created created=pickupService.createPickup(place(request),auth);
        PickupApi.Joined joined=pickupService.joinPickup(created.pickupId(),new PickupApi.JoinRequest(created.inviteToken(), displayName(request.passengerId)));
        PickupApi.Snapshot snapshot=pickupService.approveParticipant(created.pickupId(),joined.participantId(),"Bearer "+created.driverToken());
        request.pickupId=created.pickupId(); request.vehicleId=vehicle.id; request.status="ACTIVE";
        return new RideRequestApi.Started(created.pickupId(),created.driverToken(),snapshot);
    }
    RideRequestApi.Tracking tracking(UUID self,UUID id) {
        RideRequest request=passengerActive(self,id);
        return new RideRequestApi.Tracking(pickupService.snapshotForAccount(request.pickupId),pickupService.vehicleForAccount(request.pickupId));
    }
    PickupApi.Snapshot readiness(UUID self,UUID id,PickupApi.ReadinessRequest input) {
        RideRequest request=passengerActive(self,id);
        return pickupService.readinessForAccount(request.pickupId,input);
    }
    private RideRequest passengerActive(UUID self,UUID id) {
        members.current(self); RideRequest request=requests.findById(id).orElseThrow(RideRequestService::notFound);
        if (!request.passengerId.equals(self)) throw notFound();
        members.current(request.driverId);
        if (!state(request).equals("ACTIVE")) throw error(403,"PICKUP_NOT_STARTED","내 픽업이 시작된 뒤 확인할 수 있어요.");
        return request;
    }
    private UUID deviceDriver(String auth) {
        RegisteredVehicle vehicle=registration.requireActive(auth);
        UUID driver=driverForVehicle(vehicle.id); driverLock(driver); ready(driver); return driver;
    }
    private UUID driverForVehicle(UUID id) { return links.findById(id).orElseThrow(() -> error(409,"DRIVER_ACCOUNT_REQUIRED","차량에 운전자 계정을 먼저 연결해주세요.")).member.id; }
    private RideRequest owned(UUID driver,UUID id) {
        RideRequest request=requests.findById(id).orElseThrow(RideRequestService::notFound);
        if (!request.driverId.equals(driver)) throw notFound(); return request;
    }
    private void driverLock(UUID id) { memberRepository.lockById(id).orElseThrow(RideRequestService::notFound); }
    private void ready(UUID id) { if (!members.current(id).profileReady) throw error(409,"PROFILE_REQUIRED","먼저 닉네임을 설정해주세요."); }
    private String displayName(UUID id) { return members.current(id).nickname.codePoints().limit(12).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString(); }
    private boolean open(RideRequest request) { return List.of("INVITED","PENDING","QUEUED","ACTIVE").contains(state(request)); }
    private String state(RideRequest request) {
        if (List.of("INVITED", "QUEUED").contains(request.status) && !invitationAvailable(request)) return "CANCELLED";
        if (request.pickupId != null) {
            Pickup pickup=pickups.findById(request.pickupId).orElse(null);
            if (pickup==null) return "EXPIRED";
            if (pickup.isTerminal()) return pickup.status.name();
            if (pickup.isExpired(clock.instant())) return "EXPIRED";
        }
        if (List.of("INVITED","PENDING","QUEUED").contains(request.status) && !clock.instant().isBefore(request.expiresAt)) return "EXPIRED";
        try { members.current(request.driverId); members.current(request.passengerId); }
        catch (ApiException failure) { if (failure.status==401) return "CANCELLED"; throw failure; }
        return request.status;
    }
    private RideRequestApi.View view(RideRequest request,boolean driver) {
        String state=state(request); Member other=null;
        try { other=members.current(driver?request.passengerId:request.driverId); } catch (ApiException failure) { if (failure.status!=401) throw failure; }
        Integer ahead=null; Long remaining=null; long remainingValidForMillis=0;
        if (!driver && state.equals("QUEUED")) {
            List<RideRequest> all=requests.findByDriverIdOrderByCreatedAtAscIdAsc(request.driverId);
            ahead=(int)all.stream().filter(r -> state(r).equals("ACTIVE") || state(r).equals("QUEUED") && r.queueOrder<request.queueOrder).count();
            if (ahead==0) {
                boolean legacyActive=links.findByMemberIdOrderByLinkedAtDesc(request.driverId).stream().anyMatch(link ->
                    pickups.findByVehicleId(link.vehicleId).stream().anyMatch(p -> !p.isTerminal() && !p.isExpired(clock.instant())
                        && all.stream().noneMatch(r -> p.id.equals(r.pickupId))));
                if (legacyActive) ahead=1;
            }
            // Remaining time is for the CURRENT pickup, never an ETA to this waiting passenger.
            for (RideRequest active:all) if (state(active).equals("ACTIVE")) {
                Pickup p=pickups.findById(active.pickupId).orElse(null);
                if (p!=null && p.vehicleSourceTime!=null && p.vehicleSourceTime.plus(vehicleFreshness).isAfter(clock.instant())
                        && p.vehicleReceivedAt!=null && p.vehicleReceivedAt.plus(vehicleFreshness).isAfter(clock.instant())) {
                    remaining=p.durationSeconds;
                    java.time.Instant sourceExpiry=p.vehicleSourceTime.plus(vehicleFreshness);
                    java.time.Instant receivedExpiry=p.vehicleReceivedAt.plus(vehicleFreshness);
                    remainingValidForMillis=Math.max(0,Duration.between(clock.instant(),sourceExpiry.isBefore(receivedExpiry)?sourceExpiry:receivedExpiry).toMillis());
                }
            }
        }
        return new RideRequestApi.View(request.id,state,other==null?"연결이 종료된 회원":other.nickname,other==null?null:other.majungId,place(request),request.expiresAt,ahead,remaining,remainingValidForMillis,request.driverInvitation);
    }
    private PickupApi.Place place(RideRequest r) { return new PickupApi.Place(r.placeLabel,r.placeLatitude,r.placeLongitude); }
    private static ApiException notFound() { return error(404,"RIDE_REQUEST_NOT_FOUND","마중 요청을 찾을 수 없어요."); }
    private static ApiException conflict(String message) { return error(409,"RIDE_REQUEST_STATE",message); }
    private static ApiException error(int status,String code,String message) { return new ApiException(status,code,message); }
}
