package app.majung.server.pickup.dev;

import app.majung.server.pickup.api.PickupApi;
import app.majung.server.pickup.api.RideRequestApi;
import app.majung.server.pickup.common.ApiException;
import app.majung.server.pickup.common.DeploymentPolicy;
import app.majung.server.pickup.domain.Member;
import app.majung.server.pickup.domain.MemberConnection;
import app.majung.server.pickup.domain.Pickup;
import app.majung.server.pickup.domain.RegisteredVehicle;
import app.majung.server.pickup.domain.RideRequest;
import app.majung.server.pickup.domain.VehicleAccountChallenge;
import app.majung.server.pickup.domain.VehicleAccountLink;
import app.majung.server.pickup.repository.MemberConnectionRepository;
import app.majung.server.pickup.repository.MemberRepository;
import app.majung.server.pickup.repository.PickupRepository;
import app.majung.server.pickup.repository.RegisteredVehicleRepository;
import app.majung.server.pickup.repository.RideRequestRepository;
import app.majung.server.pickup.repository.VehicleAccountChallengeRepository;
import app.majung.server.pickup.repository.VehicleAccountLinkRepository;
import app.majung.server.pickup.service.MemberService;
import app.majung.server.pickup.service.PickupService;
import app.majung.server.pickup.service.RideRequestService;
import app.majung.server.pickup.service.TokenService;
import app.majung.server.pickup.service.VehicleAccountService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local-only dummy data seeder and solo-test simulator backend.
 * Disabled whenever {@code majung.deployment=true} or {@code DeploymentPolicy#developmentToolsEnabled()} is false.
 */
@Service
@ConditionalOnProperty(name = "majung.deployment", havingValue = "false", matchIfMissing = true)
public class DevDummySeedService {
    public static final String DUMMY_PROVIDER = "dev-dummy";
    public static final UUID DEMO_SELF_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    public record DummyMemberSpec(UUID id, String majungId, String nickname, UUID vehicleId, String vehicleName) {
        public boolean isDriver() { return vehicleId != null; }
    }

    public record GuestInviteInfo(UUID pickupId, String inviteToken, String placeLabel, Instant expiresAt) { }

    public record VehicleChallengeInfo(UUID challengeId, UUID vehicleId, String vehicleName, String code, Instant expiresAt, String claimantMajungId) { }

    public static final List<DummyMemberSpec> DUMMY_SPECS = List.of(
            new DummyMemberSpec(DEMO_SELF_ID, "MJ-DEMO001", "송승준",
                    UUID.fromString("20000000-0000-4000-8000-000000000000"), "내 테스트 아이오닉5 · 00하 0001"),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000002"), "MJ-DRV001", "김민준 아이오닉5",
                    UUID.fromString("20000000-0000-4000-8000-000000000001"), "흰색 아이오닉 5 · 서울 12가 3456"),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000003"), "MJ-DRV002", "박서연 EV6",
                    UUID.fromString("20000000-0000-4000-8000-000000000002"), "문스케이프 EV6 · 경기 34나 7890"),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000004"), "MJ-DRV003", "이도현 아반떼",
                    UUID.fromString("20000000-0000-4000-8000-000000000003"), "아반떼 N라인 · 서울 56다 1234"),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000005"), "MJ-DRV004", "정우진 그랜저",
                    UUID.fromString("20000000-0000-4000-8000-000000000004"), "그랜저 하이브리드 · 서울 78라 4321"),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000006"), "MJ-DRV005", "최수빈 카니발",
                    UUID.fromString("20000000-0000-4000-8000-000000000005"), "카니발 하이리무진 · 인천 90마 5678"),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000007"), "MJ-DRV006", "한지민 쏘렌토",
                    UUID.fromString("20000000-0000-4000-8000-000000000006"), "쏘렌토 하이브리드 · 경기 11바 2468"),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000008"), "MJ-DRV007", "오태석 아이오닉6",
                    UUID.fromString("20000000-0000-4000-8000-000000000007"), "아이오닉 6 · 서울 22사 1357"),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000009"), "MJ-DRV008", "강태오 모델3",
                    UUID.fromString("20000000-0000-4000-8000-000000000008"), "테슬라 모델 3 · 서울 88아 9988"),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000010"), "MJ-USR0001", "윤채원", null, null),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000011"), "MJ-USR0002", "송하준", null, null),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000012"), "MJ-USR0003", "임지우", null, null),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000013"), "MJ-USR0004", "배수지", null, null),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000014"), "MJ-USR0005", "노은비", null, null),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000015"), "MJ-USR0006", "조현우", null, null),
            new DummyMemberSpec(UUID.fromString("10000000-0000-4000-8000-000000000016"), "MJ-USR0007", "엄마 서민지", null, null)
    );

    private static final UUID SPARE_UNLINKED_VEHICLE_ID = UUID.fromString("20000000-0000-4000-8000-000000000099");
    private static final String SPARE_UNLINKED_VEHICLE_NAME = "전시용 쏘나타 · 서울 99차 0000";

    private final DeploymentPolicy deploymentPolicy;
    private final MemberRepository members;
    private final MemberConnectionRepository connections;
    private final RegisteredVehicleRepository vehicles;
    private final VehicleAccountLinkRepository links;
    private final VehicleAccountChallengeRepository challenges;
    private final RideRequestRepository rideRequests;
    private final PickupRepository pickups;
    private final PickupService pickupService;
    private final RideRequestService rideRequestService;
    private final VehicleAccountService vehicleAccountService;
    private final TokenService tokens;
    private final Clock clock;
    private final boolean seedOnStartup;

    private final Map<UUID, String> vehicleTokens = new ConcurrentHashMap<>();
    private final Map<UUID, String> pickupOwnerTokens = new ConcurrentHashMap<>();
    private final Map<UUID, String> guestInviteTokens = new ConcurrentHashMap<>();
    private final Map<UUID, String> challengeCodes = new ConcurrentHashMap<>();

    public DevDummySeedService(
            DeploymentPolicy deploymentPolicy,
            MemberRepository members,
            MemberConnectionRepository connections,
            RegisteredVehicleRepository vehicles,
            VehicleAccountLinkRepository links,
            VehicleAccountChallengeRepository challenges,
            RideRequestRepository rideRequests,
            PickupRepository pickups,
            PickupService pickupService,
            RideRequestService rideRequestService,
            VehicleAccountService vehicleAccountService,
            TokenService tokens,
            Clock clock,
            @Value("${majung.dummy-data.enabled:false}") boolean seedOnStartup) {
        this.deploymentPolicy = deploymentPolicy;
        this.members = members;
        this.connections = connections;
        this.vehicles = vehicles;
        this.links = links;
        this.challenges = challenges;
        this.rideRequests = rideRequests;
        this.pickups = pickups;
        this.pickupService = pickupService;
        this.rideRequestService = rideRequestService;
        this.vehicleAccountService = vehicleAccountService;
        this.tokens = tokens;
        this.clock = clock;
        this.seedOnStartup = seedOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedOnStartup() {
        if (!seedOnStartup || !deploymentPolicy.developmentToolsEnabled()) {
            return;
        }
        seedIfNeeded();
    }

    @Transactional
    public void seedIfNeeded() {
        requireDev();
        if (members.existsById(DEMO_SELF_ID)) {
            restoreTokenCaches();
            return;
        }
        seedAllInternal();
    }

    @Transactional
    public void resetAllDummyData() {
        requireDev();
        deleteDummyData();
        seedAllInternal();
    }

    @Transactional
    public void injectScenarioFor(Member target) {
        requireDev();
        seedIfNeeded();
        if (target.id.equals(DEMO_SELF_ID)) {
            return;
        }
        ensureConnection(member(idx(1)), target, member(idx(1)), target, "ACCEPTED");
        ensureConnection(member(idx(2)), target, member(idx(2)), target, "ACCEPTED");
        ensureConnection(member(idx(9)), target, target, member(idx(9)), "ACCEPTED");
        ensureConnection(member(idx(3)), target, member(idx(3)), target, "PENDING");
        ensureConnection(member(idx(15)), target, member(idx(15)), target, "PENDING");

        List<RideRequest> existing = rideRequests.forMember(target.id);
        boolean hasInvite = existing.stream().anyMatch(r -> r.passengerId.equals(target.id) && "INVITED".equals(r.status));
        if (!hasInvite) {
            saveRideRequest(UUID.randomUUID(), DUMMY_SPECS.get(2).id(), target.id, DUMMY_SPECS.get(2).vehicleId(),
                    null, "INVITED", true, new PickupApi.Place("판교역 2번 출구", 37.3947, 127.1112), null);
        }
        boolean hasActive = existing.stream().anyMatch(r -> r.passengerId.equals(target.id) && "ACTIVE".equals(r.status));
        if (!hasActive) {
            Pickup active = createLivePickup(DUMMY_SPECS.get(5).vehicleId(), target.nickname,
                    new PickupApi.Place("광교중앙역 1번 출구", 37.2886, 127.0516), 37.2820, 127.0460, 240L,
                    Pickup.Readiness.PREPARING, "곧 출발할게요!");
            saveRideRequest(UUID.randomUUID(), DUMMY_SPECS.get(5).id(), target.id, DUMMY_SPECS.get(5).vehicleId(),
                    active.id, "ACTIVE", false, new PickupApi.Place("광교중앙역 1번 출구", 37.2886, 127.0516), null);
        }
    }

    public List<DummyMemberSpec> specs() {
        return DUMMY_SPECS;
    }

    public Map<UUID, String> vehicleTokensView() { return Map.copyOf(vehicleTokens); }

    public GuestInviteInfo latestGuestInvite() {
        Instant now = clock.instant();
        for (Map.Entry<UUID, String> entry : guestInviteTokens.entrySet()) {
            Pickup pickup = pickups.findById(entry.getKey()).orElse(null);
            if (pickup != null && pickup.status == Pickup.Status.INVITING && pickup.inviteExpiresAt != null
                    && now.isBefore(pickup.inviteExpiresAt)) {
                return new GuestInviteInfo(pickup.id, entry.getValue(), pickup.placeLabel, pickup.inviteExpiresAt);
            }
        }
        return null;
    }

    @Transactional
    public GuestInviteInfo createGuestInvite(PickupApi.Place place) {
        requireDev();
        seedIfNeeded();
        PickupApi.Place targetPlace = place != null ? place : new PickupApi.Place("의왕역 1호선 1번 출구 앞", 37.3208, 126.9482);
        String vehicleAuth = bearerForVehicle(DUMMY_SPECS.get(8).vehicleId());
        PickupApi.Created created = pickupService.createPickup(targetPlace, vehicleAuth);
        pickupOwnerTokens.put(created.pickupId(), created.driverToken());
        guestInviteTokens.put(created.pickupId(), created.inviteToken());
        return new GuestInviteInfo(created.pickupId(), created.inviteToken(), targetPlace.label(), created.inviteExpiresAt());
    }

    @Transactional
    public VehicleChallengeInfo createOrRefreshVehicleQr() {
        requireDev();
        seedIfNeeded();
        String auth = bearerForVehicle(SPARE_UNLINKED_VEHICLE_ID);
        if (links.existsById(SPARE_UNLINKED_VEHICLE_ID)) {
            links.deleteById(SPARE_UNLINKED_VEHICLE_ID);
            challenges.deleteById(SPARE_UNLINKED_VEHICLE_ID);
        }
        var challenge = vehicleAccountService.issue(auth);
        challengeCodes.put(challenge.challengeId(), challenge.code());
        return new VehicleChallengeInfo(challenge.challengeId(), SPARE_UNLINKED_VEHICLE_ID,
                SPARE_UNLINKED_VEHICLE_NAME, challenge.code(), challenge.expiresAt(), null);
    }

    public VehicleChallengeInfo currentVehicleQr() {
        VehicleAccountChallenge challenge = challenges.findById(SPARE_UNLINKED_VEHICLE_ID).orElse(null);
        if (challenge == null || !clock.instant().isBefore(challenge.expiresAt)) {
            return null;
        }
        String code = challengeCodes.get(challenge.challengeId);
        if (code == null) {
            return null;
        }
        return new VehicleChallengeInfo(challenge.challengeId, SPARE_UNLINKED_VEHICLE_ID,
                SPARE_UNLINKED_VEHICLE_NAME, code, challenge.expiresAt,
                challenge.claimant == null ? null : challenge.claimant.majungId);
    }

    @Transactional
    public void confirmVehicleQr(UUID challengeId) {
        requireDev();
        String auth = bearerForVehicle(SPARE_UNLINKED_VEHICLE_ID);
        vehicleAccountService.confirm(auth, challengeId);
    }

    @Transactional
    public RideRequestApi.View submitRideRequestAsDummy(UUID passengerId, UUID driverId, PickupApi.Place place) {
        requireDev();
        seedIfNeeded();
        return rideRequestService.submit(passengerId, new RideRequestApi.Submit(driverId, place, UUID.randomUUID()));
    }

    @Transactional
    public Object driverAction(UUID requestId, String action) {
        requireDev();
        RideRequest req = rideRequests.findById(requestId)
                .orElseThrow(() -> new ApiException(404, "RIDE_REQUEST_NOT_FOUND", "마중 요청을 찾을 수 없어요."));
        UUID vehicleId = req.vehicleId != null ? req.vehicleId : firstVehicleIdForDriver(req.driverId);
        String vehicleAuth = bearerForVehicle(vehicleId);
        return switch (action) {
            case "accept" -> rideRequestService.accept(vehicleAuth, requestId);
            case "reject" -> rideRequestService.reject(vehicleAuth, requestId);
            case "start" -> {
                RideRequestApi.Started started = rideRequestService.start(vehicleAuth, requestId);
                pickupOwnerTokens.put(started.pickupId(), started.driverToken());
                Pickup p = pickups.findById(started.pickupId()).orElse(null);
                if (p != null) {
                    pickupService.updateVehicle(started.pickupId(), "Bearer " + started.driverToken(),
                            new PickupApi.VehicleRequest(p.placeLatitude - 0.008, p.placeLongitude - 0.006,
                                    300L, clock.instant(), p.vehicleSequence + 1, p.placeVersion));
                }
                yield started;
            }
            case "arrive" -> {
                UUID pid = requirePickupId(req);
                yield pickupService.arrivePickup(pid, bearerForPickup(pid));
            }
            case "complete" -> {
                UUID pid = requirePickupId(req);
                String ownerAuth = bearerForPickup(pid);
                Pickup p = pickups.findById(pid).orElseThrow();
                if (p.status == Pickup.Status.ACTIVE) {
                    pickupService.arrivePickup(pid, ownerAuth, p.placeVersion);
                }
                yield pickupService.endPickup(pid, ownerAuth, Pickup.Status.COMPLETED);
            }
            case "cancel" -> {
                if (req.pickupId != null) {
                    yield pickupService.endPickup(req.pickupId, bearerForPickup(req.pickupId), Pickup.Status.CANCELLED);
                }
                if (req.driverInvitation) {
                    yield rideRequestService.cancelInvitation(vehicleAuth, requestId);
                }
                yield rideRequestService.cancel(req.passengerId, requestId);
            }
            default -> throw new ApiException(400, "INVALID_ACTION", "지원하지 않는 동작이에요.");
        };
    }

    @Transactional
    public Object vehicleTick(UUID pickupId, String action) {
        requireDev();
        String ownerAuth = bearerForPickup(pickupId);
        Pickup pickup = pickups.findById(pickupId)
                .orElseThrow(() -> new ApiException(404, "PICKUP_NOT_FOUND", "픽업을 찾을 수 없어요."));
        Instant now = clock.instant();
        return switch (action) {
            case "approach" -> {
                double curLat = pickup.vehicleLatitude != null ? pickup.vehicleLatitude : pickup.placeLatitude - 0.010;
                double curLng = pickup.vehicleLongitude != null ? pickup.vehicleLongitude : pickup.placeLongitude - 0.010;
                double nextLat = curLat + (pickup.placeLatitude - curLat) * 0.35;
                double nextLng = curLng + (pickup.placeLongitude - curLng) * 0.35;
                long nextDur = pickup.durationSeconds != null ? Math.max(20L, pickup.durationSeconds - 60L) : 180L;
                yield pickupService.updateVehicle(pickupId, ownerAuth,
                        new PickupApi.VehicleRequest(nextLat, nextLng, nextDur, now, pickup.vehicleSequence + 1, pickup.placeVersion));
            }
            case "arrive" -> {
                if (pickup.status == Pickup.Status.ACTIVE) {
                    pickupService.updateVehicle(pickupId, ownerAuth,
                            new PickupApi.VehicleRequest(pickup.placeLatitude, pickup.placeLongitude, 0L,
                                    now, pickup.vehicleSequence + 1, pickup.placeVersion));
                }
                yield pickupService.arrivePickup(pickupId, ownerAuth, pickup.placeVersion);
            }
            case "accept-proposal" -> {
                if (pickup.proposalId == null) {
                    throw new ApiException(409, "NO_PROPOSAL", "대기 중인 장소 변경 제안이 없어요.");
                }
                PickupApi.Place confirmed = new PickupApi.Place(pickup.proposalLabel, pickup.proposalLatitude, pickup.proposalLongitude);
                PickupApi.Snapshot snap = pickupService.confirmPlace(pickupId, ownerAuth,
                        new PickupApi.PlaceConfirmation(pickup.proposalId, pickup.placeVersion, confirmed, 210L, now));
                pickupService.updateVehicle(pickupId, ownerAuth,
                        new PickupApi.VehicleRequest(confirmed.latitude() - 0.005, confirmed.longitude() - 0.004,
                                210L, now, snap.placeVersion() + 10, snap.placeVersion()));
                yield snap;
            }
            case "complete" -> {
                if (pickup.status == Pickup.Status.ACTIVE) {
                    pickupService.arrivePickup(pickupId, ownerAuth, pickup.placeVersion);
                }
                yield pickupService.endPickup(pickupId, ownerAuth, Pickup.Status.COMPLETED);
            }
            default -> throw new ApiException(400, "INVALID_ACTION", "지원하지 않는 차량 시뮬레이션이에요.");
        };
    }

    @Transactional
    public PickupApi.Snapshot passengerAction(UUID pickupId, Pickup.Readiness readiness, String quickMessage, PickupApi.Place proposePlace) {
        requireDev();
        Pickup pickup = pickups.findById(pickupId)
                .orElseThrow(() -> new ApiException(404, "PICKUP_NOT_FOUND", "픽업을 찾을 수 없어요."));
        PickupApi.Snapshot result = null;
        if (readiness != null) {
            result = pickupService.readinessForAccount(pickupId,
                    new PickupApi.ReadinessRequest(readiness, pickup.readinessSequence + 1));
        }
        if (quickMessage != null && !quickMessage.isBlank()) {
            result = pickupService.sendQuickMessageForAccount(pickupId, quickMessage.strip());
        }
        if (proposePlace != null) {
            result = pickupService.proposePlaceForAccount(pickupId, proposePlace);
        }
        if (result == null) {
            throw new ApiException(400, "NO_ACTION", "실행할 탑승자 동작을 선택해주세요.");
        }
        return result;
    }

    private void seedAllInternal() {
        Instant now = clock.instant();
        List<Member> createdMembers = new ArrayList<>();
        for (int i = 0; i < DUMMY_SPECS.size(); i++) {
            DummyMemberSpec spec = DUMMY_SPECS.get(i);
            Member m = new Member();
            m.id = spec.id();
            m.provider = DUMMY_PROVIDER;
            m.providerSubject = "dummy-" + (i + 1);
            m.majungId = spec.majungId();
            m.nickname = spec.nickname();
            m.normalizedNickname = spec.nickname().toLowerCase(Locale.ROOT);
            m.profileReady = true;
            m.createdAt = now.minusSeconds(3600L * (20 - i));
            createdMembers.add(members.saveAndFlush(m));

            if (spec.vehicleId() != null) {
                RegisteredVehicle vehicle = createVehicle(spec.vehicleId(), spec.vehicleName(), now.minusSeconds(7200L));
                VehicleAccountLink link = new VehicleAccountLink();
                link.vehicleId = vehicle.id;
                link.member = m;
                link.linkedAt = now.minusSeconds(3600L);
                links.saveAndFlush(link);
            }
        }

        // Unlinked vehicle with active QR challenge ready for testing vehicle QR pairing
        createVehicle(SPARE_UNLINKED_VEHICLE_ID, SPARE_UNLINKED_VEHICLE_NAME, now.minusSeconds(7200L));
        VehicleAccountChallenge spareChallenge = new VehicleAccountChallenge();
        spareChallenge.vehicleId = SPARE_UNLINKED_VEHICLE_ID;
        spareChallenge.challengeId = UUID.fromString("30000000-0000-4000-8000-000000000099");
        String spareCode = deterministicToken("dev-spare-vehicle-qr");
        spareChallenge.codeHash = tokens.hash(spareCode);
        spareChallenge.expiresAt = now.plus(Duration.ofMinutes(30));
        challenges.saveAndFlush(spareChallenge);
        challengeCodes.put(spareChallenge.challengeId, spareCode);

        Member self = createdMembers.get(0); // 송승준
        Member minjun = createdMembers.get(1);
        Member seoyeon = createdMembers.get(2);
        Member dohyun = createdMembers.get(3);
        Member woojin = createdMembers.get(4);
        Member subin = createdMembers.get(5);
        Member jimin = createdMembers.get(6);
        Member taeo = createdMembers.get(8);
        Member chaewon = createdMembers.get(9);
        Member hajun = createdMembers.get(10);
        Member jiwoo = createdMembers.get(11);
        Member suji = createdMembers.get(12);
        Member minji = createdMembers.get(15);

        // Diverse friend connections for default demo user
        ensureConnection(self, minjun, self, minjun, "ACCEPTED");
        ensureConnection(self, seoyeon, seoyeon, self, "ACCEPTED");
        ensureConnection(self, chaewon, self, chaewon, "ACCEPTED");
        ensureConnection(self, hajun, hajun, self, "ACCEPTED");
        ensureConnection(self, dohyun, dohyun, self, "PENDING");  // INCOMING
        ensureConnection(self, jiwoo, jiwoo, self, "PENDING");    // INCOMING
        ensureConnection(self, minji, minji, self, "PENDING");    // INCOMING
        ensureConnection(self, woojin, self, woojin, "PENDING");  // OUTGOING
        ensureConnection(self, suji, self, suji, "PENDING");      // OUTGOING

        // Extra driver-side connections for Kim Minjun
        ensureConnection(minjun, chaewon, minjun, chaewon, "ACCEPTED");
        ensureConnection(minjun, jiwoo, minjun, jiwoo, "ACCEPTED");

        // 1) Active pickup for self (driver: 최수빈 카니발)
        PickupApi.Place gwanggyo = new PickupApi.Place("광교중앙역 1번 출구", 37.2886, 127.0516);
        Pickup activeSelfPickup = createLivePickup(subinVehicleId(), self.nickname, gwanggyo,
                37.2820, 127.0460, 240L, Pickup.Readiness.PREPARING, "잠시만요, 1번 출구로 가고 있어요!");
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000001"), subin.id, self.id, subinVehicleId(),
                activeSelfPickup.id, "ACTIVE", false, gwanggyo, null);

        // 2) Driver invitations to self
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000002"), seoyeon.id, self.id,
                DUMMY_SPECS.get(2).vehicleId(), null, "INVITED", true,
                new PickupApi.Place("판교역 2번 출구", 37.3947, 127.1112), null);
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000003"), jimin.id, self.id,
                DUMMY_SPECS.get(6).vehicleId(), null, "INVITED", true,
                new PickupApi.Place("정자역 3번 출구", 37.3670, 127.1081), null);

        // 3) Pending passenger request from self
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000004"), dohyun.id, self.id,
                DUMMY_SPECS.get(3).vehicleId(), null, "PENDING", false,
                new PickupApi.Place("강남역 11번 출구", 37.4990, 127.0272), null);

        // 4) Queue on Kim Minjun's vehicle: Chaewon ACTIVE (ahead) -> self QUEUED
        PickupApi.Place yatap = new PickupApi.Place("야탑역 1번 출구", 37.4114, 127.1286);
        Pickup aheadPickup = createLivePickup(minjunVehicleId(), chaewon.nickname, yatap,
                37.4050, 127.1210, 360L, Pickup.Readiness.READY, "지금 1번 출구 앞이에요!");
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000005"), minjun.id, chaewon.id,
                minjunVehicleId(), aheadPickup.id, "ACTIVE", false, yatap, 1L);
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000006"), minjun.id, self.id,
                minjunVehicleId(), null, "QUEUED", false,
                new PickupApi.Place("수원역 환승센터", 37.2660, 127.0005), 2L);

        // 5) Incoming requests on self's own vehicle (for testing Driver / Vehicle Emulator solo)
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000007"), self.id, hajun.id,
                DUMMY_SPECS.get(0).vehicleId(), null, "QUEUED", false,
                new PickupApi.Place("양재역 9번 출구", 37.4843, 127.0346), 1L);
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000008"), self.id, jiwoo.id,
                null, null, "PENDING", false,
                new PickupApi.Place("선릉역 1번 출구", 37.5045, 127.0489), null);
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000009"), self.id, suji.id,
                null, null, "PENDING", false,
                new PickupApi.Place("삼성역 6번 출구", 37.5088, 127.0631), null);

        // 6) Standalone guest invitation pickup ready for #join testing
        String guestInviteToken = deterministicToken("dev-guest-invite-1");
        String guestOwnerToken = deterministicToken("dev-guest-owner-1");
        Pickup guestPickup = Pickup.create(tokens.hash(guestOwnerToken), tokens.hash(guestInviteToken),
                now.minusSeconds(120), now.plus(Duration.ofMinutes(30)), now.plus(Duration.ofHours(6)),
                new PickupApi.Place("의왕역 1호선 1번 출구 앞", 37.3208, 126.9482));
        guestPickup.id = UUID.fromString("50000000-0000-4000-8000-000000000001");
        guestPickup.vehicleId = DUMMY_SPECS.get(8).vehicleId(); // 강태오 모델3
        pickups.saveAndFlush(guestPickup);
        pickupOwnerTokens.put(guestPickup.id, guestOwnerToken);
        guestInviteTokens.put(guestPickup.id, guestInviteToken);
    }

    private void restoreTokenCaches() {
        for (DummyMemberSpec spec : DUMMY_SPECS) {
            if (spec.vehicleId() != null) {
                vehicleTokens.putIfAbsent(spec.vehicleId(), deterministicToken("veh-" + spec.vehicleId()));
            }
        }
        vehicleTokens.putIfAbsent(SPARE_UNLINKED_VEHICLE_ID, deterministicToken("veh-" + SPARE_UNLINKED_VEHICLE_ID));
        UUID spareChallengeId = UUID.fromString("30000000-0000-4000-8000-000000000099");
        challenges.findByChallengeId(spareChallengeId).ifPresent(c ->
                challengeCodes.putIfAbsent(c.challengeId, deterministicToken("dev-spare-vehicle-qr")));
        UUID guestId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        pickups.findById(guestId).ifPresent(p -> {
            pickupOwnerTokens.putIfAbsent(p.id, deterministicToken("dev-guest-owner-1"));
            if (p.status == Pickup.Status.INVITING && p.inviteHash != null) {
                guestInviteTokens.putIfAbsent(p.id, deterministicToken("dev-guest-invite-1"));
            }
        });
        for (Pickup p : pickups.findAll()) {
            if (p.vehicleId != null && DUMMY_SPECS.stream().anyMatch(s -> p.vehicleId.equals(s.vehicleId()))) {
                String maybeOwner = deterministicToken("pickup-owner-" + p.id);
                if (tokens.matches(maybeOwner, p.ownerHash)) {
                    pickupOwnerTokens.putIfAbsent(p.id, maybeOwner);
                }
            }
        }
    }

    private void deleteDummyData() {
        List<UUID> dummyMemberIds = members.findAll().stream()
                .filter(m -> DUMMY_PROVIDER.equals(m.provider)).map(m -> m.id).toList();
        List<UUID> dummyVehicleIds = new ArrayList<>();
        DUMMY_SPECS.stream().map(DummyMemberSpec::vehicleId).filter(java.util.Objects::nonNull).forEach(dummyVehicleIds::add);
        dummyVehicleIds.add(SPARE_UNLINKED_VEHICLE_ID);
        java.util.Set<UUID> dummyMemberSet = new java.util.HashSet<>(dummyMemberIds);
        java.util.Set<UUID> dummyVehicleSet = new java.util.HashSet<>(dummyVehicleIds);

        List<Pickup> dummyPickups = pickups.findAll().stream()
                .filter(p -> p.vehicleId != null && dummyVehicleSet.contains(p.vehicleId))
                .toList();
        java.util.Set<UUID> dummyPickupSet = dummyPickups.stream().map(p -> p.id).collect(java.util.stream.Collectors.toSet());

        List<RideRequest> dummyRequests = rideRequests.findAll().stream()
                .filter(r -> dummyMemberSet.contains(r.driverId)
                        || dummyMemberSet.contains(r.passengerId)
                        || (r.vehicleId != null && dummyVehicleSet.contains(r.vehicleId))
                        || (r.pickupId != null && dummyPickupSet.contains(r.pickupId)))
                .toList();
        rideRequests.deleteAll(dummyRequests);
        rideRequests.flush();

        for (UUID mid : dummyMemberIds) {
            connections.deleteForMember(mid);
            links.deleteForMember(mid);
        }
        connections.flush();
        links.flush();
        pickups.deleteAll(dummyPickups);
        pickups.flush();

        for (UUID vid : dummyVehicleIds) {
            challenges.deleteById(vid);
            links.deleteById(vid);
        }
        challenges.flush();
        links.flush();

        for (UUID vid : dummyVehicleIds) {
            vehicles.deleteById(vid);
        }
        vehicles.flush();

        members.deleteAllById(dummyMemberIds);
        members.flush();
        vehicleTokens.clear();
        pickupOwnerTokens.clear();
        guestInviteTokens.clear();
        challengeCodes.clear();
    }

    private RegisteredVehicle createVehicle(UUID id, String displayName, Instant registeredAt) {
        String rawToken = deterministicToken("veh-" + id);
        RegisteredVehicle v = new RegisteredVehicle();
        v.id = id;
        v.credentialHash = tokens.hash(rawToken);
        v.displayName = displayName;
        v.registeredAt = registeredAt;
        vehicleTokens.put(id, rawToken);
        return vehicles.saveAndFlush(v);
    }

    private Pickup createLivePickup(UUID vehicleId, String participantName, PickupApi.Place place,
                                    double carLat, double carLng, long durationSeconds,
                                    Pickup.Readiness readiness, String quickMessage) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String ownerToken = deterministicToken("pickup-owner-" + id);
        String guestToken = tokens.issue();
        Pickup p = Pickup.create(tokens.hash(ownerToken), null, now.minusSeconds(300),
                now.minusSeconds(60), now.plus(Duration.ofHours(6)), place);
        p.id = id;
        p.vehicleId = vehicleId;
        p.status = Pickup.Status.ACTIVE;
        p.participantId = UUID.randomUUID();
        p.participantName = participantName;
        p.participantHash = tokens.hash(guestToken);
        p.readiness = readiness;
        p.readinessSequence = 1;
        p.readinessReceivedSequence = 1;
        p.vehicleSequence = 1;
        p.vehicleLatitude = carLat;
        p.vehicleLongitude = carLng;
        p.durationSeconds = durationSeconds;
        p.vehicleSourceTime = now;
        p.vehicleReceivedAt = now;
        p.lastVehicleActivityAt = now;
        p.quickMessage = quickMessage;
        p.quickMessageAt = now.minusSeconds(30);
        pickupOwnerTokens.put(id, ownerToken);
        return pickups.saveAndFlush(p);
    }

    private void saveRideRequest(UUID id, UUID driverId, UUID passengerId, UUID vehicleId, UUID pickupId,
                                 String status, boolean driverInvitation, PickupApi.Place place, Long queueOrder) {
        Instant now = clock.instant();
        RideRequest r = new RideRequest();
        r.id = id;
        r.driverId = driverId;
        r.passengerId = passengerId;
        r.vehicleId = vehicleId;
        r.pickupId = pickupId;
        r.status = status;
        r.driverInvitation = driverInvitation;
        r.placeLabel = place.label();
        r.placeLatitude = place.latitude();
        r.placeLongitude = place.longitude();
        r.createdAt = now.minusSeconds(180);
        r.expiresAt = now.plus(Duration.ofHours(2));
        r.queueOrder = queueOrder;
        rideRequests.saveAndFlush(r);
    }

    private void ensureConnection(Member a, Member b, Member requester, Member recipient, String status) {
        UUID first = a.id.compareTo(b.id) < 0 ? a.id : b.id;
        UUID second = first.equals(a.id) ? b.id : a.id;
        if (connections.findByFirstMemberAndSecondMember(first, second).isPresent()) {
            return;
        }
        Instant now = clock.instant();
        MemberConnection c = new MemberConnection();
        c.id = UUID.randomUUID();
        c.firstMember = first;
        c.secondMember = second;
        c.requester = requester.id;
        c.recipient = recipient.id;
        c.status = status;
        c.createdAt = now.minusSeconds(600);
        c.updatedAt = now.minusSeconds(300);
        connections.saveAndFlush(c);
    }

    private UUID firstVehicleIdForDriver(UUID driverId) {
        return links.findByMemberIdOrderByLinkedAtDesc(driverId).stream()
                .map(l -> l.vehicleId).findFirst()
                .orElseThrow(() -> new ApiException(409, "DRIVER_UNAVAILABLE", "연결된 차량이 없어요."));
    }

    private UUID requirePickupId(RideRequest req) {
        if (req.pickupId == null) {
            throw new ApiException(409, "PICKUP_NOT_STARTED", "아직 픽업이 시작되지 않았어요.");
        }
        return req.pickupId;
    }

    private String bearerForVehicle(UUID vehicleId) {
        String token = vehicleTokens.get(vehicleId);
        if (token == null) {
            throw new ApiException(409, "DEV_VEHICLE_TOKEN_MISSING", "개발용 차량 토큰이 없습니다. 데이터 리셋 후 다시 시도해주세요.");
        }
        return "Bearer " + token;
    }

    private String bearerForPickup(UUID pickupId) {
        String token = pickupOwnerTokens.computeIfAbsent(pickupId, id -> {
            Pickup p = pickups.findById(id).orElse(null);
            if (p == null) return null;
            String fresh = tokens.issue();
            p.ownerHash = tokens.hash(fresh);
            pickups.saveAndFlush(p);
            return fresh;
        });
        if (token == null) {
            throw new ApiException(404, "PICKUP_NOT_FOUND", "픽업을 찾을 수 없어요.");
        }
        return "Bearer " + token;
    }

    private Member member(UUID id) {
        return members.findById(id).orElseThrow();
    }

    private static UUID idx(int i) {
        return DUMMY_SPECS.get(i).id();
    }

    private static UUID minjunVehicleId() { return DUMMY_SPECS.get(1).vehicleId(); }
    private static UUID subinVehicleId() { return DUMMY_SPECS.get(5).vehicleId(); }

    public static String deterministicToken(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireDev() {
        if (!deploymentPolicy.developmentToolsEnabled()) {
            throw new ApiException(404, "NOT_FOUND", "찾을 수 없어요.");
        }
    }
}
