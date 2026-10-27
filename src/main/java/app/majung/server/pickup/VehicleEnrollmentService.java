package app.majung.server.pickup;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Device secret never enters the QR/browser. No vehicle authority exists before both confirmations. */
@Service
@Transactional
class VehicleEnrollmentService {
    private final VehicleEnrollmentRepository enrollments;
    private final RegisteredVehicleRepository vehicles;
    private final VehicleAccountLinkRepository links;
    private final MemberRepository members;
    private final MemberService accounts;
    private final VehicleRegistrationService registration;
    private final TokenService tokens;
    private final Clock clock;

    VehicleEnrollmentService(VehicleEnrollmentRepository enrollments, RegisteredVehicleRepository vehicles,
            VehicleAccountLinkRepository links, MemberRepository members, MemberService accounts,
            VehicleRegistrationService registration, TokenService tokens, Clock clock) {
        this.enrollments=enrollments; this.vehicles=vehicles; this.links=links; this.members=members;
        this.accounts=accounts; this.registration=registration; this.tokens=tokens; this.clock=clock;
    }
    VehicleEnrollmentApi.Started start() {
        String secret=tokens.issue(); String code=tokens.issue();
        VehicleEnrollment enrollment=new VehicleEnrollment();
        enrollment.id=UUID.randomUUID(); enrollment.deviceHash=tokens.hash(secret); enrollment.codeHash=tokens.hash(code);
        enrollment.expiresAt=clock.instant().plus(Duration.ofMinutes(5));
        enrollments.save(enrollment);
        return new VehicleEnrollmentApi.Started(enrollment.id, secret, code, enrollment.expiresAt);
    }
    VehicleEnrollmentApi.State device(UUID id, String authorization) {
        VehicleEnrollment enrollment=deviceRecord(id, authorization);
        if (enrollment.confirmedAt != null) {
            RegisteredVehicle vehicle=registration.requireActive(authorization);
            VehicleAccountLink link=links.findById(vehicle.id).orElseThrow(VehicleEnrollmentService::unavailable);
            if (!link.member.id.equals(enrollment.claimantId)) throw unavailable();
        } else requireLive(enrollment);
        return state(enrollment);
    }
    VehicleEnrollmentApi.State claim(UUID memberId, UUID id, String code) {
        Member member=readyMember(memberId);
        VehicleEnrollment enrollment=live(id);
        if (!tokens.matches(code, enrollment.codeHash) || enrollment.confirmedAt != null) throw unavailable();
        if (enrollment.claimantId != null && !member.id.equals(enrollment.claimantId))
            throw new ApiException(409,"ENROLLMENT_CLAIMED","다른 계정이 요청했어요. 차량에서 새 QR을 만들어주세요.");
        enrollment.claimantId=member.id;
        return state(enrollment);
    }
    VehicleEnrollmentApi.State confirm(UUID id, String authorization, String expectedMajungId) {
        VehicleEnrollment hint=enrollments.findById(id).orElseThrow(VehicleEnrollmentService::unavailable);
        if (hint.claimantId == null) throw unavailable();
        Member member=readyMember(hint.claimantId);
        VehicleEnrollment enrollment=deviceRecord(id, authorization);
        if (enrollment.confirmedAt != null) {
            VehicleEnrollmentApi.State current=device(id, authorization);
            if (!expectedMajungId.equals(current.majungId())) throw unavailable();
            return current;
        }
        requireLive(enrollment);
        if (enrollment.claimantId == null) throw unavailable();
        if (!member.id.equals(enrollment.claimantId) || !member.majungId.equals(expectedMajungId)) throw unavailable();
        RegisteredVehicle vehicle=new RegisteredVehicle();
        vehicle.id=enrollment.id; vehicle.credentialHash=enrollment.deviceHash;
        vehicle.displayName="마중 차량"; vehicle.registeredAt=clock.instant();
        vehicles.saveAndFlush(vehicle);
        VehicleAccountLink link=new VehicleAccountLink();
        link.vehicleId=vehicle.id; link.member=member; link.linkedAt=clock.instant(); links.saveAndFlush(link);
        enrollment.confirmedAt=clock.instant();
        return state(enrollment);
    }
    VehicleEnrollmentApi.State account(UUID memberId, UUID id) {
        accounts.current(memberId);
        VehicleEnrollment enrollment=live(id);
        if (!memberId.equals(enrollment.claimantId)) throw unavailable();
        if (enrollment.confirmedAt != null) {
            VehicleAccountLink link=links.findById(id).orElseThrow(VehicleEnrollmentService::unavailable);
            RegisteredVehicle vehicle=vehicles.findById(id).orElseThrow(VehicleEnrollmentService::unavailable);
            if (vehicle.revokedAt != null || !memberId.equals(link.member.id)) throw unavailable();
        }
        return state(enrollment);
    }
    VehicleEnrollmentApi.State cancelDevice(UUID id, String authorization) {
        VehicleEnrollment enrollment=deviceRecord(id, authorization);
        cancel(enrollment); return state(enrollment);
    }
    VehicleEnrollmentApi.State cancelAccount(UUID memberId, UUID id) {
        accounts.current(memberId);
        VehicleEnrollment enrollment=live(id);
        if (!memberId.equals(enrollment.claimantId)) throw unavailable();
        cancel(enrollment); return state(enrollment);
    }
    private void cancel(VehicleEnrollment enrollment) {
        if (enrollment.confirmedAt != null) throw new ApiException(409,"ENROLLMENT_COMPLETED","이미 연결됐어요. 연결된 차량 목록에서 해제해주세요.");
        enrollment.expiresAt=clock.instant(); enrollment.claimantId=null;
    }
    private VehicleEnrollment deviceRecord(UUID id, String authorization) {
        String token=tokens.bearer(authorization);
        VehicleEnrollment enrollment=enrollments.lockById(id).orElseThrow(VehicleEnrollmentService::unavailable);
        if (!tokens.matches(token,enrollment.deviceHash)) throw unavailable();
        return enrollment;
    }
    private VehicleEnrollment live(UUID id) {
        VehicleEnrollment enrollment=enrollments.lockById(id).orElseThrow(VehicleEnrollmentService::unavailable);
        requireLive(enrollment); return enrollment;
    }
    private void requireLive(VehicleEnrollment enrollment) {
        if (!clock.instant().isBefore(enrollment.expiresAt)) throw unavailable();
    }
    private Member readyMember(UUID id) {
        Member member=members.lockById(id).orElseThrow(VehicleEnrollmentService::unavailable);
        accounts.current(id);
        if (!member.profileReady) throw new ApiException(409,"PROFILE_REQUIRED","먼저 닉네임을 설정해주세요.");
        return member;
    }
    private VehicleEnrollmentApi.State state(VehicleEnrollment enrollment) {
        Member member=enrollment.claimantId == null ? null : accounts.current(enrollment.claimantId);
        String status=enrollment.confirmedAt != null ? "LINKED" : member == null ? "AWAITING_ACCOUNT" : "AWAITING_VEHICLE_CONFIRMATION";
        return new VehicleEnrollmentApi.State(enrollment.id,status,member == null ? null : member.nickname,
                member == null ? null : member.majungId,enrollment.expiresAt);
    }
    @Scheduled(fixedDelay=60000)
    void purgeExpired() { enrollments.deleteExpired(clock.instant().minus(Duration.ofHours(1))); }
    private static ApiException unavailable() {
        return new ApiException(410,"ENROLLMENT_UNAVAILABLE","연결 요청이 만료되었거나 사용할 수 없어요. 차량에서 새 QR을 만들어주세요.");
    }
}
