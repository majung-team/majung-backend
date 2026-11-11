package app.majung.server.pickup.service;

import app.majung.server.pickup.common.ApiException;
import app.majung.server.pickup.domain.Member;
import app.majung.server.pickup.domain.Pickup;
import app.majung.server.pickup.domain.RideRequest;
import app.majung.server.pickup.repository.MemberConnectionRepository;
import app.majung.server.pickup.repository.MemberRepository;
import app.majung.server.pickup.repository.PickupRepository;
import app.majung.server.pickup.repository.RideRequestRepository;
import app.majung.server.pickup.repository.VehicleAccountChallengeRepository;
import app.majung.server.pickup.repository.VehicleAccountLinkRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes a member and cancels or cleans up their associated rides, connections, and vehicle links. */
@Service
public class MemberDeletionService {
    private final MemberRepository memberRepository;
    private final MemberConnectionRepository connectionRepository;
    private final RideRequestRepository rideRequests;
    private final PickupRepository pickups;
    private final ArrivalPushService arrivalPush;
    private final VehicleAccountLinkRepository vehicleLinks;
    private final VehicleAccountChallengeRepository vehicleChallenges;
    private final Clock clock;

    public MemberDeletionService(
            MemberRepository memberRepository,
            MemberConnectionRepository connectionRepository,
            RideRequestRepository rideRequests,
            PickupRepository pickups,
            ArrivalPushService arrivalPush,
            VehicleAccountLinkRepository vehicleLinks,
            VehicleAccountChallengeRepository vehicleChallenges,
            Clock clock) {
        this.memberRepository = memberRepository;
        this.connectionRepository = connectionRepository;
        this.rideRequests = rideRequests;
        this.pickups = pickups;
        this.arrivalPush = arrivalPush;
        this.vehicleLinks = vehicleLinks;
        this.vehicleChallenges = vehicleChallenges;
        this.clock = clock;
    }

    @Transactional
    public void deleteMemberById(UUID memberId) {
        Member member = memberRepository.lockById(memberId)
                .orElseThrow(() -> new ApiException(401, "LOGIN_REQUIRED", "다시 로그인해주세요."));
        purgeMember(member);
    }

    @Transactional
    public void deleteKakaoMemberIfPresent(String providerSubject) {
        memberRepository.findByProviderAndProviderSubject("kakao", providerSubject)
                .ifPresent(candidate -> memberRepository.lockById(candidate.id).ifPresent(this::purgeMember));
    }

    private void purgeMember(Member member) {
        for (RideRequest request : rideRequests.forMember(member.id)) {
            if (request.pickupId != null) {
                pickups.findLockedById(request.pickupId).ifPresent(pickup -> {
                    if (!pickup.isTerminal()) {
                        pickup.status = Pickup.Status.CANCELLED;
                        pickup.endedAt = clock.instant();
                        pickup.inviteHash = null;
                        pickup.clearProposal();
                        pickup.clearVehicle();
                    }
                    arrivalPush.remove(pickup.id);
                });
            }
            rideRequests.delete(request);
        }
        rideRequests.flush();
        connectionRepository.deleteForMember(member.id);
        vehicleChallenges.deleteForMember(member.id);
        vehicleLinks.deleteForMember(member.id);
        memberRepository.delete(member);
        memberRepository.flush();
    }
}
