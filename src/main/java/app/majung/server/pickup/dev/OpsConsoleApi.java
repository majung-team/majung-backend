package app.majung.server.pickup.dev;

import app.majung.server.pickup.api.PickupApi;
import app.majung.server.pickup.api.RideRequestApi;
import app.majung.server.pickup.api.VehicleRegistrationApi;
import app.majung.server.pickup.common.ApiException;
import app.majung.server.pickup.domain.Member;
import app.majung.server.pickup.domain.Pickup;
import app.majung.server.pickup.domain.RegisteredVehicle;
import app.majung.server.pickup.domain.RideRequest;
import app.majung.server.pickup.domain.VehicleAccountLink;
import app.majung.server.pickup.repository.MemberRepository;
import app.majung.server.pickup.repository.PickupRepository;
import app.majung.server.pickup.repository.RegisteredVehicleRepository;
import app.majung.server.pickup.repository.RideRequestRepository;
import app.majung.server.pickup.repository.VehicleAccountLinkRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private Operations & Solo-Testing Admin Console mounted under {@code /api/v1/ops}.
 *
 * <p>Works on both local development ({@code http://127.0.0.1:4173/api/v1/ops}) and deployed
 * production ({@code https://majung.cloud/api/v1/ops}) behind Caddy's {@code /api/*} reverse proxy.
 * In production/deployed mode every read and mutation requires a verified HMAC session cookie + CSRF token
 * minted from {@code MAJUNG_ADMIN_KEY} (or {@code Authorization: Bearer <MAJUNG_ADMIN_KEY>}).
 */
@RestController
public class OpsConsoleApi {
    public record OpsSessionView(boolean authenticated, boolean authRequired, boolean deployment,
                                 String slot, String csrfToken) { }

    public record OpsLoginBody(String adminKey) { }

    public record OpsMemberItem(UUID memberId, String majungId, String nickname, boolean profileReady,
                                boolean dummy, boolean driver, UUID vehicleId, String vehicleName,
                                String vehicleCode, Instant createdAt) { }

    public record OpsVehicleItem(UUID vehicleId, String vehicleCode, String displayName,
                                 String vehicleModel, String licensePlate, String ownerHint,
                                 boolean dummy, boolean directRequestEnabled,
                                 boolean linked, UUID linkedMemberId, String linkedMemberMajungId,
                                 String linkedMemberNickname, boolean linkedMemberDummy,
                                 Instant registeredAt) { }

    public record OpsRequestItem(UUID requestId, String status, boolean controllable, boolean dummyPassenger,
                                 UUID driverId, String driverName, UUID passengerId, String passengerName,
                                 UUID vehicleId, String vehicleCode,
                                 String placeLabel, double placeLatitude, double placeLongitude, UUID pickupId,
                                 boolean driverInvitation, Long queueOrder, Instant createdAt, Instant expiresAt) { }

    public record OpsPickupItem(UUID pickupId, String status, boolean controllable, boolean dummyPassenger,
                                boolean guestControllable, UUID vehicleId, String vehicleCode,
                                String vehicleName, String participantName, String placeLabel,
                                double placeLatitude, double placeLongitude,
                                Double vehicleLatitude, Double vehicleLongitude, Long durationSeconds,
                                String readiness, long readinessSequence, long readinessReceivedSequence,
                                String quickMessage, String proposalLabel, String proposalBy,
                                long placeVersion, Instant createdAt, Instant expiresAt) { }

    public record OpsGuestInvite(UUID pickupId, String inviteToken, String link, String placeLabel, Instant expiresAt) { }

    public record OpsVehicleQr(UUID challengeId, UUID vehicleId, String vehicleName, String vehicleCode,
                               String code, String link, Instant expiresAt, String claimantMajungId) { }

    public record OpsStats(long totalMembers, long realMembers, long dummyMembers, long linkedVehicles,
                           long realVehicles, long openPickups, long openRequests) { }

    public record OpsState(boolean deployment, String slot, OpsStats stats,
                           List<OpsMemberItem> members, List<OpsVehicleItem> vehicles,
                           List<OpsRequestItem> requests, List<OpsPickupItem> pickups,
                           OpsGuestInvite guestInvite, OpsVehicleQr vehicleQr) { }

    public record ResetBody(Boolean withSamplePickups) { }

    public record CreateDummyBody(String nickname, String vehicleName) { }

    public record InjectBody(UUID memberId, String query) { }

    public record ConnectBody(UUID dummyId, UUID targetId, Boolean accept) { }

    public record PlaceTargetBody(UUID driverId, UUID passengerId, String placeLabel, Double latitude, Double longitude) { }

    public record RequestActionBody(UUID requestId, String action) { }

    public record PickupTickBody(UUID pickupId, String action) { }

    public record PassengerActionBody(UUID pickupId, Pickup.Readiness readiness, String quickMessage,
                                      PickupApi.Place proposePlace) { }

    public record GuestInviteBody(String placeLabel, Double latitude, Double longitude) { }

    public record ConfirmQrBody(UUID challengeId) { }

    public record QuickActionBody(UUID targetMemberId, String kind, String placeLabel, Double latitude, Double longitude) { }

    public record VehicleRequestBody(UUID vehicleId, String vehicleCode, UUID passengerId,
                                     String placeLabel, Double latitude, Double longitude) { }

    public record VehicleConnectBody(UUID vehicleId, String vehicleCode, UUID dummyId) { }

    public record ClaimQrBody(String link, UUID challengeId, String code, UUID memberId) { }

    public record GuestJoinBody(String link, UUID pickupId, String inviteToken, String displayName) { }

    public record QuickActionResult(String message, OpsState state) { }

    private final OpsSessionService opsSession;
    private final DevDummySeedService seedService;
    private final MemberRepository members;
    private final RegisteredVehicleRepository vehicles;
    private final VehicleAccountLinkRepository links;
    private final RideRequestRepository rideRequests;
    private final PickupRepository pickups;

    public OpsConsoleApi(OpsSessionService opsSession, DevDummySeedService seedService,
                         MemberRepository members, RegisteredVehicleRepository vehicles,
                         VehicleAccountLinkRepository links, RideRequestRepository rideRequests,
                         PickupRepository pickups) {
        this.opsSession = opsSession;
        this.seedService = seedService;
        this.members = members;
        this.vehicles = vehicles;
        this.links = links;
        this.rideRequests = rideRequests;
        this.pickups = pickups;
    }

    @GetMapping({"/api/v1/ops", "/api/v1/ops/"})
    public ResponseEntity<String> consoleHtml() throws IOException {
        return ResponseEntity.ok()
                .header("X-Robots-Tag", "noindex, nofollow")
                .contentType(MediaType.TEXT_HTML)
                .body(readAsset("ops/index.html"));
    }

    @GetMapping("/api/v1/ops/ops.css")
    public ResponseEntity<String> consoleCss() throws IOException {
        return ResponseEntity.ok()
                .header("X-Robots-Tag", "noindex, nofollow")
                .contentType(MediaType.valueOf("text/css;charset=UTF-8"))
                .body(readAsset("ops/ops.css"));
    }

    @GetMapping("/api/v1/ops/ops.js")
    public ResponseEntity<String> consoleJs() throws IOException {
        return ResponseEntity.ok()
                .header("X-Robots-Tag", "noindex, nofollow")
                .contentType(MediaType.valueOf("application/javascript;charset=UTF-8"))
                .body(readAsset("ops/ops.js"));
    }

    @GetMapping("/api/v1/ops/api/session")
    public OpsSessionView session(HttpServletRequest request, HttpServletResponse response) {
        OpsSessionService.SessionStatus status = opsSession.sessionStatus(request, response);
        return new OpsSessionView(status.authenticated(), status.authRequired(), opsSession.isDeployment(),
                currentSlot(), status.csrfToken());
    }

    @PostMapping("/api/v1/ops/api/login")
    public OpsSessionView login(@RequestBody(required = false) OpsLoginBody body,
                                HttpServletRequest request, HttpServletResponse response) {
        OpsSessionService.SessionStatus status = opsSession.login(body == null ? null : body.adminKey(), request, response);
        return new OpsSessionView(status.authenticated(), status.authRequired(), opsSession.isDeployment(),
                currentSlot(), status.csrfToken());
    }

    @PostMapping("/api/v1/ops/api/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        opsSession.logout(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/ops/api/state")
    public OpsState state(HttpServletRequest request) {
        opsSession.requireAuthorized(request, false);
        return buildState(request);
    }

    @PostMapping("/api/v1/ops/api/dummy/seed")
    public OpsState seedFull(@RequestBody(required = false) ResetBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        boolean withPickups = body == null || body.withSamplePickups() == null || body.withSamplePickups();
        if (withPickups) {
            seedService.resetAllDummyData(true);
        } else {
            seedService.seedCatalogSafely();
        }
        return buildState(request);
    }

    @PostMapping("/api/v1/ops/api/dummy/reset")
    public OpsState reset(@RequestBody(required = false) ResetBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        boolean withPickups = body == null || body.withSamplePickups() == null ? !opsSession.isDeployment() : body.withSamplePickups();
        seedService.resetAllDummyData(withPickups);
        return buildState(request);
    }

    @PostMapping("/api/v1/ops/api/dummy/create")
    public OpsMemberItem createDummy(@RequestBody CreateDummyBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || body.nickname() == null || body.nickname().isBlank()) {
            throw new ApiException(400, "NICKNAME_REQUIRED", "닉네임을 입력해주세요.");
        }
        Member created = seedService.createCustomDummy(body.nickname(), body.vehicleName());
        VehicleAccountLink link = links.findByMemberIdOrderByLinkedAtDesc(created.id).stream().findFirst().orElse(null);
        RegisteredVehicle v = link == null ? null : vehicles.findById(link.vehicleId).orElse(null);
        return new OpsMemberItem(created.id, created.majungId, created.nickname, created.profileReady, true,
                v != null, v == null ? null : v.id, v == null ? null : v.displayName,
                v == null ? null : v.vehicleCode, created.createdAt);
    }

    @PostMapping("/api/v1/ops/api/users/inject")
    public OpsState inject(@RequestBody(required = false) InjectBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        Member target = resolveTargetMember(body);
        seedService.injectScenarioFor(target);
        return buildState(request);
    }

    @PostMapping("/api/v1/ops/api/users/connect")
    public OpsState connect(@RequestBody ConnectBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || body.dummyId() == null || body.targetId() == null) {
            throw new ApiException(400, "INVALID_BODY", "더미 계정과 대상 계정을 모두 선택해주세요.");
        }
        seedService.sendConnectionFromDummy(body.dummyId(), body.targetId(), body.accept() == null || body.accept());
        return buildState(request);
    }

    @PostMapping("/api/v1/ops/api/users/invite")
    public RideRequestApi.View sendInvite(@RequestBody PlaceTargetBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || body.driverId() == null || body.passengerId() == null) {
            throw new ApiException(400, "INVALID_BODY", "운전자와 탑승자를 모두 선택해주세요.");
        }
        return seedService.sendDriverInvitation(body.driverId(), body.passengerId(), resolvePlace(body));
    }

    @PostMapping("/api/v1/ops/api/requests/submit")
    public RideRequestApi.View submitRequest(@RequestBody PlaceTargetBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || body.passengerId() == null || body.driverId() == null) {
            throw new ApiException(400, "INVALID_BODY", "탑승자와 운전자를 모두 선택해주세요.");
        }
        return seedService.submitRideRequestAsDummy(body.passengerId(), body.driverId(), resolvePlace(body));
    }

    @PostMapping("/api/v1/ops/api/requests/action")
    public Object requestAction(@RequestBody RequestActionBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || body.requestId() == null || body.action() == null) {
            throw new ApiException(400, "INVALID_BODY", "요청과 동작을 지정해주세요.");
        }
        return seedService.driverAction(body.requestId(), body.action());
    }

    @PostMapping("/api/v1/ops/api/requests/passenger-action")
    public RideRequestApi.View passengerRequestAction(@RequestBody RequestActionBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || body.requestId() == null || body.action() == null) {
            throw new ApiException(400, "INVALID_BODY", "요청과 동작을 지정해주세요.");
        }
        return seedService.passengerRequestAction(body.requestId(), body.action());
    }

    @PostMapping("/api/v1/ops/api/pickups/tick")
    public Object pickupTick(@RequestBody PickupTickBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || body.pickupId() == null || body.action() == null) {
            throw new ApiException(400, "INVALID_BODY", "픽업과 동작을 지정해주세요.");
        }
        return seedService.vehicleTick(body.pickupId(), body.action());
    }

    @PostMapping("/api/v1/ops/api/pickups/passenger-action")
    public PickupApi.Snapshot passengerAction(@RequestBody PassengerActionBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || body.pickupId() == null) {
            throw new ApiException(400, "INVALID_BODY", "픽업을 선택해주세요.");
        }
        return seedService.passengerAction(body.pickupId(), body.readiness(), body.quickMessage(), body.proposePlace(), false);
    }

    @PostMapping("/api/v1/ops/api/guest-invite")
    public OpsGuestInvite createGuestInvite(@RequestBody(required = false) GuestInviteBody body,
                                            HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        PickupApi.Place place = body != null && body.placeLabel() != null && !body.placeLabel().isBlank()
                && body.latitude() != null && body.longitude() != null
                ? new PickupApi.Place(body.placeLabel().strip(), body.latitude(), body.longitude()) : null;
        DevDummySeedService.GuestInviteInfo info = seedService.createGuestInvite(place);
        return toGuestView(info, request);
    }

    @PostMapping("/api/v1/ops/api/vehicle-qr")
    public OpsVehicleQr createVehicleQr(HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        DevDummySeedService.VehicleChallengeInfo qr = seedService.createOrRefreshVehicleQr();
        RegisteredVehicle v = vehicles.findById(qr.vehicleId()).orElse(null);
        return toQrView(qr, v == null ? Map.of() : Map.of(v.id, v), request);
    }

    @PostMapping("/api/v1/ops/api/vehicle-qr/confirm")
    public ResponseEntity<Void> confirmVehicleQr(@RequestBody ConfirmQrBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || body.challengeId() == null) {
            throw new ApiException(400, "INVALID_BODY", "차량 연결 요청 식별자를 지정해주세요.");
        }
        seedService.confirmVehicleQr(body.challengeId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/ops/api/vehicle-registration-codes")
    public VehicleRegistrationApi.IssuedCode issueVehicleRegistrationCode(HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        return seedService.issueRealVehicleRegistrationCode();
    }

    @PostMapping("/api/v1/ops/api/quick")
    public QuickActionResult quickAction(@RequestBody QuickActionBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || body.targetMemberId() == null || body.kind() == null || body.kind().isBlank()) {
            throw new ApiException(400, "INVALID_BODY", "대상 계정과 빠른 테스트 동작을 선택해주세요.");
        }
        PickupApi.Place overridePlace = body.placeLabel() != null && !body.placeLabel().isBlank()
                && body.latitude() != null && body.longitude() != null
                ? new PickupApi.Place(body.placeLabel().strip(), body.latitude(), body.longitude())
                : null;
        String message = seedService.quickAction(body.targetMemberId(), body.kind(), overridePlace);
        return new QuickActionResult(message, buildState(request));
    }

    @PostMapping("/api/v1/ops/api/vehicles/request")
    public QuickActionResult submitVehicleRequest(@RequestBody VehicleRequestBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || (body.vehicleId() == null && (body.vehicleCode() == null || body.vehicleCode().isBlank()))) {
            throw new ApiException(400, "VEHICLE_REQUIRED", "요청을 보낼 대상 차량을 선택하거나 차량 코드를 입력해주세요.");
        }
        PickupApi.Place overridePlace = body.placeLabel() != null && !body.placeLabel().isBlank()
                && body.latitude() != null && body.longitude() != null
                ? new PickupApi.Place(body.placeLabel().strip(), body.latitude(), body.longitude())
                : null;
        String message = seedService.submitRideRequestToVehicleAsDummy(
                body.vehicleId(), body.vehicleCode(), body.passengerId(), overridePlace);
        return new QuickActionResult(message, buildState(request));
    }

    @PostMapping("/api/v1/ops/api/vehicles/connect-dummy")
    public QuickActionResult connectDummyToVehicle(@RequestBody VehicleConnectBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null || (body.vehicleId() == null && (body.vehicleCode() == null || body.vehicleCode().isBlank()))) {
            throw new ApiException(400, "VEHICLE_REQUIRED", "대상 차량을 선택하거나 차량 코드를 입력해주세요.");
        }
        String message = seedService.connectDummyToVehicleDriver(body.vehicleId(), body.vehicleCode(), body.dummyId());
        return new QuickActionResult(message, buildState(request));
    }

    @PostMapping("/api/v1/ops/api/vehicle-qr/claim")
    public QuickActionResult claimVehicleQr(@RequestBody ClaimQrBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null) {
            throw new ApiException(400, "QR_LINK_REQUIRED", "차량 QR 링크를 입력해주세요.");
        }
        String message = seedService.claimVehicleQrAsDummy(body.link(), body.challengeId(), body.code(), body.memberId());
        return new QuickActionResult(message, buildState(request));
    }

    @PostMapping("/api/v1/ops/api/guest-join")
    public QuickActionResult joinGuestInvite(@RequestBody GuestJoinBody body, HttpServletRequest request) {
        opsSession.requireAuthorized(request, true);
        if (body == null) {
            throw new ApiException(400, "INVITE_LINK_REQUIRED", "게스트 초대 링크를 입력해주세요.");
        }
        String message = seedService.joinGuestPickupFromOps(body.link(), body.pickupId(), body.inviteToken(), body.displayName());
        return new QuickActionResult(message, buildState(request));
    }

    private OpsState buildState(HttpServletRequest request) {
        List<Member> allMembers = members.findAll();
        Map<UUID, Member> memberMap = allMembers.stream().collect(Collectors.toMap(m -> m.id, m -> m));
        List<RegisteredVehicle> allVehicles = vehicles.findAll();
        Map<UUID, RegisteredVehicle> vehicleMap = allVehicles.stream()
                .collect(Collectors.toMap(v -> v.id, v -> v));
        List<VehicleAccountLink> allLinks = links.findAll();
        Map<UUID, List<VehicleAccountLink>> linksByMember = allLinks.stream()
                .collect(Collectors.groupingBy(l -> l.member.id));
        Map<UUID, VehicleAccountLink> linkByVehicle = allLinks.stream()
                .collect(Collectors.toMap(l -> l.vehicleId, l -> l, (a, b) -> a));

        List<OpsMemberItem> memberItems = allMembers.stream()
                .sorted(Comparator.comparing((Member m) -> DevDummySeedService.DUMMY_PROVIDER.equals(m.provider) ? 1 : 0)
                        .thenComparing(m -> linksByMember.getOrDefault(m.id, List.of()).isEmpty() ? 1 : 0)
                        .thenComparing(m -> m.createdAt != null ? m.createdAt : Instant.EPOCH, Comparator.reverseOrder())
                        .thenComparing(m -> m.majungId))
                .map(m -> {
                    VehicleAccountLink firstLink = linksByMember.getOrDefault(m.id, List.of()).stream().findFirst().orElse(null);
                    RegisteredVehicle v = firstLink == null ? null : vehicleMap.get(firstLink.vehicleId);
                    return new OpsMemberItem(
                            m.id, m.majungId, m.nickname, m.profileReady,
                            DevDummySeedService.DUMMY_PROVIDER.equals(m.provider),
                            v != null,
                            v == null ? null : v.id,
                            v == null ? null : v.summaryDisplayName(),
                            v == null ? null : v.vehicleCode,
                            m.createdAt
                    );
                })
                .toList();

        List<OpsVehicleItem> vehicleItems = allVehicles.stream()
                .filter(v -> v.revokedAt == null)
                .sorted(Comparator.comparing((RegisteredVehicle v) -> seedService.isDummyVehicle(v.id) ? 1 : 0)
                        .thenComparing(v -> v.registeredAt != null ? v.registeredAt : Instant.EPOCH, Comparator.reverseOrder())
                        .thenComparing(v -> v.vehicleCode))
                .map(v -> {
                    boolean dummyVehicle = seedService.isDummyVehicle(v.id);
                    VehicleAccountLink link = linkByVehicle.get(v.id);
                    Member linkedMember = link == null ? null : memberMap.get(link.member.id);
                    return new OpsVehicleItem(
                            v.id, v.vehicleCode, v.summaryDisplayName(),
                            v.vehicleModel, v.licensePlate, v.ownerHint,
                            dummyVehicle, v.directRequestEnabled,
                            linkedMember != null,
                            linkedMember == null ? null : linkedMember.id,
                            linkedMember == null ? null : linkedMember.majungId,
                            linkedMember == null ? null : linkedMember.nickname,
                            linkedMember != null && DevDummySeedService.DUMMY_PROVIDER.equals(linkedMember.provider),
                            v.registeredAt
                    );
                })
                .toList();

        List<RideRequest> allRequests = rideRequests.findAll();
        List<OpsRequestItem> reqItems = allRequests.stream()
                .sorted(Comparator.comparingInt((RideRequest r) -> isOpenStatus(r.status) ? 0 : 1)
                        .thenComparing(r -> r.createdAt != null ? r.createdAt : Instant.EPOCH, Comparator.reverseOrder()))
                .limit(50)
                .map(r -> {
                    Member drv = r.driverId == null ? null : memberMap.get(r.driverId);
                    Member pas = memberMap.get(r.passengerId);
                    RegisteredVehicle rv = r.vehicleId == null ? null : vehicleMap.get(r.vehicleId);
                    boolean controllable = drv != null && DevDummySeedService.DUMMY_PROVIDER.equals(drv.provider)
                            && (rv == null || seedService.isDummyVehicle(rv.id));
                    boolean dummyPassenger = pas != null && DevDummySeedService.DUMMY_PROVIDER.equals(pas.provider);
                    String targetDisplay = drv != null
                            ? drv.nickname + " (" + drv.majungId + ")"
                            : (rv != null ? rv.summaryDisplayName() + " (" + rv.vehicleCode + ")" : "-");
                    return new OpsRequestItem(
                            r.id, r.status, controllable, dummyPassenger, r.driverId,
                            targetDisplay,
                            r.passengerId,
                            pas == null ? "-" : pas.nickname + " (" + pas.majungId + ")",
                            r.vehicleId,
                            rv == null ? null : rv.vehicleCode,
                            r.placeLabel, r.placeLatitude, r.placeLongitude,
                            r.pickupId, r.driverInvitation, r.queueOrder, r.createdAt, r.expiresAt
                    );
                })
                .toList();

        Map<UUID, RideRequest> requestByPickup = allRequests.stream()
                .filter(r -> r.pickupId != null)
                .collect(Collectors.toMap(r -> r.pickupId, r -> r, (a, b) -> a));

        List<Pickup> allPickups = pickups.findAll();
        List<OpsPickupItem> pickupItems = allPickups.stream()
                .sorted(Comparator.comparingInt((Pickup p) -> p.isTerminal() ? 1 : 0)
                        .thenComparingInt(p -> (p.status == Pickup.Status.ACTIVE || p.status == Pickup.Status.ARRIVED) ? 0 : 1)
                        .thenComparing(p -> p.createdAt != null ? p.createdAt : Instant.EPOCH, Comparator.reverseOrder()))
                .limit(30)
                .map(p -> {
                    RegisteredVehicle rv = p.vehicleId == null ? null : vehicleMap.get(p.vehicleId);
                    RideRequest linkedReq = requestByPickup.get(p.id);
                    Member passenger = linkedReq == null ? null : memberMap.get(linkedReq.passengerId);
                    boolean dummyPassenger = passenger != null && DevDummySeedService.DUMMY_PROVIDER.equals(passenger.provider);
                    boolean guestControllable = seedService.isOpsGuestPickup(p.id);
                    boolean controllable = seedService.isDummyVehicle(p.vehicleId);
                    return new OpsPickupItem(
                            p.id, p.status.name(), controllable, dummyPassenger, guestControllable,
                            p.vehicleId,
                            rv == null ? null : rv.vehicleCode,
                            rv == null ? "마중 차량" : rv.summaryDisplayName(),
                            p.participantName, p.placeLabel, p.placeLatitude, p.placeLongitude,
                            p.vehicleLatitude, p.vehicleLongitude, p.durationSeconds,
                            p.readiness == null ? null : p.readiness.name(),
                            p.readinessSequence, p.readinessReceivedSequence,
                            p.quickMessage, p.proposalLabel, p.proposalBy == null ? null : p.proposalBy.name(), p.placeVersion,
                            p.createdAt, p.expiresAt
                    );
                })
                .toList();

        long dummyCount = memberItems.stream().filter(OpsMemberItem::dummy).count();
        long realCount = memberItems.size() - dummyCount;
        long realVehicleCount = vehicleItems.stream().filter(v -> !v.dummy()).count();
        long openPickups = allPickups.stream().filter(p -> !p.isTerminal()).count();
        long openRequests = allRequests.stream().filter(r -> isOpenStatus(r.status)).count();

        OpsStats stats = new OpsStats(
                memberItems.size(), realCount, dummyCount, links.count(), realVehicleCount, openPickups, openRequests
        );

        return new OpsState(
                opsSession.isDeployment(),
                currentSlot(),
                stats,
                memberItems,
                vehicleItems,
                reqItems,
                pickupItems,
                toGuestView(seedService.latestGuestInvite(), request),
                toQrView(seedService.currentVehicleQr(), vehicleMap, request)
        );
    }

    private Member resolveTargetMember(InjectBody body) {
        if (body != null && body.memberId() != null) {
            return members.findById(body.memberId())
                    .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "대상 회원을 찾을 수 없어요."));
        }
        if (body != null && body.query() != null && !body.query().isBlank()) {
            String q = body.query().strip();
            return members.findAll().stream()
                    .filter(m -> m.majungId.equalsIgnoreCase(q) || m.nickname.equalsIgnoreCase(q))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "일치하는 마중 ID 또는 닉네임을 찾을 수 없어요."));
        }
        throw new ApiException(400, "TARGET_REQUIRED", "대상 회원을 선택하거나 마중 ID를 입력해주세요.");
    }

    private static PickupApi.Place resolvePlace(PlaceTargetBody body) {
        String label = body.placeLabel() == null || body.placeLabel().isBlank() ? "강남역 2번 출구" : body.placeLabel().strip();
        double lat = body.latitude() != null ? body.latitude() : 37.4979;
        double lng = body.longitude() != null ? body.longitude() : 127.0276;
        return new PickupApi.Place(label, lat, lng);
    }

    private static boolean isOpenStatus(String status) {
        return "PENDING".equals(status) || "INVITED".equals(status) || "QUEUED".equals(status) || "ACTIVE".equals(status);
    }

    private OpsGuestInvite toGuestView(DevDummySeedService.GuestInviteInfo info, HttpServletRequest request) {
        if (info == null) return null;
        String base = opsSession.isDeployment() ? "https://majung.cloud" : localOrigin(request);
        String link = base + "/#join/" + info.pickupId() + "/" + info.inviteToken();
        return new OpsGuestInvite(info.pickupId(), info.inviteToken(), link, info.placeLabel(), info.expiresAt());
    }

    private OpsVehicleQr toQrView(DevDummySeedService.VehicleChallengeInfo qr, Map<UUID, RegisteredVehicle> vehicleMap,
                                HttpServletRequest request) {
        if (qr == null) return null;
        String base = opsSession.isDeployment() ? "https://majung.cloud" : localOrigin(request);
        String link = base + "/#vehicle/" + qr.challengeId() + "/" + qr.code();
        RegisteredVehicle rv = vehicleMap.get(qr.vehicleId());
        String vCode = rv == null ? null : rv.vehicleCode;
        return new OpsVehicleQr(qr.challengeId(), qr.vehicleId(), qr.vehicleName(), vCode, qr.code(), link,
                qr.expiresAt(), qr.claimantMajungId());
    }

    private static String localOrigin(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }

    private static String currentSlot() {
        String slot = System.getenv("MAJUNG_SLOT");
        return slot == null || slot.isBlank() ? "standalone" : slot;
    }

    private static String readAsset(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
