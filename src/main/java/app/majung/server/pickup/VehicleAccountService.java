package app.majung.server.pickup;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class VehicleAccountService {
    private final RegisteredVehicleRepository registeredVehicleRepository;
    private final VehicleRegistrationService registration;
    private final VehicleAccountChallengeRepository challenges;
    private final VehicleAccountLinkRepository links;
    private final MemberRepository members;
    private final MemberService accounts;
    private final TokenService tokens;
    private final Clock clock;

    VehicleAccountService(RegisteredVehicleRepository registeredVehicleRepository, VehicleRegistrationService registration,
            VehicleAccountChallengeRepository challenges, VehicleAccountLinkRepository links,
            MemberRepository members, MemberService accounts, TokenService tokens, Clock clock) {
        this.registeredVehicleRepository = registeredVehicleRepository;
        this.registration = registration;
        this.challenges = challenges;
        this.links = links;
        this.members = members;
        this.accounts = accounts;
        this.tokens = tokens;
        this.clock = clock;
    }
    VehicleAccountApi.Challenge issue(String authorization) {
        RegisteredVehicle vehicle = registration.requireActive(authorization);
        if (links.existsById(vehicle.id)) throw conflict("이미 계정이 연결돼 있어요. 연결을 해제한 뒤 다시 시도해주세요.");
        VehicleAccountChallenge challenge = challenges.findById(vehicle.id).orElseGet(VehicleAccountChallenge::new);
        challenge.vehicleId = vehicle.id;
        challenge.challengeId = UUID.randomUUID();
        String code = tokens.issue();
        challenge.codeHash = tokens.hash(code);
        challenge.expiresAt = clock.instant().plus(Duration.ofMinutes(5));
        challenge.claimant = null;
        challenge.confirmedAt = null;
        challenges.save(challenge);
        return new VehicleAccountApi.Challenge(challenge.challengeId, code, challenge.expiresAt);
    }
    VehicleAccountApi.DeviceView device(String authorization) {
        RegisteredVehicle vehicle = registration.requireActive(authorization);
        VehicleAccountLink link = links.findById(vehicle.id).orElse(null);
        if (link != null) {
            try {
                Member member = accounts.current(link.member.id);
                return view("LINKED", null, member, null);
            } catch (ApiException failure) {
                if (failure.status != 401) throw failure;
                return view("UNLINKING", null, null, null);
            }
        }
        VehicleAccountChallenge challenge = challenges.findById(vehicle.id).orElse(null);
        if (challenge == null || !clock.instant().isBefore(challenge.expiresAt)) return view("UNLINKED", null, null, null);
        if (challenge.claimant != null) accounts.current(challenge.claimant.id);
        return view(challenge.claimant == null ? "AWAITING_ACCOUNT" : "AWAITING_VEHICLE_CONFIRMATION",
                challenge.challengeId, challenge.claimant, challenge.expiresAt);
    }
    VehicleAccountApi.RequestView claim(UUID self, VehicleAccountApi.Claim request) {
        VehicleAccountChallenge hint = challenges.findByChallengeId(request.challengeId()).orElseThrow(VehicleAccountService::unavailable);
        RegisteredVehicle vehicle = active(hint.vehicleId);
        Member member = readyMember(self);
        VehicleAccountChallenge challenge = live(request.challengeId());
        if (!tokens.matches(request.code(), challenge.codeHash)) throw unavailable();
        if (challenge.claimant != null && !challenge.claimant.id.equals(self)) throw conflict("다른 계정에서 연결을 요청했어요. 차량에서 새 QR을 만들어주세요.");
        VehicleAccountLink existing = links.findById(vehicle.id).orElse(null);
        if (existing != null && (challenge.confirmedAt == null || !existing.member.id.equals(self))) throw conflict("이미 계정이 연결된 차량이에요.");
        challenge.claimant = member;
        return requestView(challenge, vehicle);
    }
    VehicleAccountApi.DeviceView confirm(String authorization, UUID challengeId) {
        RegisteredVehicle vehicle = registration.requireActive(authorization);
        VehicleAccountChallenge challenge = live(challengeId);
        if (!challenge.vehicleId.equals(vehicle.id) || challenge.claimant == null) throw unavailable();
        Member member = readyMember(challenge.claimant.id);
        // All device/account writes serialize on the vehicle row. Account cleanup never waits for that row.
        challenge = live(challengeId);
        VehicleAccountLink existing = links.findById(vehicle.id).orElse(null);
        if (existing != null && (!existing.member.id.equals(member.id) || challenge.confirmedAt == null)) throw conflict("이미 다른 계정이 연결돼 있어요.");
        if (existing == null) {
            VehicleAccountLink link = new VehicleAccountLink();
            link.vehicleId = vehicle.id; link.member = member; link.linkedAt = clock.instant();
            links.saveAndFlush(link);
        }
        challenge.confirmedAt = clock.instant();
        return view("LINKED", null, member, null);
    }
    VehicleAccountApi.RequestView request(UUID self, UUID id) {
        accounts.current(self);
        VehicleAccountChallenge hint = live(id);
        RegisteredVehicle vehicle = active(hint.vehicleId);
        VehicleAccountChallenge challenge = live(id);
        if (challenge.claimant == null || !challenge.claimant.id.equals(self)) throw notFound();
        return requestView(challenge, vehicle);
    }
    List<VehicleAccountApi.LinkView> links(UUID self) {
        accounts.current(self);
        return links.findByMemberIdOrderByLinkedAtDesc(self).stream().map(link -> {
            RegisteredVehicle vehicle = registeredVehicleRepository.findById(link.vehicleId).orElseThrow(VehicleAccountService::notFound);
            return vehicle.revokedAt == null ? new VehicleAccountApi.LinkView(vehicle.id, vehicle.displayName, link.linkedAt) : null;
        }).filter(java.util.Objects::nonNull).toList();
    }
    void cancel(UUID self, UUID id) {
        accounts.current(self);
        VehicleAccountChallenge hint = live(id);
        active(hint.vehicleId);
        VehicleAccountChallenge challenge = live(id);
        if (challenge.claimant == null || !challenge.claimant.id.equals(self)) throw notFound();
        if (challenge.confirmedAt != null) throw conflict("이미 연결됐어요. 연결된 차량 목록에서 해제해주세요.");
        challenges.delete(challenge);
    }
    void detach(UUID self, UUID vehicleId) {
        accounts.current(self);
        registeredVehicleRepository.findLockedById(vehicleId).orElseThrow(VehicleAccountService::notFound);
        VehicleAccountLink link = links.findById(vehicleId).orElseThrow(VehicleAccountService::notFound);
        if (!link.member.id.equals(self)) throw notFound();
        links.delete(link);
        challenges.deleteById(vehicleId);
    }
    void detachDevice(String authorization) {
        RegisteredVehicle vehicle = registration.requireActive(authorization);
        links.deleteById(vehicle.id);
        challenges.deleteById(vehicle.id);
    }
    @Scheduled(fixedDelay = 60000) @Transactional
    void purgeExpired() {
        if (ActiveDeploymentJobs.allowed()) challenges.deleteExpired(clock.instant());
    }

    private Member readyMember(UUID id) {
        Member member = members.lockById(id).orElseThrow(() -> new ApiException(401, "LOGIN_REQUIRED", "다시 로그인해주세요."));
        accounts.current(id);
        if (!member.profileReady) throw new ApiException(409, "PROFILE_REQUIRED", "먼저 닉네임을 설정해주세요.");
        return member;
    }
    private RegisteredVehicle active(UUID id) {
        RegisteredVehicle vehicle = registeredVehicleRepository.findLockedById(id).orElseThrow(VehicleAccountService::unavailable);
        if (vehicle.revokedAt != null) throw new ApiException(410, "VEHICLE_LINK_UNAVAILABLE", "차량 권한이 철회됐어요. 차량에서 다시 확인해주세요.");
        return vehicle;
    }
    private VehicleAccountChallenge live(UUID id) {
        VehicleAccountChallenge challenge = challenges.findByChallengeId(id).orElseThrow(VehicleAccountService::unavailable);
        if (!clock.instant().isBefore(challenge.expiresAt)) throw unavailable();
        return challenge;
    }
    private VehicleAccountApi.DeviceView view(String state, UUID id, Member member, java.time.Instant expiry) {
        return new VehicleAccountApi.DeviceView(state, id, member == null ? null : member.nickname, member == null ? null : member.majungId, expiry);
    }
    private VehicleAccountApi.RequestView requestView(VehicleAccountChallenge challenge, RegisteredVehicle vehicle) {
        VehicleAccountLink link = links.findById(vehicle.id).orElse(null);
        String state = challenge.confirmedAt != null && link != null && link.member.id.equals(challenge.claimant.id)
                ? "LINKED" : "AWAITING_VEHICLE_CONFIRMATION";
        return new VehicleAccountApi.RequestView(challenge.challengeId, vehicle.id, vehicle.displayName, state, challenge.expiresAt);
    }
    private static ApiException unavailable() { return new ApiException(410, "VEHICLE_LINK_UNAVAILABLE", "연결 QR이 만료되었거나 사용할 수 없어요. 차량에서 새 QR을 만들어주세요."); }
    private static ApiException notFound() { return new ApiException(404, "VEHICLE_LINK_NOT_FOUND", "연결 정보를 찾을 수 없어요."); }
    private static ApiException conflict(String message) { return new ApiException(409, "VEHICLE_LINK_CONFLICT", message); }
}
