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
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PickupService {
    private static final String DEFAULT_PARTICIPANT_NAME = "탑승자";
    private final PickupRepository pickupRepository;
    private final RideRequestRepository rideRequestRepository;
    private final RegisteredVehicleRepository registeredVehicleRepository;
    private final ArrivalPushService arrivalPush;
    private final TokenService tokenService;
    private final Clock clock;
    private final VehicleRegistrationService vehicleRegistrationService;
    private final DeploymentPolicy deploymentPolicy;
    private final Duration inviteTtl;
    private final Duration pickupTtl;
    private final Duration vehicleFreshness;
    private final Duration vehicleLastKnown;

    public PickupService(PickupRepository pickupRepository, RideRequestRepository rideRequestRepository,
                  RegisteredVehicleRepository registeredVehicleRepository,
                  ArrivalPushService arrivalPush,
                  TokenService tokenService, Clock clock,
                  VehicleRegistrationService vehicleRegistrationService, DeploymentPolicy deploymentPolicy,
                  @Value("${majung.invite-ttl}") Duration inviteTtl,
                  @Value("${majung.pickup-ttl}") Duration pickupTtl,
                  @Value("${majung.vehicle-freshness}") Duration vehicleFreshness,
                  @Value("${majung.vehicle-last-known}") Duration vehicleLastKnown) {
        if (inviteTtl.isNegative() || inviteTtl.isZero() || pickupTtl.compareTo(inviteTtl) < 0
                || pickupTtl.compareTo(Duration.ofHours(12)) > 0 || vehicleFreshness.isNegative() || vehicleFreshness.isZero()
                || vehicleLastKnown.compareTo(vehicleFreshness) < 0) {
            throw new IllegalArgumentException("Invalid pickup lifetime configuration");
        }
        this.pickupRepository = pickupRepository;
        this.rideRequestRepository = rideRequestRepository;
        this.registeredVehicleRepository = registeredVehicleRepository;
        this.arrivalPush = arrivalPush;
        this.tokenService = tokenService;
        this.clock = clock;
        this.vehicleRegistrationService = vehicleRegistrationService;
        this.deploymentPolicy = deploymentPolicy;
        this.inviteTtl = inviteTtl;
        this.pickupTtl = pickupTtl;
        this.vehicleFreshness = vehicleFreshness;
        this.vehicleLastKnown = vehicleLastKnown;
    }

    public PickupApi.Created createPickup(PickupApi.Place place, String authorization) {
        RegisteredVehicle vehicle = authorization == null && deploymentPolicy.developmentToolsEnabled()
                ? null : vehicleRegistrationService.requireActive(authorization);
        Instant now = clock.instant();
        String ownerToken = tokenService.issue();
        String inviteToken = tokenService.issue();
        Pickup pickup = Pickup.create(tokenService.hash(ownerToken), tokenService.hash(inviteToken),
                now, now.plus(inviteTtl), now.plus(pickupTtl), place);
        pickup.vehicleId = vehicle == null ? null : vehicle.id;
        pickupRepository.save(pickup);
        return new PickupApi.Created(pickup.id, ownerToken, inviteToken, pickup.inviteExpiresAt, pickup.expiresAt);
    }

    @Transactional(readOnly = true)
    public PickupApi.InvitationPreview previewInvitation(UUID id, String inviteToken) {
        Instant now = clock.instant();
        Pickup pickup = pickupRepository.findById(id)
                .filter(candidate -> candidate.status == Pickup.Status.INVITING
                        && !candidate.isExpired(now)
                        && now.isBefore(candidate.inviteExpiresAt)
                        && candidate.inviteHash != null
                        && tokenService.matches(inviteToken, candidate.inviteHash))
                .orElseThrow(() -> error(410, "INVITE_UNAVAILABLE", "이 초대 링크는 더 이상 사용할 수 없어요."));
        return new PickupApi.InvitationPreview(pickup.placeLabel);
    }

    public PickupApi.Joined joinPickup(UUID id, PickupApi.JoinRequest request) {
        Pickup pickup = locked(id);
        requireOpen(pickup);
        if (!clock.instant().isBefore(pickup.inviteExpiresAt) || pickup.inviteHash == null) {
            throw error(410, "INVITE_UNAVAILABLE", "초대가 만료되었거나 이미 사용됐습니다.");
        }
        if (!tokenService.matches(request.inviteToken(), pickup.inviteHash)) {
            throw error(403, "FORBIDDEN", "이 초대로 참여할 수 없습니다.");
        }
        if (pickup.status != Pickup.Status.INVITING) {
            throw error(409, "INVALID_STATE", "참여할 수 없는 픽업 상태입니다.");
        }
        String guestToken = tokenService.issue();
        pickup.participantId = UUID.randomUUID();
        // The first person to open the invitation is connected right away; the single-use invite is consumed.
        pickup.participantName = request.displayName() == null || request.displayName().isBlank()
                ? DEFAULT_PARTICIPANT_NAME : request.displayName().strip();
        pickup.participantHash = tokenService.hash(guestToken);
        pickup.inviteHash = null;
        pickup.status = Pickup.Status.ACTIVE;
        pickup.lastVehicleActivityAt = clock.instant();
        return new PickupApi.Joined(pickup.participantId, guestToken, pickup.status.name());
    }

    public PickupApi.Invitation rotateInvitation(UUID id, String auth) {
        Pickup pickup = owner(id, auth);
        requireOpen(pickup);
        if (pickup.status != Pickup.Status.INVITING && pickup.status != Pickup.Status.WAITING_APPROVAL) {
            throw error(409, "INVALID_STATE", "위치 공유 중에는 초대를 다시 만들 수 없습니다.");
        }
        return reissueInvitation(pickup);
    }

    // Only the driver can disconnect the current passenger; the passenger's token stops working immediately.
    public PickupApi.Invitation disconnectParticipant(UUID id, String auth) {
        Pickup pickup = owner(id, auth);
        requireOpen(pickup);
        if (rideRequestRepository.existsByPickupId(id)) {
            throw error(409, "RIDE_REQUEST_PICKUP", "마중 요청으로 시작한 픽업은 요청 목록에서 관리해주세요.");
        }
        if (pickup.participantHash == null) {
            throw error(409, "NO_PARTICIPANT", "연결된 탑승자가 없습니다.");
        }
        return reissueInvitation(pickup);
    }

    private PickupApi.Invitation reissueInvitation(Pickup pickup) {
        String inviteToken = tokenService.issue();
        pickup.inviteHash = tokenService.hash(inviteToken);
        Instant proposedExpiry = clock.instant().plus(inviteTtl);
        pickup.inviteExpiresAt = proposedExpiry.isBefore(pickup.expiresAt) ? proposedExpiry : pickup.expiresAt;
        pickup.participantHash = null;
        pickup.participantId = null;
        pickup.participantName = null;
        pickup.readiness = Pickup.Readiness.WAITING;
        pickup.readinessSequence = 0;
        pickup.readinessReceivedSequence = 0;
        pickup.status = Pickup.Status.INVITING;
        pickup.arrivedAt = null;
        pickup.lastVehicleActivityAt = null;
        pickup.clearProposal();
        // The disconnected passenger's browser must not be notified about the next passenger's pickup.
        arrivalPush.remove(pickup.id);
        // The previous passenger's view of the car must not carry over; the sequence stays monotonic.
        pickup.clearVehicle();
        return new PickupApi.Invitation(inviteToken, pickup.inviteExpiresAt);
    }

    public PickupApi.Snapshot approveParticipant(UUID id, UUID participantId, String auth) {
        Pickup pickup = owner(id, auth);
        requireOpen(pickup);
        if (!Objects.equals(pickup.participantId, participantId)) {
            throw error(404, "PARTICIPANT_NOT_FOUND", "참여 요청을 찾을 수 없습니다.");
        }
        if (pickup.status == Pickup.Status.ACTIVE || pickup.status == Pickup.Status.ARRIVED) {
            return toPickupResponse(pickup, true);
        }
        if (pickup.status != Pickup.Status.WAITING_APPROVAL) {
            throw error(409, "INVALID_STATE", "승인할 수 없는 상태입니다.");
        }
        if (!clock.instant().isBefore(pickup.inviteExpiresAt)) {
            throw error(410, "INVITE_EXPIRED", "참여 요청이 만료됐습니다. 새로 초대해주세요.");
        }
        pickup.status = Pickup.Status.ACTIVE;
        pickup.lastVehicleActivityAt = clock.instant();
        return toPickupResponse(pickup, true);
    }

    public PickupApi.Snapshot getPickup(UUID id, String auth) {
        String token = tokenService.bearer(auth);
        Pickup pickup = locked(id);
        boolean isOwner = tokenService.matches(token, pickup.ownerHash);
        if (!isOwner && !tokenService.matches(token, pickup.participantHash)) {
            throw error(403, "FORBIDDEN", "이 픽업에 접근할 수 없습니다.");
        }
        return toPickupResponse(pickup, isOwner);
    }

    public PickupApi.Snapshot updateReadiness(UUID id, String auth, PickupApi.ReadinessRequest request) {
        String token = tokenService.bearer(auth);
        Pickup pickup = locked(id);
        if (!tokenService.matches(token, pickup.participantHash)) {
            throw error(403, "FORBIDDEN", "탑승자만 준비 상태를 바꿀 수 있습니다.");
        }
        requireSharing(pickup);
        if (request.sequence() == pickup.readinessSequence && request.readiness() == pickup.readiness) {
            return toPickupResponse(pickup, false);
        }
        if (request.sequence() <= pickup.readinessSequence) {
            throw error(409, "STALE_EVENT", "이미 반영된 상태보다 새로운 순서로 요청해주세요.");
        }
        pickup.readiness = request.readiness();
        pickup.readinessSequence = request.sequence();
        return toPickupResponse(pickup, false);
    }

    private void requirePlaceVersion(Pickup pickup, Long version) {
        if (version == null && pickup.placeVersion == 1) return;
        if (version == null || version != pickup.placeVersion) {
            throw error(409, "PLACE_CHANGED", "만날 장소가 바뀌었습니다. 경로와 상태를 다시 확인해주세요.");
        }
    }

    public PickupApi.Snapshot confirmPlace(UUID id, String auth, PickupApi.PlaceConfirmation request) {
        Pickup pickup = owner(id, auth);
        requireSharing(pickup);
        if (Objects.equals(request.proposalId(), pickup.confirmedProposalId)
                && request.expectedPlaceVersion() == pickup.placeVersion - 1) return toPickupResponse(pickup, true);
        if (pickup.status != Pickup.Status.ACTIVE) throw error(409, "INVALID_STATE", "도착 전의 픽업에서만 장소를 바꿀 수 있습니다.");
        requirePlaceVersion(pickup, request.expectedPlaceVersion());
        if (!Objects.equals(request.proposalId(), pickup.proposalId) || pickup.proposalBy == null) {
            throw error(409, "PROPOSAL_CHANGED", "장소 제안이 바뀌었습니다. 새 제안을 확인해주세요.");
        }
        if (Math.abs(request.place().latitude() - pickup.proposalLatitude) > 0.000001
                || Math.abs(request.place().longitude() - pickup.proposalLongitude) > 0.000001) {
            throw error(409, "ROUTE_UNVERIFIED", "내비 경로의 장소가 제안과 일치하지 않습니다.");
        }
        Instant now = clock.instant();
        if (request.sourceTime().isAfter(now.plusSeconds(5)) || request.sourceTime().isBefore(now.minusSeconds(10))) {
            throw error(409, "ROUTE_UNVERIFIED", "내비에서 새 경로와 도착 시간을 다시 확인해주세요.");
        }
        pickup.placeLabel = pickup.proposalLabel;
        pickup.placeLatitude = pickup.proposalLatitude;
        pickup.placeLongitude = pickup.proposalLongitude;
        pickup.confirmedProposalId = pickup.proposalId;
        pickup.placeVersion++;
        pickup.clearProposal();
        pickup.clearVehicle();
        arrivalPush.rearm(pickup.id, pickup.placeVersion);
        return toPickupResponse(pickup, true);
    }

    public PickupApi.Snapshot receiveReadiness(UUID id, String auth, PickupApi.ReadinessReceipt request) {
        Pickup pickup = owner(id, auth);
        requireSharing(pickup);
        // Participant identity prevents a delayed receipt from confirming a replacement passenger's sequence.
        if (!Objects.equals(pickup.participantId, request.participantId())) {
            throw error(409, "PARTICIPANT_CHANGED", "탑승자가 변경됐습니다. 상태를 다시 확인해주세요.");
        }
        if (request.sequence() > pickup.readinessSequence) {
            throw error(409, "READINESS_NOT_FOUND", "준비 상태를 다시 확인해주세요.");
        }
        pickup.readinessReceivedSequence = Math.max(pickup.readinessReceivedSequence, request.sequence());
        return toPickupResponse(pickup, true);
    }

    public PickupApi.VehicleState updateVehicle(UUID id, String auth, PickupApi.VehicleRequest request) {
        Pickup pickup = owner(id, auth);
        requireSharing(pickup);
        requirePlaceVersion(pickup, request.placeVersion());
        Instant now = clock.instant();
        if (request.sourceTime().isAfter(now.plusSeconds(5))) {
            throw error(400, "FUTURE_SOURCE_TIME", "미래 시각의 차량 정보는 반영할 수 없습니다.");
        }
        if (request.sequence() == pickup.vehicleSequence
                && Objects.equals(request.latitude(), pickup.vehicleLatitude)
                && Objects.equals(request.longitude(), pickup.vehicleLongitude)
                && Objects.equals(request.durationSeconds(), pickup.durationSeconds)
                && Objects.equals(request.sourceTime(), pickup.vehicleSourceTime)) {
            return toVehicleResponse(pickup);
        }
        if (request.sequence() <= pickup.vehicleSequence || (pickup.vehicleSourceTime != null
                && request.sourceTime().isBefore(pickup.vehicleSourceTime))) {
            throw error(409, "STALE_EVENT", "이전 차량 정보로 현재 상태를 덮을 수 없습니다.");
        }
        if (request.sourceTime().plus(vehicleFreshness).isAfter(now)) pickup.lastVehicleActivityAt = now;
        pickup.vehicleSequence = request.sequence();
        pickup.vehicleLatitude = request.latitude();
        pickup.vehicleLongitude = request.longitude();
        pickup.durationSeconds = request.durationSeconds();
        pickup.vehicleSourceTime = request.sourceTime();
        pickup.vehicleReceivedAt = now;
        if (request.sourceTime().plus(vehicleFreshness).isAfter(now))
            arrivalPush.vehicleUpdated(pickup.id, pickup.status, pickup.durationSeconds, pickup.placeVersion);
        return toVehicleResponse(pickup);
    }

    public PickupApi.VehicleState invalidateVehicle(UUID id, String auth, long sequence) {
        return invalidateVehicle(id, auth, sequence, null);
    }

    public PickupApi.VehicleState invalidateVehicle(UUID id, String auth, long sequence, Long placeVersion) {
        Pickup pickup = owner(id, auth);
        requireSharing(pickup);
        requirePlaceVersion(pickup, placeVersion);
        if (sequence == pickup.vehicleSequence && pickup.vehicleSourceTime == null) {
            return toVehicleResponse(pickup);
        }
        if (sequence <= pickup.vehicleSequence) {
            throw error(409, "STALE_EVENT", "이전 요청으로 차량 상태를 바꿀 수 없습니다.");
        }
        pickup.vehicleSequence = sequence;
        pickup.clearVehicle();
        return toVehicleResponse(pickup);
    }

    public PickupEventStreams.State streamState(UUID id, String auth) {
        return new PickupEventStreams.State(getPickup(id, auth), getVehicle(id, auth));
    }

    public PickupApi.VehicleState getVehicle(UUID id, String auth) {
        String token = tokenService.bearer(auth);
        Pickup pickup = locked(id);
        if (!tokenService.matches(token, pickup.ownerHash)
                && !tokenService.matches(token, pickup.participantHash)) {
            throw error(403, "FORBIDDEN", "이 픽업에 접근할 수 없습니다.");
        }
        requireSharing(pickup);
        return toVehicleResponse(pickup);
    }

    public PickupApi.Snapshot arrivePickup(UUID id, String auth) { return arrivePickup(id, auth, null); }

    public PickupApi.Snapshot arrivePickup(UUID id, String auth, Long placeVersion) {
        Pickup pickup = owner(id, auth);
        requireSharing(pickup);
        requirePlaceVersion(pickup, placeVersion);
        if (pickup.status != Pickup.Status.ARRIVED) pickup.arrivedAt = clock.instant();
        pickup.status = Pickup.Status.ARRIVED;
        arrivalPush.arrived(pickup.id, pickup.placeVersion);
        pickup.clearProposal();
        return toPickupResponse(pickup, true);
    }

    public PickupApi.Snapshot endPickup(UUID id, String auth, Pickup.Status target) {
        Pickup pickup = owner(id, auth);
        if (pickup.status == target) {
            return toPickupResponse(pickup, true);
        }
        requireOpen(pickup);
        if (target == Pickup.Status.COMPLETED && pickup.status != Pickup.Status.ARRIVED) {
            throw error(409, "ARRIVAL_REQUIRED", "도착 후 탑승을 확인하고 완료해주세요.");
        }
        pickup.status = target;
        pickup.endedAt = clock.instant();
        pickup.inviteHash = null;
        pickup.clearProposal();
        pickup.clearVehicle();
        arrivalPush.remove(pickup.id);
        return toPickupResponse(pickup, true);
    }

    // Both sides may suggest a place. The confirmed place never changes here; the driver decides separately.
    public PickupApi.Snapshot proposePlace(UUID id, String auth, PickupApi.Place place) {
        String token = tokenService.bearer(auth);
        Pickup pickup = locked(id);
        boolean isOwner = tokenService.matches(token, pickup.ownerHash);
        if (!isOwner && !tokenService.matches(token, pickup.participantHash)) {
            throw error(403, "FORBIDDEN", "이 픽업에 접근할 수 없습니다.");
        }
        return applyProposal(pickup, isOwner ? Pickup.Proposer.DRIVER : Pickup.Proposer.PASSENGER, place, isOwner);
    }

    public PickupApi.Snapshot withdrawPlaceProposal(UUID id, String auth) {
        String token = tokenService.bearer(auth);
        Pickup pickup = locked(id);
        boolean isOwner = tokenService.matches(token, pickup.ownerHash);
        if (!isOwner && !tokenService.matches(token, pickup.participantHash)) {
            throw error(403, "FORBIDDEN", "이 픽업에 접근할 수 없습니다.");
        }
        return dropProposal(pickup, isOwner ? null : Pickup.Proposer.PASSENGER, isOwner);
    }

    public PickupApi.Snapshot proposePlaceForAccount(UUID id, PickupApi.Place place) {
        return applyProposal(locked(id), Pickup.Proposer.PASSENGER, place, false);
    }

    public PickupApi.Snapshot withdrawPlaceProposalForAccount(UUID id) {
        return dropProposal(locked(id), Pickup.Proposer.PASSENGER, false);
    }

    private PickupApi.Snapshot applyProposal(Pickup pickup, Pickup.Proposer by, PickupApi.Place place, boolean isOwner) {
        requireSharing(pickup);
        if (pickup.status != Pickup.Status.ACTIVE) {
            throw error(409, "INVALID_STATE", "도착 전의 픽업에서만 장소를 제안할 수 있습니다.");
        }
        if (Math.abs(place.latitude() - pickup.placeLatitude) < 0.00001
                && Math.abs(place.longitude() - pickup.placeLongitude) < 0.00001) {
            throw error(409, "SAME_PLACE", "지금 정해진 장소와 같은 곳입니다.");
        }
        pickup.proposalId = UUID.randomUUID();
        pickup.proposalBy = by;
        pickup.proposalLabel = place.label().strip();
        pickup.proposalLatitude = place.latitude();
        pickup.proposalLongitude = place.longitude();
        pickup.proposalAt = clock.instant();
        return toPickupResponse(pickup, isOwner);
    }

    // The driver can clear any proposal; the passenger can only clear their own.
    private PickupApi.Snapshot dropProposal(Pickup pickup, Pickup.Proposer onlyBy, boolean isOwner) {
        requireSharing(pickup);
        if (pickup.proposalBy != null && onlyBy != null && pickup.proposalBy != onlyBy) {
            throw error(403, "FORBIDDEN", "운전자가 제안한 장소는 운전자만 취소할 수 있습니다.");
        }
        pickup.clearProposal();
        return toPickupResponse(pickup, isOwner);
    }

    public PickupApi.ArrivalPushState subscribeArrivalPush(UUID id, String auth, PickupApi.ArrivalPushRequest request) {
        Pickup pickup = locked(id);
        if (!tokenService.matches(tokenService.bearer(auth), pickup.participantHash)) {
            throw error(403, "FORBIDDEN", "탑승자만 도착 알림을 신청할 수 있습니다.");
        }
        requireSharing(pickup);
        arrivalPush.subscribe(pickup.id, request, pickup.placeVersion, "/?pickup=" + pickup.id);
        return new PickupApi.ArrivalPushState(true);
    }

    public PickupApi.ArrivalPushState unsubscribeArrivalPush(UUID id, String auth) {
        Pickup pickup = locked(id);
        if (!tokenService.matches(tokenService.bearer(auth), pickup.participantHash)) {
            throw error(403, "FORBIDDEN", "탑승자만 도착 알림을 바꿀 수 있습니다.");
        }
        arrivalPush.remove(pickup.id);
        return new PickupApi.ArrivalPushState(false);
    }

    // Account-side variants: RideRequestService has already checked the signed-in passenger and active request.
    public PickupApi.ArrivalPushState subscribeArrivalPushForAccount(UUID id, PickupApi.ArrivalPushRequest request, UUID requestId) {
        Pickup pickup = locked(id);
        requireSharing(pickup);
        arrivalPush.subscribe(pickup.id, request, pickup.placeVersion, "/account?request=" + requestId);
        return new PickupApi.ArrivalPushState(true);
    }

    public PickupApi.ArrivalPushState unsubscribeArrivalPushForAccount(UUID id) {
        arrivalPush.remove(id);
        return new PickupApi.ArrivalPushState(false);
    }

    // Called only after RideRequestService has checked the signed-in passenger and active request.
    PickupApi.Snapshot snapshotForAccount(UUID id) { return toPickupResponse(locked(id), false); }
    PickupApi.VehicleState vehicleForAccount(UUID id) { Pickup pickup=locked(id); requireSharing(pickup); return toVehicleResponse(pickup); }
    public PickupApi.Snapshot readinessForAccount(UUID id, PickupApi.ReadinessRequest request) {
        Pickup pickup=locked(id); requireSharing(pickup);
        if (request.sequence()==pickup.readinessSequence && request.readiness()==pickup.readiness) return toPickupResponse(pickup,false);
        if (request.sequence()<=pickup.readinessSequence) throw error(409,"STALE_EVENT","준비 상태를 다시 확인해주세요.");
        pickup.readiness=request.readiness(); pickup.readinessSequence=request.sequence(); return toPickupResponse(pickup,false);
    }

    public PickupApi.Snapshot sendQuickMessage(UUID id, String auth, String message) {
        String token = tokenService.bearer(auth);
        Pickup pickup = locked(id);
        boolean isOwner = tokenService.matches(token, pickup.ownerHash);
        if (!isOwner && !tokenService.matches(token, pickup.participantHash)) {
            throw error(403, "FORBIDDEN", "이 픽업에 접근할 수 없습니다.");
        }
        return applyQuickMessage(pickup, message, isOwner);
    }

    public PickupApi.Snapshot sendQuickMessageForAccount(UUID id, String message) {
        return applyQuickMessage(locked(id), message, false);
    }

    private PickupApi.Snapshot applyQuickMessage(Pickup pickup, String message, boolean isOwner) {
        requireSharing(pickup);
        String text = message == null ? "" : message.strip();
        if (text.isEmpty()) {
            throw error(400, "EMPTY_MESSAGE", "메시지를 입력해주세요.");
        }
        if (text.length() > 50) {
            text = text.substring(0, 50);
        }
        pickup.quickMessage = text;
        pickup.quickMessageAt = clock.instant();
        return toPickupResponse(pickup, isOwner);
    }

    private Pickup locked(UUID id) {
        Pickup pickup = pickupRepository.findLockedById(id)
                .orElseThrow(() -> error(404, "PICKUP_NOT_FOUND", "픽업을 찾을 수 없습니다."));
        if (pickup.expireIfDue(clock.instant())) arrivalPush.remove(pickup.id);
        return pickup;
    }

    private Pickup owner(UUID id, String auth) {
        String token = tokenService.bearer(auth);
        Pickup pickup = locked(id);
        if (!tokenService.matches(token, pickup.ownerHash)) {
            throw error(403, "FORBIDDEN", "운전자만 이 작업을 할 수 있습니다.");
        }
        return pickup;
    }

    private void requireOpen(Pickup pickup) {
        if (pickup.isTerminal() || pickup.isExpired(clock.instant())) {
            throw error(410, "PICKUP_ENDED", "종료되었거나 만료된 픽업입니다.");
        }
    }

    private void requireSharing(Pickup pickup) {
        requireOpen(pickup);
        if (pickup.status != Pickup.Status.ACTIVE && pickup.status != Pickup.Status.ARRIVED) {
            throw error(403, "APPROVAL_REQUIRED", "운전자 확인 후 이용할 수 있습니다.");
        }
    }

    private PickupApi.Snapshot toPickupResponse(Pickup pickup, boolean isOwner) {
        boolean expired = !pickup.isTerminal() && pickup.isExpired(clock.instant());
        boolean inviteExpired = !pickup.isTerminal()
                && (pickup.status == Pickup.Status.INVITING || pickup.status == Pickup.Status.WAITING_APPROVAL)
                && !clock.instant().isBefore(pickup.inviteExpiresAt);
        boolean visible = !pickup.isTerminal() && !expired && (isOwner
                || pickup.status == Pickup.Status.ACTIVE || pickup.status == Pickup.Status.ARRIVED);
        PickupApi.Place place = visible
                ? new PickupApi.Place(pickup.placeLabel, pickup.placeLatitude, pickup.placeLongitude) : null;
        PickupApi.PlaceProposal proposal = visible && pickup.proposalBy != null
                ? new PickupApi.PlaceProposal(new PickupApi.Place(pickup.proposalLabel, pickup.proposalLatitude,
                pickup.proposalLongitude), pickup.proposalBy, pickup.proposalAt, pickup.proposalId) : null;
        PickupApi.Participant participant = pickup.participantId == null ? null
                : new PickupApi.Participant(pickup.participantId, pickup.participantName,
                pickup.readiness, pickup.readinessSequence, pickup.readinessReceivedSequence);
        String status = expired ? "EXPIRED" : inviteExpired ? "INVITE_EXPIRED" : pickup.status.name();
        PickupApi.QuickMessage quickMessage = (visible && pickup.quickMessage != null)
                ? new PickupApi.QuickMessage(pickup.quickMessage, pickup.quickMessageAt) : null;
        String vehicleName = null;
        if (visible && pickup.vehicleId != null) {
            vehicleName = registeredVehicleRepository.findById(pickup.vehicleId)
                    .map(v -> v.displayName)
                    .orElse("마중 차량");
        } else if (visible) {
            vehicleName = "마중 차량";
        }
        return new PickupApi.Snapshot(pickup.id, status, place, proposal, participant,
                pickup.inviteExpiresAt, pickup.expiresAt, pickup.endedAt, pickup.placeVersion,
                quickMessage, vehicleName);
    }

    private PickupApi.VehicleState toVehicleResponse(Pickup pickup) {
        if (pickup.vehicleSourceTime == null) {
            return new PickupApi.VehicleState("WAITING", null, null, null, null, null, pickup.vehicleSequence, 0, null, pickup.placeVersion);
        }
        // Age uses the older of the SDK time and the server receipt, matching the freshness check.
        Instant observedAt = pickup.vehicleSourceTime.isBefore(pickup.vehicleReceivedAt)
                ? pickup.vehicleSourceTime : pickup.vehicleReceivedAt;
        Duration age = Duration.between(observedAt, clock.instant());
        if (age.isNegative()) age = Duration.ZERO;
        long validForMillis = Math.max(0, vehicleFreshness.minus(age).toMillis());
        boolean fresh = validForMillis > 0;
        // Stale values stay visible briefly so the passenger sees a dimmed last position, then they are withheld.
        boolean lastKnown = age.compareTo(vehicleLastKnown) <= 0;
        return new PickupApi.VehicleState(fresh ? "FRESH" : "STALE",
                lastKnown ? pickup.vehicleLatitude : null, lastKnown ? pickup.vehicleLongitude : null,
                lastKnown ? pickup.durationSeconds : null, pickup.vehicleSourceTime,
                pickup.vehicleReceivedAt, pickup.vehicleSequence, validForMillis, age.toMillis(), pickup.placeVersion);
    }

    private ApiException error(int status, String code, String message) {
        return new ApiException(status, code, message);
    }
}
