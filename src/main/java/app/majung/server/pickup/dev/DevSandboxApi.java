package app.majung.server.pickup.dev;

import app.majung.server.pickup.api.AccountApi;
import app.majung.server.pickup.api.PickupApi;
import app.majung.server.pickup.api.RideRequestApi;
import app.majung.server.pickup.common.ApiException;
import app.majung.server.pickup.common.DeploymentPolicy;
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
import app.majung.server.pickup.security.AccountIdentity;
import app.majung.server.pickup.service.MemberService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private local sandbox/admin console and solo-test APIs.
 * Registered only when {@code majung.deployment=false} and enforces both
 * {@link DeploymentPolicy#developmentToolsEnabled()} and loopback client address on every call.
 */
@RestController
@ConditionalOnProperty(name = "majung.deployment", havingValue = "false", matchIfMissing = true)
public class DevSandboxApi {
    public record DevAccountItem(UUID memberId, String majungId, String nickname, boolean driver,
                                 UUID vehicleId, String vehicleName) { }

    public record DevRequestItem(UUID requestId, String status, UUID driverId, String driverNickname,
                                 UUID passengerId, String passengerNickname, String placeLabel,
                                 UUID pickupId, boolean driverInvitation, Long queueOrder) { }

    public record DevPickupItem(UUID pickupId, String status, String vehicleName, String participantName,
                                String placeLabel, double placeLatitude, double placeLongitude,
                                Double vehicleLatitude, Double vehicleLongitude, Long durationSeconds,
                                String readiness, String quickMessage, String proposalLabel, long placeVersion) { }

    public record SandboxState(AccountApi.Profile currentMember, List<DevAccountItem> accounts,
                               List<DevRequestItem> requests, List<DevPickupItem> pickups,
                               DevDummySeedService.GuestInviteInfo guestInvite,
                               DevDummySeedService.VehicleChallengeInfo vehicleQr) { }

    public record LoginBody(UUID memberId, String majungId) { }

    public record InjectBody(UUID memberId, String query) { }

    public record SimulateRequestBody(UUID passengerId, UUID driverId, String placeLabel, Double latitude, Double longitude) { }

    public record DriverActionBody(UUID requestId, String action) { }

    public record VehicleTickBody(UUID pickupId, String action) { }

    public record PassengerActionBody(UUID pickupId, Pickup.Readiness readiness, String quickMessage, PickupApi.Place proposePlace) { }

    public record GuestInviteBody(String placeLabel, Double latitude, Double longitude) { }

    public record ConfirmQrBody(UUID challengeId) { }

    private final DeploymentPolicy deploymentPolicy;
    private final DevDummySeedService seedService;
    private final MemberRepository members;
    private final RegisteredVehicleRepository vehicles;
    private final VehicleAccountLinkRepository links;
    private final RideRequestRepository rideRequests;
    private final PickupRepository pickups;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public DevSandboxApi(DeploymentPolicy deploymentPolicy, DevDummySeedService seedService,
                         MemberRepository members, RegisteredVehicleRepository vehicles,
                         VehicleAccountLinkRepository links, RideRequestRepository rideRequests,
                         PickupRepository pickups) {
        this.deploymentPolicy = deploymentPolicy;
        this.seedService = seedService;
        this.members = members;
        this.vehicles = vehicles;
        this.links = links;
        this.rideRequests = rideRequests;
        this.pickups = pickups;
    }

    @GetMapping("/sandbox")
    public ResponseEntity<String> sandboxHtml(HttpServletRequest request) throws IOException {
        guard(request);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(readAsset("sandbox/index.html"));
    }

    @GetMapping("/sandbox/sandbox.css")
    public ResponseEntity<String> sandboxCss(HttpServletRequest request) throws IOException {
        guard(request);
        return ResponseEntity.ok().contentType(MediaType.valueOf("text/css;charset=UTF-8"))
                .body(readAsset("sandbox/sandbox.css"));
    }

    @GetMapping("/sandbox/sandbox.js")
    public ResponseEntity<String> sandboxJs(HttpServletRequest request) throws IOException {
        guard(request);
        return ResponseEntity.ok().contentType(MediaType.valueOf("application/javascript;charset=UTF-8"))
                .body(readAsset("sandbox/sandbox.js"));
    }

    @GetMapping("/api/v1/dev/accounts")
    public List<DevAccountItem> accounts(HttpServletRequest request) {
        guard(request);
        seedService.seedIfNeeded();
        return loadAccountItems();
    }

    @GetMapping("/api/v1/dev/state")
    public SandboxState state(HttpServletRequest request) {
        guard(request);
        seedService.seedIfNeeded();
        List<DevAccountItem> accountItems = loadAccountItems();
        Map<UUID, Member> memberMap = members.findAll().stream().collect(Collectors.toMap(m -> m.id, m -> m));
        Map<UUID, String> vehicleNames = vehicles.findAll().stream().collect(Collectors.toMap(v -> v.id, v -> v.displayName));

        List<DevRequestItem> reqItems = rideRequests.findAll().stream()
                .sorted(Comparator.comparing((RideRequest r) -> r.createdAt != null ? r.createdAt : Instant.EPOCH).reversed())
                .limit(40)
                .map(r -> {
                    Member drv = memberMap.get(r.driverId);
                    Member pas = memberMap.get(r.passengerId);
                    return new DevRequestItem(r.id, r.status, r.driverId,
                            drv == null ? "-" : drv.nickname, r.passengerId,
                            pas == null ? "-" : pas.nickname, r.placeLabel, r.pickupId,
                            r.driverInvitation, r.queueOrder);
                }).toList();

        List<DevPickupItem> pickupItems = pickups.findAll().stream()
                .filter(p -> !p.isTerminal())
                .sorted(Comparator.comparingInt((Pickup p) -> p.status == Pickup.Status.ACTIVE || p.status == Pickup.Status.ARRIVED ? 0 : 1)
                        .thenComparing(Comparator.comparing((Pickup p) -> p.createdAt != null ? p.createdAt : Instant.EPOCH).reversed()))
                .limit(20)
                .map(p -> new DevPickupItem(p.id, p.status.name(),
                        p.vehicleId == null ? null : vehicleNames.get(p.vehicleId),
                        p.participantName, p.placeLabel, p.placeLatitude, p.placeLongitude,
                        p.vehicleLatitude, p.vehicleLongitude, p.durationSeconds,
                        p.readiness == null ? null : p.readiness.name(), p.quickMessage,
                        p.proposalLabel, p.placeVersion))
                .toList();

        return new SandboxState(currentSessionMember(request), accountItems, reqItems, pickupItems,
                seedService.latestGuestInvite(), seedService.currentVehicleQr());
    }

    @PostMapping("/api/v1/dev/login")
    public AccountApi.Profile login(@RequestBody(required = false) LoginBody body,
                                    HttpServletRequest request, HttpServletResponse response) {
        guard(request);
        seedService.seedIfNeeded();
        Member member = resolveMember(body == null ? null : body.memberId(), body == null ? null : body.majungId());

        AccountApi.Profile existingMember = currentSessionMember(request);
        HttpSession session = request.getSession(false);
        if (session != null && existingMember != null && !existingMember.memberId().equals(member.id)) {
            session.invalidate();
            session = request.getSession(true);
        } else if (session == null) {
            session = request.getSession(true);
        }
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_MEMBER"));
        var principal = new DefaultOAuth2User(authorities, Map.of("memberId", member.id.toString()), "memberId");
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return MemberService.profile(member);
    }

    @PostMapping("/api/v1/dev/reset")
    public SandboxState reset(HttpServletRequest request) {
        guard(request);
        seedService.resetAllDummyData();
        return state(request);
    }

    @PostMapping("/api/v1/dev/inject")
    public SandboxState inject(@RequestBody(required = false) InjectBody body, HttpServletRequest request) {
        guard(request);
        seedService.seedIfNeeded();
        Member target = null;
        if (body != null && body.memberId() != null) {
            target = members.findById(body.memberId()).orElse(null);
        } else if (body != null && body.query() != null && !body.query().isBlank()) {
            String q = body.query().strip();
            target = members.findAll().stream()
                    .filter(m -> m.majungId.equalsIgnoreCase(q) || m.nickname.equalsIgnoreCase(q))
                    .findFirst().orElse(null);
        } else {
            AccountApi.Profile sessionMember = currentSessionMember(request);
            if (sessionMember != null) {
                target = members.findById(sessionMember.memberId()).orElse(null);
            }
        }
        if (target == null) {
            throw new ApiException(404, "MEMBER_NOT_FOUND", "대상 회원을 찾을 수 없어요.");
        }
        seedService.injectScenarioFor(target);
        return state(request);
    }

    @PostMapping("/api/v1/dev/simulate/request")
    public RideRequestApi.View simulateRequest(@RequestBody SimulateRequestBody body, HttpServletRequest request) {
        guard(request);
        if (body == null || body.passengerId() == null || body.driverId() == null) {
            throw new ApiException(400, "INVALID_BODY", "탑승자와 운전자를 모두 선택해주세요.");
        }
        String label = body.placeLabel() == null || body.placeLabel().isBlank() ? "강남역 2번 출구" : body.placeLabel().strip();
        double lat = body.latitude() != null ? body.latitude() : 37.4979;
        double lng = body.longitude() != null ? body.longitude() : 127.0276;
        return seedService.submitRideRequestAsDummy(body.passengerId(), body.driverId(), new PickupApi.Place(label, lat, lng));
    }

    @PostMapping("/api/v1/dev/simulate/driver-action")
    public Object simulateDriverAction(@RequestBody DriverActionBody body, HttpServletRequest request) {
        guard(request);
        if (body == null || body.requestId() == null || body.action() == null) {
            throw new ApiException(400, "INVALID_BODY", "요청과 동작을 지정해주세요.");
        }
        return seedService.driverAction(body.requestId(), body.action());
    }

    @PostMapping("/api/v1/dev/simulate/vehicle-tick")
    public Object simulateVehicleTick(@RequestBody VehicleTickBody body, HttpServletRequest request) {
        guard(request);
        if (body == null || body.pickupId() == null || body.action() == null) {
            throw new ApiException(400, "INVALID_BODY", "픽업과 동작을 지정해주세요.");
        }
        return seedService.vehicleTick(body.pickupId(), body.action());
    }

    @PostMapping("/api/v1/dev/simulate/passenger-action")
    public PickupApi.Snapshot simulatePassengerAction(@RequestBody PassengerActionBody body, HttpServletRequest request) {
        guard(request);
        if (body == null || body.pickupId() == null) {
            throw new ApiException(400, "INVALID_BODY", "픽업 식별자를 지정해주세요.");
        }
        return seedService.passengerAction(body.pickupId(), body.readiness(), body.quickMessage(), body.proposePlace(), true);
    }

    @PostMapping("/api/v1/dev/simulate/guest-invite")
    public DevDummySeedService.GuestInviteInfo createGuestInvite(@RequestBody(required = false) GuestInviteBody body,
                                                               HttpServletRequest request) {
        guard(request);
        PickupApi.Place place = body != null && body.placeLabel() != null && !body.placeLabel().isBlank()
                && body.latitude() != null && body.longitude() != null
                ? new PickupApi.Place(body.placeLabel().strip(), body.latitude(), body.longitude()) : null;
        return seedService.createGuestInvite(place);
    }

    @PostMapping("/api/v1/dev/simulate/vehicle-qr")
    public DevDummySeedService.VehicleChallengeInfo createVehicleQr(HttpServletRequest request) {
        guard(request);
        return seedService.createOrRefreshVehicleQr();
    }

    @PostMapping("/api/v1/dev/simulate/vehicle-qr/confirm")
    public ResponseEntity<Void> confirmVehicleQr(@RequestBody ConfirmQrBody body, HttpServletRequest request) {
        guard(request);
        if (body == null || body.challengeId() == null) {
            throw new ApiException(400, "INVALID_BODY", "challengeId가 필요합니다.");
        }
        seedService.confirmVehicleQr(body.challengeId());
        return ResponseEntity.noContent().build();
    }

    private List<DevAccountItem> loadAccountItems() {
        List<VehicleAccountLink> allLinks = links.findAll();
        Map<UUID, UUID> memberToVehicle = allLinks.stream()
                .collect(Collectors.toMap(l -> l.member.id, l -> l.vehicleId, (a, b) -> a));
        Map<UUID, String> vehicleNames = vehicles.findAll().stream()
                .filter(v -> v.revokedAt == null)
                .collect(Collectors.toMap(v -> v.id, v -> v.displayName));
        return members.findAll().stream()
                .filter(m -> m.profileReady)
                .sorted(Comparator.comparing((Member m) -> !DevDummySeedService.DEMO_SELF_ID.equals(m.id))
                        .thenComparing(m -> !memberToVehicle.containsKey(m.id))
                        .thenComparing(m -> m.majungId))
                .map(m -> {
                    UUID vid = memberToVehicle.get(m.id);
                    return new DevAccountItem(m.id, m.majungId, m.nickname, vid != null,
                            vid, vid == null ? null : vehicleNames.get(vid));
                }).toList();
    }

    private Member resolveMember(UUID memberId, String majungId) {
        if (memberId != null) {
            return members.findById(memberId).orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "회원을 찾을 수 없어요."));
        }
        if (majungId != null && !majungId.isBlank()) {
            return members.findAll().stream()
                    .filter(m -> m.majungId.equalsIgnoreCase(majungId.strip()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "회원을 찾을 수 없어요."));
        }
        return members.findById(DevDummySeedService.DEMO_SELF_ID)
                .orElseThrow(() -> new ApiException(404, "MEMBER_NOT_FOUND", "기본 데모 계정이 없습니다."));
    }

    private AccountApi.Profile currentSessionMember(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object raw = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (!(raw instanceof SecurityContext context)) return null;
        Authentication auth = context.getAuthentication();
        UUID id = AccountIdentity.memberOrNull(auth);
        if (id == null) return null;
        return members.findById(id).map(MemberService::profile).orElse(null);
    }

    private void guard(HttpServletRequest request) {
        if (!deploymentPolicy.developmentToolsEnabled()) {
            throw new ApiException(404, "NOT_FOUND", "찾을 수 없어요.");
        }
        try {
            InetAddress addr = InetAddress.getByName(request.getRemoteAddr());
            if (!addr.isLoopbackAddress()) {
                throw new ApiException(403, "DEV_LOOPBACK_ONLY", "로컬 환경에서만 접근할 수 있어요.");
            }
        } catch (IOException ex) {
            throw new ApiException(403, "DEV_LOOPBACK_ONLY", "로컬 환경에서만 접근할 수 있어요.");
        }
    }

    private static String readAsset(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
