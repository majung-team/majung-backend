package app.majung.server.pickup.dev;

import app.majung.server.pickup.api.PickupApi;
import app.majung.server.pickup.api.RideRequestApi;
import app.majung.server.pickup.api.VehicleRegistrationApi;
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
import app.majung.server.pickup.service.ArrivalPushService;
import app.majung.server.pickup.service.PickupService;
import app.majung.server.pickup.service.RideRequestService;
import app.majung.server.pickup.service.TokenService;
import app.majung.server.pickup.service.VehicleAccountService;
import app.majung.server.pickup.service.VehicleCodeGenerator;
import app.majung.server.pickup.service.VehicleRegistrationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Diverse Korean dummy data seeder and operator/sandbox simulation service.
 *
 * <p>Dummy accounts &amp; linked dummy vehicles can safely live in both local dev and production
 * (with HMAC-SHA256 tokens keyed by {@code MAJUNG_ADMIN_KEY} so no credential is predictable).
 * Public dev login/unauthenticated sandbox remain strictly disabled in deployment; production
 * simulation runs exclusively through {@link OpsConsoleApi}.
 */
@Service
public class DevDummySeedService {
    private static final Logger log = LoggerFactory.getLogger(DevDummySeedService.class);
    public static final String DUMMY_PROVIDER = "dev-dummy";
    public static final UUID DEMO_SELF_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    public static final UUID SPARE_UNLINKED_VEHICLE_ID = UUID.fromString("20000000-0000-4000-8000-000000000099");
    private static final String SPARE_UNLINKED_VEHICLE_NAME = "전시용 쏘나타 · 서울 99차 0000";

    public record DummyMemberSpec(UUID id, String majungId, String nickname, UUID vehicleId, String vehicleName) {
        public boolean isDriver() { return vehicleId != null; }
    }

    public record GuestInviteInfo(UUID pickupId, String inviteToken, String placeLabel, Instant expiresAt) { }

    public record VehicleChallengeInfo(UUID challengeId, UUID vehicleId, String vehicleName, String code,
                                       Instant expiresAt, String claimantMajungId) { }

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

    private static final Set<UUID> BUILTIN_DUMMY_VEHICLE_IDS;

    static {
        Set<UUID> vids = new HashSet<>();
        DUMMY_SPECS.stream().map(DummyMemberSpec::vehicleId).filter(Objects::nonNull).forEach(vids::add);
        vids.add(SPARE_UNLINKED_VEHICLE_ID);
        BUILTIN_DUMMY_VEHICLE_IDS = Set.copyOf(vids);
    }

    private final DeploymentPolicy deploymentPolicy;
    private final MemberRepository members;
    private final MemberConnectionRepository connections;
    private final RegisteredVehicleRepository vehicles;
    private final VehicleAccountLinkRepository links;
    private final VehicleAccountChallengeRepository challenges;
    private final RideRequestRepository rideRequests;
    private final PickupRepository pickups;
    private final ArrivalPushService arrivalPush;
    private final PickupService pickupService;
    private final RideRequestService rideRequestService;
    private final VehicleAccountService vehicleAccountService;
    private final VehicleRegistrationService vehicleRegistrationService;
    private final TokenService tokens;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final boolean seedOnStartup;
    private final boolean deployment;
    private final String adminKey;
    private final SecureRandom random = new SecureRandom();

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
            ArrivalPushService arrivalPush,
            PickupService pickupService,
            RideRequestService rideRequestService,
            VehicleAccountService vehicleAccountService,
            VehicleRegistrationService vehicleRegistrationService,
            TokenService tokens,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${majung.dummy-data.enabled:false}") boolean seedOnStartup,
            @Value("${majung.deployment:false}") boolean deployment,
            @Value("${majung.admin-key:${majung.creation-key:}}") String adminKey) {
        this.deploymentPolicy = deploymentPolicy;
        this.members = members;
        this.connections = connections;
        this.vehicles = vehicles;
        this.links = links;
        this.challenges = challenges;
        this.rideRequests = rideRequests;
        this.pickups = pickups;
        this.arrivalPush = arrivalPush;
        this.pickupService = pickupService;
        this.rideRequestService = rideRequestService;
        this.vehicleAccountService = vehicleAccountService;
        this.vehicleRegistrationService = vehicleRegistrationService;
        this.tokens = tokens;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.seedOnStartup = seedOnStartup;
        this.deployment = deployment;
        this.adminKey = adminKey == null ? "" : adminKey;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        if (!seedOnStartup) {
            return;
        }
        try {
            if (!deployment && deploymentPolicy.developmentToolsEnabled()) {
                transaction.executeWithoutResult(status -> seedIfNeeded());
            } else {
                seedCatalogSafely();
            }
        } catch (RuntimeException exception) {
            // Another blue-green slot may concurrently seed shared PostgreSQL on boot.
            log.warn("Dummy startup seed skipped or deferred: {}", exception.getClass().getSimpleName());
        }
    }

    public void seedCatalogSafely() {
        Instant now = clock.instant();
        for (int i = 0; i < DUMMY_SPECS.size(); i++) {
            DummyMemberSpec spec = DUMMY_SPECS.get(i);
            final int index = i;
            try {
                transaction.executeWithoutResult(status -> ensureDummyMemberAndVehicle(spec, index, now));
            } catch (DataIntegrityViolationException ignored) {
                // Concurrent slot already inserted this record cleanly.
            }
        }
        try {
            transaction.executeWithoutResult(status -> ensureSpareVehicleAndChallenge(now));
        } catch (DataIntegrityViolationException ignored) {
        }
        restoreTokenCaches();
    }

    private void ensureDummyMemberAndVehicle(DummyMemberSpec spec, int index, Instant now) {
        Member m = members.findById(spec.id()).orElseGet(() -> {
            Member created = new Member();
            created.id = spec.id();
            created.provider = DUMMY_PROVIDER;
            created.providerSubject = spec.majungId();
            created.majungId = spec.majungId();
            created.nickname = spec.nickname();
            created.normalizedNickname = spec.nickname().toLowerCase(Locale.ROOT);
            created.profileReady = true;
            created.createdAt = now.minusSeconds(3600L - (index * 60L));
            return members.saveAndFlush(created);
        });

        if (spec.vehicleId() != null) {
            RegisteredVehicle v = vehicles.findById(spec.vehicleId()).orElseGet(() ->
                    createVehicle(spec.vehicleId(), spec.vehicleName(), now.minusSeconds(3600L - (index * 60L))));
            if (!links.existsById(v.id)) {
                VehicleAccountLink link = new VehicleAccountLink();
                link.vehicleId = v.id;
                link.member = m;
                link.linkedAt = now.minusSeconds(1800);
                links.saveAndFlush(link);
            }
        }
    }

    private void ensureSpareVehicleAndChallenge(Instant now) {
        RegisteredVehicle spare = vehicles.findById(SPARE_UNLINKED_VEHICLE_ID)
                .orElseGet(() -> createVehicle(SPARE_UNLINKED_VEHICLE_ID, SPARE_UNLINKED_VEHICLE_NAME, now.minusSeconds(900)));
        if (!links.existsById(spare.id) && challenges.findById(spare.id).isEmpty()) {
            VehicleAccountChallenge spareChallenge = new VehicleAccountChallenge();
            spareChallenge.vehicleId = spare.id;
            spareChallenge.challengeId = UUID.fromString("30000000-0000-4000-8000-000000000099");
            String spareCode = secretToken("dev-spare-vehicle-qr");
            spareChallenge.codeHash = tokens.hash(spareCode);
            spareChallenge.expiresAt = now.plus(Duration.ofMinutes(30));
            challenges.saveAndFlush(spareChallenge);
            challengeCodes.put(spareChallenge.challengeId, spareCode);
        }
    }

    @Transactional
    public void seedIfNeeded() {
        if (members.existsById(DEMO_SELF_ID)) {
            restoreTokenCaches();
            return;
        }
        seedAllInternal();
    }

    @Transactional
    public void resetAllDummyData(boolean withSamplePickups) {
        deleteDummyData();
        if (withSamplePickups) {
            seedAllInternal();
        } else {
            Instant now = clock.instant();
            for (int i = 0; i < DUMMY_SPECS.size(); i++) {
                ensureDummyMemberAndVehicle(DUMMY_SPECS.get(i), i, now);
            }
            ensureSpareVehicleAndChallenge(now);
            restoreTokenCaches();
        }
    }

    @Transactional
    public void resetAllDummyData() {
        resetAllDummyData(true);
    }

    @Transactional
    public Member createCustomDummy(String rawNickname, String rawVehicleName) {
        String normalized = Normalizer.normalize((rawNickname == null ? "" : rawNickname).strip(), Normalizer.Form.NFKC);
        if (!normalized.matches("[\\p{L}\\p{N} _.-]{2,20}")) {
            throw new ApiException(400, "NICKNAME_INVALID", "닉네임은 문자·숫자·공백·밑줄·점·하이픈으로 2~20자 입력해주세요.");
        }
        String vehicleName = rawVehicleName == null ? "" : rawVehicleName.strip();
        if (vehicleName.length() > 32) {
            throw new ApiException(400, "VEHICLE_NAME_INVALID", "차량 이름은 32자 이하로 입력해주세요.");
        }

        byte[] idBytes = new byte[4];
        random.nextBytes(idBytes);
        String suffix = HexFormat.of().formatHex(idBytes).toUpperCase(Locale.ROOT);
        boolean isDriver = !vehicleName.isEmpty();

        Member m = new Member();
        m.id = UUID.randomUUID();
        m.provider = DUMMY_PROVIDER;
        m.providerSubject = "CUSTOM-" + suffix;
        m.majungId = (isDriver ? "MJ-D" : "MJ-U") + suffix;
        m.nickname = normalized;
        m.normalizedNickname = normalized.toLowerCase(Locale.ROOT);
        m.profileReady = true;
        m.createdAt = clock.instant();
        members.saveAndFlush(m);

        if (isDriver) {
            RegisteredVehicle v = createVehicle(UUID.randomUUID(), vehicleName, clock.instant());
            VehicleAccountLink link = new VehicleAccountLink();
            link.vehicleId = v.id;
            link.member = m;
            link.linkedAt = clock.instant();
            links.saveAndFlush(link);
        }
        return m;
    }

    @Transactional
    public void injectScenarioFor(Member target) {
        seedIfNeeded();
        if (target.id.equals(DEMO_SELF_ID)) {
            return;
        }
        ensureConnection(member(idx(1)), target, member(idx(1)), target, "ACCEPTED");
        ensureConnection(member(idx(2)), target, member(idx(2)), target, "ACCEPTED");
        ensureConnection(member(idx(5)), target, member(idx(5)), target, "ACCEPTED");
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

    @Transactional
    public void sendConnectionFromDummy(UUID dummyId, UUID targetId, boolean accepted) {
        seedCatalogSafely();
        Member dummy = members.findById(dummyId)
                .filter(m -> DUMMY_PROVIDER.equals(m.provider))
                .orElseThrow(() -> new ApiException(400, "DUMMY_REQUIRED", "출발 주체는 더미 계정이어야 해요."));
        Member target = members.findById(targetId)
                .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "대상 회원을 찾을 수 없어요."));
        if (dummy.id.equals(target.id)) {
            throw new ApiException(400, "SELF_CONNECTION", "같은 계정은 연결할 수 없어요.");
        }
        UUID first = dummy.id.compareTo(target.id) < 0 ? dummy.id : target.id;
        UUID second = first.equals(dummy.id) ? target.id : dummy.id;
        MemberConnection existing = connections.findByFirstMemberAndSecondMember(first, second).orElse(null);
        if (existing != null) {
            if (accepted && !"ACCEPTED".equals(existing.status)) {
                existing.status = "ACCEPTED";
                existing.updatedAt = clock.instant();
                connections.saveAndFlush(existing);
            }
            return;
        }
        ensureConnection(dummy, target, dummy, target, accepted ? "ACCEPTED" : "PENDING");
    }

    @Transactional
    public RideRequestApi.View sendDriverInvitation(UUID dummyDriverId, UUID targetPassengerId, PickupApi.Place place) {
        seedCatalogSafely();
        Member driver = members.findById(dummyDriverId)
                .filter(m -> DUMMY_PROVIDER.equals(m.provider))
                .orElseThrow(() -> new ApiException(400, "DUMMY_DRIVER_REQUIRED", "더미 운전자 계정을 선택해주세요."));
        UUID vehicleId = firstVehicleIdForDriver(driver.id);
        Member passenger = members.findById(targetPassengerId)
                .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "대상 탑승자를 찾을 수 없어요."));
        if (driver.id.equals(passenger.id)) {
            throw new ApiException(400, "SELF_INVITE", "운전자와 탑승자가 같을 수 없어요.");
        }
        ensureConnection(driver, passenger, driver, passenger, "ACCEPTED");
        String vehicleAuth = bearerForVehicle(vehicleId);
        UUID first = driver.id.compareTo(passenger.id) < 0 ? driver.id : passenger.id;
        UUID second = first.equals(driver.id) ? passenger.id : driver.id;
        MemberConnection conn = connections.findByFirstMemberAndSecondMember(first, second).orElseThrow();
        return rideRequestService.invite(vehicleAuth, new RideRequestApi.Invite(conn.id, place, UUID.randomUUID()));
    }

    @Transactional
    public String quickAction(UUID targetId, String kind) {
        return quickAction(targetId, kind, null);
    }

    @Transactional
    public String quickAction(UUID targetId, String kind, PickupApi.Place overridePlace) {
        seedCatalogSafely();
        Member target = members.findById(targetId)
                .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "대상 회원을 찾을 수 없어요."));
        List<RideRequest> existing = rideRequests.forMember(target.id);

        return switch (kind) {
            case "auto-start-request" -> {
                RideRequest activeAlready = existing.stream()
                        .filter(r -> r.passengerId.equals(target.id) && "ACTIVE".equals(r.status))
                        .findFirst().orElse(null);
                if (activeAlready != null) {
                    yield "이미 진행 중인 픽업이 있습니다. 아래 픽업 제어 목록에서 차량을 이동·도착 처리하세요.";
                }
                RideRequest candidate = existing.stream()
                        .filter(r -> r.passengerId.equals(target.id)
                                && ("QUEUED".equals(r.status) || "PENDING".equals(r.status))
                                && members.findById(r.driverId).map(m -> DUMMY_PROVIDER.equals(m.provider)).orElse(false))
                        .sorted(java.util.Comparator.comparing((RideRequest r) -> r.createdAt != null ? r.createdAt : Instant.EPOCH).reversed())
                        .findFirst()
                        .orElseThrow(() -> new ApiException(409, "NO_PENDING_REQUEST",
                                "더미 운전자에게 보낸 대기 요청이 아직 없어요. 폰 앱에서 먼저 요청을 보내거나 '실시간 픽업 바로 생성'을 눌러주세요."));
                if ("PENDING".equals(candidate.status)) {
                    driverAction(candidate.id, "accept");
                }
                driverAction(candidate.id, "start");
                yield "방금 보낸 요청을 수락하고 차량 운행을 시작했습니다.";
            }
            case "send-invite" -> {
                DummyMemberSpec chosen = pickAvailableDummyDriver(target.id, existing);
                List<PickupApi.Place> places = List.of(
                        new PickupApi.Place("판교역 2번 출구", 37.3947, 127.1112),
                        new PickupApi.Place("강남역 2번 출구", 37.4979, 127.0276),
                        new PickupApi.Place("선릉역 1번 출구", 37.5045, 127.0489),
                        new PickupApi.Place("광교중앙역 1번 출구", 37.2886, 127.0516),
                        new PickupApi.Place("정자역 3번 출구", 37.3670, 127.1081)
                );
                long inviteCount = existing.stream().filter(r -> "INVITED".equals(r.status)).count();
                PickupApi.Place place = overridePlace != null ? overridePlace : places.get((int) (inviteCount % places.size()));
                sendDriverInvitation(chosen.id(), target.id, place);
                yield chosen.nickname() + " 운전자가 '" + place.label() + "' 초대를 보냈습니다.";
            }
            case "start-live-pickup" -> {
                boolean hasActive = existing.stream()
                        .anyMatch(r -> r.passengerId.equals(target.id) && "ACTIVE".equals(r.status));
                if (hasActive) {
                    yield "이미 진행 중인 픽업이 있습니다. 아래 픽업 제어 목록에서 이동·도착을 바로 실행할 수 있습니다.";
                }
                DummyMemberSpec chosen = pickAvailableDummyDriver(target.id, existing);
                PickupApi.Place place = overridePlace != null ? overridePlace : new PickupApi.Place("광교중앙역 1번 출구", 37.2886, 127.0516);
                ensureConnection(member(chosen.id()), target, member(chosen.id()), target, "ACCEPTED");
                Pickup active = createLivePickup(chosen.vehicleId(), target.nickname, place,
                        37.2820, 127.0460, 240L, Pickup.Readiness.PREPARING, "지금 1번 출구 쪽으로 가고 있어요!");
                saveRideRequest(UUID.randomUUID(), chosen.id(), target.id, chosen.vehicleId(),
                        active.id, "ACTIVE", false, place, null);
                yield chosen.nickname() + " 차량과의 실시간 픽업을 바로 시작했습니다.";
            }
            case "dummy-request-my-vehicle" -> {
                boolean hasVehicle = links.findByMemberIdOrderByLinkedAtDesc(target.id).stream().anyMatch(l ->
                        vehicles.findById(l.vehicleId).map(v -> v.revokedAt == null).orElse(false));
                if (!hasVehicle) {
                    throw new ApiException(409, "DRIVER_VEHICLE_REQUIRED",
                            "먼저 내 계정에 차량을 연결해주세요 (iPhone 앱 > 설정 > 차량 QR 연결로 에뮬레이터 QR 스캔).");
                }
                DummyMemberSpec dummy = pickAvailableDummyPassengerForDriver(target.id, existing);
                Member dummyMember = member(dummy.id());
                ensureConnection(dummyMember, target, dummyMember, target, "ACCEPTED");
                List<PickupApi.Place> places = List.of(
                        new PickupApi.Place("강남역 2번 출구", 37.4979, 127.0276),
                        new PickupApi.Place("판교역 2번 출구", 37.3947, 127.1112),
                        new PickupApi.Place("광교중앙역 1번 출구", 37.2886, 127.0516),
                        new PickupApi.Place("선릉역 1번 출구", 37.5045, 127.0489),
                        new PickupApi.Place("수원역 환승센터", 37.2660, 127.0005)
                );
                long reqCount = existing.stream().filter(r -> java.util.Objects.equals(r.driverId, target.id) && isOpenRequest(r)).count();
                PickupApi.Place place = overridePlace != null ? overridePlace : places.get((int) (reqCount % places.size()));
                rideRequestService.submit(dummyMember.id, new RideRequestApi.Submit(target.id, place, UUID.randomUUID()));
                yield dummyMember.nickname + " (" + dummyMember.majungId + ") 더미 탑승자가 내 차량으로 '" + place.label() + "' 마중 요청을 보냈습니다.";
            }
            case "connect-dummy-passenger" -> {
                DummyMemberSpec dummy = DUMMY_SPECS.stream()
                        .filter(s -> !s.id().equals(DEMO_SELF_ID) && !s.id().equals(target.id))
                        .filter(s -> {
                            UUID first = s.id().compareTo(target.id) < 0 ? s.id() : target.id;
                            UUID second = first.equals(s.id()) ? target.id : s.id();
                            return connections.findByFirstMemberAndSecondMember(first, second)
                                    .map(c -> !"ACCEPTED".equals(c.status))
                                    .orElse(true);
                        })
                        .sorted(Comparator.comparingInt(s -> s.isDriver() ? 1 : 0))
                        .findFirst()
                        .orElseThrow(() -> new ApiException(409, "ALL_CONNECTED", "모든 더미 탑승자가 이미 내 계정과 연결되어 있습니다."));
                Member dummyMember = member(dummy.id());
                ensureConnection(dummyMember, target, dummyMember, target, "ACCEPTED");
                yield dummyMember.nickname + " (" + dummyMember.majungId + ") 더미 탑승자와 지인 연결을 완료했습니다.";
            }
            case "send-friend-request" -> {
                Member dummy = members.findAll().stream()
                        .filter(m -> DUMMY_PROVIDER.equals(m.provider) && !m.id.equals(DEMO_SELF_ID) && !m.id.equals(target.id))
                        .filter(m -> {
                            UUID first = m.id.compareTo(target.id) < 0 ? m.id : target.id;
                            UUID second = first.equals(m.id) ? target.id : m.id;
                            return connections.findByFirstMemberAndSecondMember(first, second).isEmpty();
                        })
                        .findFirst()
                        .orElseThrow(() -> new ApiException(409, "ALL_CONNECTED", "모든 더미 계정과 이미 연결되어 있거나 요청 대기 중입니다."));
                ensureConnection(dummy, target, dummy, target, "PENDING");
                yield dummy.nickname + " (" + dummy.majungId + ") 계정이 지인 연결을 요청했습니다.";
            }
            default -> throw new ApiException(400, "INVALID_QUICK_ACTION", "지원하지 않는 빠른 테스트 동작이에요.");
        };
    }

    private boolean isOpenRequest(RideRequest r) {
        if ("PENDING".equals(r.status) || "INVITED".equals(r.status) || "QUEUED".equals(r.status)) {
            return r.expiresAt == null || clock.instant().isBefore(r.expiresAt);
        }
        if ("ACTIVE".equals(r.status)) {
            return r.pickupId != null && pickups.findById(r.pickupId)
                    .map(p -> !p.isTerminal() && !p.isExpired(clock.instant()))
                    .orElse(false);
        }
        return false;
    }

    private DummyMemberSpec pickAvailableDummyDriver(UUID targetId, List<RideRequest> existing) {
        Set<UUID> busyDrivers = new HashSet<>();
        for (RideRequest r : existing) {
            if (r.passengerId.equals(targetId) && isOpenRequest(r)) {
                busyDrivers.add(r.driverId);
            }
        }
        return DUMMY_SPECS.stream()
                .filter(s -> s.isDriver() && !s.id().equals(DEMO_SELF_ID) && !s.id().equals(targetId))
                .filter(s -> !busyDrivers.contains(s.id()))
                .filter(s -> pickups.findByVehicleId(s.vehicleId()).stream().allMatch(Pickup::isTerminal))
                .findFirst()
                .orElseThrow(() -> new ApiException(409, "NO_FREE_DRIVER",
                        "현재 대기 가능한 더미 운전자가 모두 사용 중입니다. 진행 중인 픽업을 완료하거나 초기화해주세요."));
    }

    private DummyMemberSpec pickAvailableDummyPassengerForDriver(UUID driverId, List<RideRequest> existing) {
        Set<UUID> busyPassengers = new HashSet<>();
        for (RideRequest r : existing) {
            if (java.util.Objects.equals(r.driverId, driverId) && isOpenRequest(r)) {
                busyPassengers.add(r.passengerId);
            }
        }
        return DUMMY_SPECS.stream()
                .filter(s -> !s.id().equals(DEMO_SELF_ID) && !s.id().equals(driverId))
                .filter(s -> !busyPassengers.contains(s.id()))
                .sorted(Comparator.comparingInt(s -> s.isDriver() ? 1 : 0))
                .findFirst()
                .orElseThrow(() -> new ApiException(409, "NO_FREE_PASSENGER",
                        "모든 더미 탑승자가 이미 내 차량에 대기 요청을 보냈습니다. 요청을 처리하거나 초기화해주세요."));
    }

    public List<DummyMemberSpec> specs() {
        return DUMMY_SPECS;
    }

    public boolean isDummyVehicle(UUID vehicleId) {
        if (vehicleId == null) return false;
        if (BUILTIN_DUMMY_VEHICLE_IDS.contains(vehicleId)) return true;
        return links.findById(vehicleId)
                .map(l -> DUMMY_PROVIDER.equals(l.member.provider))
                .orElse(false);
    }

    public GuestInviteInfo latestGuestInvite() {
        Instant now = clock.instant();
        for (Map.Entry<UUID, String> entry : guestInviteTokens.entrySet()) {
            Pickup pickup = pickups.findById(entry.getKey()).orElse(null);
            if (pickup != null && pickup.status == Pickup.Status.INVITING && pickup.inviteExpiresAt != null
                    && now.isBefore(pickup.inviteExpiresAt)) {
                return new GuestInviteInfo(pickup.id, entry.getValue(), pickup.placeLabel, pickup.inviteExpiresAt);
            }
        }
        UUID defaultGuestId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        Pickup defaultGuest = pickups.findById(defaultGuestId).orElse(null);
        if (defaultGuest != null && defaultGuest.status == Pickup.Status.INVITING
                && defaultGuest.inviteExpiresAt != null && now.isBefore(defaultGuest.inviteExpiresAt)) {
            String tok = secretToken("dev-guest-invite-1");
            if (defaultGuest.inviteHash != null && tokens.matches(tok, defaultGuest.inviteHash)) {
                guestInviteTokens.putIfAbsent(defaultGuest.id, tok);
                return new GuestInviteInfo(defaultGuest.id, tok, defaultGuest.placeLabel, defaultGuest.inviteExpiresAt);
            }
        }
        return null;
    }

    @Transactional
    public GuestInviteInfo createGuestInvite(PickupApi.Place place) {
        seedCatalogSafely();
        PickupApi.Place targetPlace = place != null ? place : new PickupApi.Place("의왕역 1호선 1번 출구 앞", 37.3208, 126.9482);
        String vehicleAuth = bearerForVehicle(DUMMY_SPECS.get(8).vehicleId());
        PickupApi.Created created = pickupService.createPickup(targetPlace, vehicleAuth);
        pickupOwnerTokens.put(created.pickupId(), created.driverToken());
        guestInviteTokens.put(created.pickupId(), created.inviteToken());
        return new GuestInviteInfo(created.pickupId(), created.inviteToken(), targetPlace.label(), created.inviteExpiresAt());
    }

    @Transactional
    public VehicleChallengeInfo createOrRefreshVehicleQr() {
        seedCatalogSafely();
        String auth = bearerForVehicle(SPARE_UNLINKED_VEHICLE_ID);
        if (links.existsById(SPARE_UNLINKED_VEHICLE_ID)) {
            links.deleteById(SPARE_UNLINKED_VEHICLE_ID);
            challenges.deleteById(SPARE_UNLINKED_VEHICLE_ID);
            links.flush();
            challenges.flush();
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
        if (code == null && challenge.challengeId.equals(UUID.fromString("30000000-0000-4000-8000-000000000099"))) {
            String candidate = secretToken("dev-spare-vehicle-qr");
            if (tokens.matches(candidate, challenge.codeHash)) {
                challengeCodes.put(challenge.challengeId, candidate);
                code = candidate;
            }
        }
        if (code == null) {
            return null;
        }
        return new VehicleChallengeInfo(challenge.challengeId, SPARE_UNLINKED_VEHICLE_ID,
                SPARE_UNLINKED_VEHICLE_NAME, code, challenge.expiresAt,
                challenge.claimant == null ? null : challenge.claimant.majungId);
    }

    @Transactional
    public void confirmVehicleQr(UUID challengeId) {
        String auth = bearerForVehicle(SPARE_UNLINKED_VEHICLE_ID);
        vehicleAccountService.confirm(auth, challengeId);
    }

    @Transactional
    public VehicleRegistrationApi.IssuedCode issueRealVehicleRegistrationCode() {
        return vehicleRegistrationService.issueCode();
    }

    @Transactional
    public RideRequestApi.View submitRideRequestAsDummy(UUID passengerId, UUID driverId, PickupApi.Place place) {
        seedCatalogSafely();
        Member passenger = members.findById(passengerId)
                .filter(m -> DUMMY_PROVIDER.equals(m.provider))
                .orElseThrow(() -> new ApiException(400, "DUMMY_PASSENGER_REQUIRED", "탑승자 대리 요청은 더미 계정에 대해서만 지원해요."));
        members.findById(driverId)
                .filter(driver -> !driver.id.equals(passenger.id))
                .ifPresent(driver -> ensureConnection(passenger, driver, passenger, driver, "ACCEPTED"));
        return rideRequestService.submit(passenger.id, new RideRequestApi.Submit(driverId, place, UUID.randomUUID()));
    }

    @Transactional
    public Object driverAction(UUID requestId, String action) {
        RideRequest req = rideRequests.findById(requestId)
                .orElseThrow(() -> new ApiException(404, "RIDE_REQUEST_NOT_FOUND", "마중 요청을 찾을 수 없어요."));
        Member driver = members.findById(req.driverId)
                .orElseThrow(() -> new ApiException(404, "DRIVER_NOT_FOUND", "운전자를 찾을 수 없어요."));
        if (!DUMMY_PROVIDER.equals(driver.provider)) {
            throw new ApiException(403, "REAL_DRIVER_PROTECTED", "실제 운전자 계정의 요청은 관리자 콘솔에서 대리 조작할 수 없어요.");
        }
        UUID vehicleId = req.vehicleId != null ? req.vehicleId : firstVehicleIdForDriver(req.driverId);
        requireDummyVehicle(vehicleId);
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
                Pickup p = pickups.findById(pid).orElseThrow();
                yield pickupService.arrivePickup(pid, bearerForPickup(pid), p.placeVersion);
            }
            case "complete" -> {
                UUID pid = requirePickupId(req);
                String ownerAuth = bearerForPickup(pid);
                Pickup p = pickups.findById(pid).orElseThrow();
                if (p.status == Pickup.Status.ACTIVE) {
                    pickupService.arrivePickup(pid, ownerAuth, p.placeVersion);
                }
                PickupApi.Snapshot done = pickupService.endPickup(pid, ownerAuth, Pickup.Status.COMPLETED);
                req.status = "COMPLETED";
                req.endedAt = clock.instant();
                rideRequests.save(req);
                yield done;
            }
            case "cancel" -> {
                if (req.pickupId != null) {
                    PickupApi.Snapshot cancelled = pickupService.endPickup(req.pickupId, bearerForPickup(req.pickupId), Pickup.Status.CANCELLED);
                    req.status = "CANCELLED";
                    req.endedAt = clock.instant();
                    rideRequests.save(req);
                    yield cancelled;
                }
                if (req.driverInvitation) {
                    yield rideRequestService.cancelInvitation(vehicleAuth, requestId);
                }
                if (!DUMMY_PROVIDER.equals(members.findById(req.passengerId).map(m -> m.provider).orElse(""))) {
                    throw new ApiException(403, "REAL_PASSENGER_PROTECTED", "실제 탑승자가 보낸 대기 요청은 운전자가 거절로 응답해주세요.");
                }
                yield rideRequestService.cancel(req.passengerId, requestId);
            }
            default -> throw new ApiException(400, "INVALID_ACTION", "지원하지 않는 동작이에요.");
        };
    }

    @Transactional
    public RideRequestApi.View passengerRequestAction(UUID requestId, String action) {
        RideRequest req = rideRequests.findById(requestId)
                .orElseThrow(() -> new ApiException(404, "RIDE_REQUEST_NOT_FOUND", "마중 요청을 찾을 수 없어요."));
        Member passenger = members.findById(req.passengerId)
                .orElseThrow(() -> new ApiException(404, "PASSENGER_NOT_FOUND", "탑승자를 찾을 수 없어요."));
        if (!DUMMY_PROVIDER.equals(passenger.provider)) {
            throw new ApiException(403, "REAL_PASSENGER_PROTECTED", "실제 탑승자 계정의 요청이나 초대는 관리자 콘솔에서 대리 조작할 수 없어요.");
        }
        return switch (action) {
            case "accept-invite" -> rideRequestService.answerInvitation(passenger.id, requestId, true);
            case "reject-invite" -> rideRequestService.answerInvitation(passenger.id, requestId, false);
            case "cancel" -> rideRequestService.cancel(passenger.id, requestId);
            default -> throw new ApiException(400, "INVALID_ACTION", "지원하지 않는 탑승자 요청 동작이에요.");
        };
    }

    @Transactional
    public Object vehicleTick(UUID pickupId, String action) {
        Pickup pickup = pickups.findById(pickupId)
                .orElseThrow(() -> new ApiException(404, "PICKUP_NOT_FOUND", "픽업을 찾을 수 없어요."));
        requireDummyVehicle(pickup.vehicleId);
        String ownerAuth = bearerForPickup(pickupId);
        Instant now = clock.instant();
        return switch (action) {
            case "approve-guest" -> {
                if (pickup.status != Pickup.Status.WAITING_APPROVAL || pickup.participantId == null) {
                    throw new ApiException(409, "NO_WAITING_GUEST", "승인 대기 중인 게스트가 없어요.");
                }
                PickupApi.Snapshot snap = pickupService.approveParticipant(pickupId, pickup.participantId, ownerAuth);
                pickupService.updateVehicle(pickupId, ownerAuth,
                        new PickupApi.VehicleRequest(pickup.placeLatitude - 0.008, pickup.placeLongitude - 0.006,
                                240L, now, pickup.vehicleSequence + 1, pickup.placeVersion));
                yield snap;
            }
            case "approach" -> {
                double curLat = pickup.vehicleLatitude != null ? pickup.vehicleLatitude : pickup.placeLatitude - 0.010;
                double curLng = pickup.vehicleLongitude != null ? pickup.vehicleLongitude : pickup.placeLongitude - 0.010;
                double nextLat = curLat + (pickup.placeLatitude - curLat) * 0.35;
                double nextLng = curLng + (pickup.placeLongitude - curLng) * 0.35;
                long nextDur = pickup.durationSeconds != null ? Math.max(20L, pickup.durationSeconds - 60L) : 180L;
                yield pickupService.updateVehicle(pickupId, ownerAuth,
                        new PickupApi.VehicleRequest(nextLat, nextLng, nextDur, now, pickup.vehicleSequence + 1, pickup.placeVersion));
            }
            case "receive-readiness" -> {
                if (pickup.participantId == null) {
                    throw new ApiException(409, "NO_PARTICIPANT", "참여 중인 탑승자가 없어요.");
                }
                yield pickupService.receiveReadiness(pickupId, ownerAuth,
                        new PickupApi.ReadinessReceipt(pickup.participantId, pickup.readinessSequence));
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
                                210L, now, pickup.vehicleSequence + 2, snap.placeVersion()));
                yield snap;
            }
            case "complete" -> {
                if (pickup.status == Pickup.Status.ACTIVE) {
                    pickupService.arrivePickup(pickupId, ownerAuth, pickup.placeVersion);
                }
                PickupApi.Snapshot done = pickupService.endPickup(pickupId, ownerAuth, Pickup.Status.COMPLETED);
                syncRequestTerminal(pickupId, "COMPLETED");
                yield done;
            }
            case "cancel" -> {
                PickupApi.Snapshot cancelled = pickupService.endPickup(pickupId, ownerAuth, Pickup.Status.CANCELLED);
                syncRequestTerminal(pickupId, "CANCELLED");
                yield cancelled;
            }
            default -> throw new ApiException(400, "INVALID_ACTION", "지원하지 않는 차량 시뮬레이션이에요.");
        };
    }

    @Transactional
    public PickupApi.Snapshot passengerAction(UUID pickupId, Pickup.Readiness readiness, String quickMessage,
                                              PickupApi.Place proposePlace, boolean devAllowedOnRealParticipant) {
        Pickup pickup = pickups.findById(pickupId)
                .orElseThrow(() -> new ApiException(404, "PICKUP_NOT_FOUND", "픽업을 찾을 수 없어요."));
        if (!devAllowedOnRealParticipant) {
            List<RideRequest> linked = rideRequests.findAll().stream()
                    .filter(r -> pickupId.equals(r.pickupId))
                    .toList();
            boolean isDummyPassenger = !linked.isEmpty() && linked.stream()
                    .allMatch(r -> DUMMY_PROVIDER.equals(members.findById(r.passengerId).map(m -> m.provider).orElse("")));
            if (!isDummyPassenger) {
                throw new ApiException(403, "REAL_PASSENGER_PROTECTED", "실제 탑승자의 픽업에서는 탑승자 행동을 대리 조작할 수 없어요.");
            }
        }
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
            throw new ApiException(400, "ACTION_REQUIRED", "준비 상태, 메시지 또는 장소 제안 중 하나 이상을 지정해주세요.");
        }
        return result;
    }

    private void syncRequestTerminal(UUID pickupId, String terminalStatus) {
        rideRequests.findAll().stream()
                .filter(r -> pickupId.equals(r.pickupId))
                .forEach(r -> {
                    r.status = terminalStatus;
                    r.endedAt = clock.instant();
                    rideRequests.save(r);
                });
    }

    private void seedAllInternal() {
        Instant now = clock.instant();
        List<Member> createdMembers = new ArrayList<>();
        for (int i = 0; i < DUMMY_SPECS.size(); i++) {
            DummyMemberSpec spec = DUMMY_SPECS.get(i);
            ensureDummyMemberAndVehicle(spec, i, now);
            createdMembers.add(members.findById(spec.id()).orElseThrow());
        }
        ensureSpareVehicleAndChallenge(now);

        Member self = createdMembers.get(0);
        Member minjun = createdMembers.get(1);
        Member seoyeon = createdMembers.get(2);
        Member dohyun = createdMembers.get(3);
        Member woojin = createdMembers.get(4);
        Member subin = createdMembers.get(5);
        Member jimin = createdMembers.get(6);
        Member chaewon = createdMembers.get(9);
        Member hajun = createdMembers.get(10);
        Member jiwoo = createdMembers.get(11);
        Member suji = createdMembers.get(12);
        Member minji = createdMembers.get(15);

        ensureConnection(self, minjun, self, minjun, "ACCEPTED");
        ensureConnection(self, seoyeon, seoyeon, self, "ACCEPTED");
        ensureConnection(self, chaewon, self, chaewon, "ACCEPTED");
        ensureConnection(self, hajun, hajun, self, "ACCEPTED");
        ensureConnection(self, dohyun, dohyun, self, "PENDING");
        ensureConnection(self, jiwoo, jiwoo, self, "PENDING");
        ensureConnection(self, minji, minji, self, "PENDING");
        ensureConnection(self, woojin, self, woojin, "PENDING");
        ensureConnection(self, suji, self, suji, "PENDING");

        ensureConnection(minjun, chaewon, minjun, chaewon, "ACCEPTED");
        ensureConnection(minjun, jiwoo, minjun, jiwoo, "ACCEPTED");

        PickupApi.Place gwanggyo = new PickupApi.Place("광교중앙역 1번 출구", 37.2886, 127.0516);
        Pickup activeSelfPickup = createLivePickup(subinVehicleId(), self.nickname, gwanggyo,
                37.2820, 127.0460, 240L, Pickup.Readiness.PREPARING, "잠시만요, 1번 출구로 가고 있어요!");
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000001"), subin.id, self.id, subinVehicleId(),
                activeSelfPickup.id, "ACTIVE", false, gwanggyo, null);

        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000002"), seoyeon.id, self.id,
                DUMMY_SPECS.get(2).vehicleId(), null, "INVITED", true,
                new PickupApi.Place("판교역 2번 출구", 37.3947, 127.1112), null);
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000003"), jimin.id, self.id,
                DUMMY_SPECS.get(6).vehicleId(), null, "INVITED", true,
                new PickupApi.Place("정자역 3번 출구", 37.3670, 127.1081), null);

        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000004"), dohyun.id, self.id,
                DUMMY_SPECS.get(3).vehicleId(), null, "PENDING", false,
                new PickupApi.Place("강남역 11번 출구", 37.4990, 127.0272), null);

        PickupApi.Place yatap = new PickupApi.Place("야탑역 1번 출구", 37.4114, 127.1286);
        Pickup aheadPickup = createLivePickup(minjunVehicleId(), chaewon.nickname, yatap,
                37.4050, 127.1210, 360L, Pickup.Readiness.READY, "지금 1번 출구 앞이에요!");
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000005"), minjun.id, chaewon.id,
                minjunVehicleId(), aheadPickup.id, "ACTIVE", false, yatap, 1L);
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000006"), minjun.id, self.id,
                minjunVehicleId(), null, "QUEUED", false,
                new PickupApi.Place("수원역 환승센터", 37.2660, 127.0005), 2L);

        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000007"), self.id, hajun.id,
                DUMMY_SPECS.get(0).vehicleId(), null, "QUEUED", false,
                new PickupApi.Place("양재역 9번 출구", 37.4843, 127.0346), 1L);
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000008"), self.id, jiwoo.id,
                null, null, "PENDING", false,
                new PickupApi.Place("선릉역 1번 출구", 37.5045, 127.0489), null);
        saveRideRequest(UUID.fromString("40000000-0000-4000-8000-000000000009"), self.id, suji.id,
                null, null, "PENDING", false,
                new PickupApi.Place("삼성역 6번 출구", 37.5088, 127.0631), null);

        String guestInviteToken = secretToken("dev-guest-invite-1");
        String guestOwnerToken = secretToken("dev-guest-owner-1");
        Pickup guestPickup = Pickup.create(tokens.hash(guestOwnerToken), tokens.hash(guestInviteToken),
                now.minusSeconds(120), now.plus(Duration.ofMinutes(30)), now.plus(Duration.ofHours(6)),
                new PickupApi.Place("의왕역 1호선 1번 출구 앞", 37.3208, 126.9482));
        guestPickup.id = UUID.fromString("50000000-0000-4000-8000-000000000001");
        guestPickup.vehicleId = DUMMY_SPECS.get(8).vehicleId();
        pickups.saveAndFlush(guestPickup);
        pickupOwnerTokens.put(guestPickup.id, guestOwnerToken);
        guestInviteTokens.put(guestPickup.id, guestInviteToken);
    }

    private void restoreTokenCaches() {
        for (DummyMemberSpec spec : DUMMY_SPECS) {
            if (spec.vehicleId() != null) {
                vehicleTokens.putIfAbsent(spec.vehicleId(), secretToken("veh-" + spec.vehicleId()));
            }
        }
        vehicleTokens.putIfAbsent(SPARE_UNLINKED_VEHICLE_ID, secretToken("veh-" + SPARE_UNLINKED_VEHICLE_ID));
        UUID spareChallengeId = UUID.fromString("30000000-0000-4000-8000-000000000099");
        challenges.findByChallengeId(spareChallengeId).ifPresent(c -> {
            String candidate = secretToken("dev-spare-vehicle-qr");
            if (tokens.matches(candidate, c.codeHash)) {
                challengeCodes.putIfAbsent(c.challengeId, candidate);
            }
        });
        UUID guestId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        pickups.findById(guestId).ifPresent(p -> {
            String candidateOwner = secretToken("dev-guest-owner-1");
            if (tokens.matches(candidateOwner, p.ownerHash)) {
                pickupOwnerTokens.putIfAbsent(p.id, candidateOwner);
            }
            if (p.status == Pickup.Status.INVITING && p.inviteHash != null) {
                String candidateInvite = secretToken("dev-guest-invite-1");
                if (tokens.matches(candidateInvite, p.inviteHash)) {
                    guestInviteTokens.putIfAbsent(p.id, candidateInvite);
                }
            }
        });
    }

    private void deleteDummyData() {
        List<UUID> dummyMemberIds = members.findAll().stream()
                .filter(m -> DUMMY_PROVIDER.equals(m.provider)).map(m -> m.id).toList();
        Set<UUID> dummyMemberSet = new HashSet<>(dummyMemberIds);

        Set<UUID> dummyVehicleSet = new HashSet<>(BUILTIN_DUMMY_VEHICLE_IDS);
        for (UUID mid : dummyMemberIds) {
            links.findByMemberIdOrderByLinkedAtDesc(mid).forEach(l -> dummyVehicleSet.add(l.vehicleId));
        }

        List<Pickup> dummyPickups = pickups.findAll().stream()
                .filter(p -> p.vehicleId != null && dummyVehicleSet.contains(p.vehicleId))
                .toList();
        Set<UUID> dummyPickupSet = new HashSet<>();
        for (Pickup p : dummyPickups) {
            dummyPickupSet.add(p.id);
        }

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
        }
        connections.flush();

        for (UUID pid : dummyPickupSet) {
            arrivalPush.remove(pid);
        }
        pickups.deleteAll(dummyPickups);
        pickups.flush();

        challenges.deleteAll(challenges.findAllById(dummyVehicleSet));
        challenges.flush();

        links.deleteAll(links.findAllById(dummyVehicleSet));
        links.flush();

        vehicles.deleteAll(vehicles.findAllById(dummyVehicleSet));
        vehicles.flush();

        members.deleteAll(members.findAllById(dummyMemberIds));
        members.flush();

        vehicleTokens.clear();
        pickupOwnerTokens.clear();
        guestInviteTokens.clear();
        challengeCodes.clear();
    }

    private RegisteredVehicle createVehicle(UUID id, String displayName, Instant registeredAt) {
        String rawToken = secretToken("veh-" + id);
        RegisteredVehicle v = new RegisteredVehicle();
        v.id = id;
        v.credentialHash = tokens.hash(rawToken);
        v.vehicleCode = VehicleCodeGenerator.nextUnique(vehicles);
        v.displayName = displayName;
        v.vehicleModel = displayName;
        v.directRequestEnabled = true;
        v.registeredAt = registeredAt;
        vehicleTokens.put(id, rawToken);
        return vehicles.saveAndFlush(v);
    }

    private Pickup createLivePickup(UUID vehicleId, String participantName, PickupApi.Place place,
                                    double carLat, double carLng, long durationSeconds,
                                    Pickup.Readiness readiness, String quickMessage) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        String ownerToken = secretToken("pickup-owner-" + id);
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
        MemberConnection existing = connections.findByFirstMemberAndSecondMember(first, second).orElse(null);
        if (existing != null) {
            if ("ACCEPTED".equals(status) && !"ACCEPTED".equals(existing.status)) {
                existing.status = "ACCEPTED";
                existing.updatedAt = clock.instant();
                connections.saveAndFlush(existing);
            }
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

    private void requireDummyVehicle(UUID vehicleId) {
        if (!isDummyVehicle(vehicleId)) {
            throw new ApiException(403, "REAL_VEHICLE_PROTECTED", "실제 등록 차량은 관리자 콘솔에서 대리 조작할 수 없어요.");
        }
    }

    private String bearerForVehicle(UUID vehicleId) {
        requireDummyVehicle(vehicleId);
        String token = vehicleTokens.computeIfAbsent(vehicleId, vid -> {
            RegisteredVehicle v = vehicles.findById(vid).orElse(null);
            if (v == null) return null;
            String deterministic = secretToken("veh-" + vid);
            if (tokens.matches(deterministic, v.credentialHash)) {
                return deterministic;
            }
            String rotated = tokens.issue();
            v.credentialHash = tokens.hash(rotated);
            vehicles.saveAndFlush(v);
            return rotated;
        });
        if (token == null) {
            throw new ApiException(404, "VEHICLE_NOT_FOUND", "더미 차량을 찾을 수 없어요.");
        }
        return "Bearer " + token;
    }

    private String bearerForPickup(UUID pickupId) {
        Pickup p = pickups.findById(pickupId)
                .orElseThrow(() -> new ApiException(404, "PICKUP_NOT_FOUND", "픽업을 찾을 수 없어요."));
        requireDummyVehicle(p.vehicleId);
        String token = pickupOwnerTokens.computeIfAbsent(pickupId, id -> {
            String candidate = secretToken("pickup-owner-" + id);
            if (p.ownerHash != null && tokens.matches(candidate, p.ownerHash)) {
                return candidate;
            }
            String fresh = tokens.issue();
            p.ownerHash = tokens.hash(fresh);
            pickups.saveAndFlush(p);
            return fresh;
        });
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

    public String secretToken(String seed) {
        try {
            byte[] digest;
            if (!adminKey.isEmpty()) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(("majung-dummy:" + adminKey).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                digest = mac.doFinal(seed.getBytes(StandardCharsets.UTF_8));
            } else {
                digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
