package app.majung.server.pickup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class KakaoUnlinkService {
    private static final Logger log = LoggerFactory.getLogger(KakaoUnlinkService.class);
    private final KakaoUnlinkRepository unlinkRepository;
    private final MemberRepository memberRepository;
    private final MemberConnectionRepository connectionRepository;
    private final RideRequestRepository rideRequests;
    private final PickupRepository pickups;
    private final VehicleAccountLinkRepository vehicleLinks;
    private final VehicleAccountChallengeRepository vehicleChallenges;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String adminKey;
    private final String appId;
    private final boolean cleanupEnabled;

    KakaoUnlinkService(KakaoUnlinkRepository unlinkRepository, MemberRepository memberRepository,
            MemberConnectionRepository connectionRepository, VehicleAccountLinkRepository vehicleLinks,
            VehicleAccountChallengeRepository vehicleChallenges, RideRequestRepository rideRequests, PickupRepository pickups, PlatformTransactionManager transactions, Clock clock,
            @Value("${majung.login.kakao-admin-key:}") String adminKey,
            @Value("${majung.login.kakao-app-id:}") String appId,
            @Value("${majung.login.unlink-cleanup-enabled:true}") boolean cleanupEnabled) {
        this.unlinkRepository = unlinkRepository;
        this.memberRepository = memberRepository;
        this.connectionRepository = connectionRepository;
        this.vehicleLinks = vehicleLinks;
        this.vehicleChallenges = vehicleChallenges;
        this.rideRequests = rideRequests;
        this.pickups = pickups;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(2);
        this.clock = clock;
        this.adminKey = adminKey;
        this.appId = appId;
        this.cleanupEnabled = cleanupEnabled;
    }

    void receive(String authorization, String receivedAppId, String subject, String referrer) {
        if (adminKey.isBlank() || appId.isBlank())
            throw new ApiException(503, "UNLINK_NOT_CONFIGURED", "연결 해제 처리를 준비 중이에요.");
        if (authorization == null || authorization.length() > 512
                || !MessageDigest.isEqual(("KakaoAK " + adminKey).getBytes(StandardCharsets.UTF_8),
                        authorization.getBytes(StandardCharsets.UTF_8)) || !appId.equals(receivedAppId))
            throw new ApiException(403, "UNLINK_FORBIDDEN", "요청을 확인하지 못했어요.");
        if (subject == null || !subject.matches("[0-9]{1,30}") || referrer == null || referrer.isBlank() || referrer.length() > 80)
            throw new ApiException(400, "UNLINK_INVALID", "요청 형식을 확인해주세요.");
        try {
            transaction.executeWithoutResult(status -> {
                if (!unlinkRepository.existsById(subject)) {
                    KakaoUnlink event = new KakaoUnlink();
                    event.providerSubject = subject;
                    event.receivedAt = clock.instant();
                    unlinkRepository.saveAndFlush(event);
                }
            });
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent notification already committed this work item.
            if (!unlinkRepository.existsById(subject)) throw duplicate;
        }
    }

    @Scheduled(fixedDelayString = "${majung.login.unlink-cleanup-interval-ms:5000}", initialDelay = 5000)
    void cleanup() {
        if (!ActiveDeploymentJobs.allowed()) return;
        if (!cleanupEnabled) return;
        for (KakaoUnlink event : unlinkRepository.findAll(PageRequest.of(0, 20))) {
            try { cleanupOne(event.providerSubject); }
            catch (RuntimeException failure) {
                // Leave the durable event for the next run; never log provider IDs or credentials.
                log.warn("Kakao unlink cleanup failed; pending work will be retried");
            }
        }
    }

    void cleanupOne(String subject) {
        transaction.executeWithoutResult(status -> {
            if (unlinkRepository.lockBySubject(subject).isEmpty()) return;
            memberRepository.findByProviderAndProviderSubject("kakao", subject).ifPresent(candidate -> {
                memberRepository.lockById(candidate.id).ifPresent(member -> {
                    for (RideRequest request : rideRequests.forMember(member.id)) {
                        if (request.pickupId != null) pickups.findLockedById(request.pickupId).ifPresent(pickup -> {
                            if (!pickup.isTerminal()) {
                                pickup.status=Pickup.Status.CANCELLED; pickup.endedAt=clock.instant();
                                pickup.inviteHash=null; pickup.clearVehicle();
                            }
                        });
                        rideRequests.delete(request);
                    }
                    rideRequests.flush();
                    connectionRepository.deleteForMember(member.id);
                    vehicleChallenges.deleteForMember(member.id);
                    vehicleLinks.deleteForMember(member.id);
                    memberRepository.delete(member);
                    memberRepository.flush();
                });
            });
            unlinkRepository.deleteById(subject);
        });
    }
}
