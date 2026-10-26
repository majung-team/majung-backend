package app.majung.server.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/pickups", produces = "application/json")
class PickupApi {
    record Place(@NotBlank @Size(max = 120) String label,
                 @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                 @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) { }
    record CreateRequest(@Valid @NotNull Place place) { }
    record JoinRequest(@NotBlank @Size(min = 43, max = 43) String inviteToken,
                       @Size(max = 12) String displayName) { }
    record ReadinessRequest(@NotNull Pickup.Readiness readiness, @Positive long sequence) { }
    record ReadinessReceipt(@NotNull UUID participantId, @Positive long sequence) { }
    record PlaceVersionRequest(@NotNull @Positive Long placeVersion) { }
    record VehicleInvalidation(@Positive long sequence, @Positive Long placeVersion) {
        VehicleInvalidation(long sequence) { this(sequence, null); }
    }
    record PlaceConfirmation(@NotNull UUID proposalId, @Positive long expectedPlaceVersion,
                             @Valid @NotNull Place place, @NotNull @PositiveOrZero Long durationSeconds,
                             @NotNull Instant sourceTime) { }
    record VehicleRequest(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                          @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                          @NotNull @PositiveOrZero Long durationSeconds,
                          @NotNull Instant sourceTime, @Positive long sequence, @Positive Long placeVersion) {
        VehicleRequest(Double latitude, Double longitude, Long durationSeconds, Instant sourceTime, long sequence) {
            this(latitude, longitude, durationSeconds, sourceTime, sequence, null);
        }
    }
    record Created(UUID pickupId, String driverToken, String inviteToken,
                   Instant inviteExpiresAt, Instant expiresAt) { }
    record Invitation(String inviteToken, Instant expiresAt) { }
    record ArrivalPushRequest(@NotBlank @Size(max = 1024) String endpoint) { }
    record ArrivalPushState(boolean subscribed) { }
    record Joined(UUID participantId, String participantToken, String status) { }
    record Participant(UUID participantId, String displayName, Pickup.Readiness readiness,
                       long readinessSequence, long readinessReceivedSequence) { }
    record PlaceProposal(Place place, Pickup.Proposer proposedBy, Instant proposedAt, UUID proposalId) { }
    record Snapshot(UUID pickupId, String status, Place place, PlaceProposal placeProposal, Participant participant,
                    Instant inviteExpiresAt, Instant expiresAt, Instant endedAt, long placeVersion) { }
    record VehicleState(String freshness, Double latitude, Double longitude, Long durationSeconds,
                        Instant sourceTime, Instant receivedAt, long sequence, long validForMillis,
                        Long ageMillis, long placeVersion) { }

    private final PickupService pickupService;
    private final DeploymentPolicy deploymentPolicy;

    PickupApi(PickupService pickupService, DeploymentPolicy deploymentPolicy) {
        this.pickupService = pickupService;
        this.deploymentPolicy = deploymentPolicy;
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@Valid @RequestBody CreateRequest request,
                   @RequestHeader(value = "Authorization", required = false) String authorization) {
        return pickupService.createPickup(request.place(), authorization);
    }

    @PostMapping(value = "/{id}/participants", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    Joined join(@PathVariable UUID id, @Valid @RequestBody JoinRequest request) {
        return pickupService.joinPickup(id, request);
    }

    @PostMapping("/{id}/invitations")
    Invitation rotate(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.rotateInvitation(id, auth);
    }

    @PutMapping(value = "/{id}/arrival-push", consumes = "application/json")
    ArrivalPushState subscribeArrivalPush(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth,
                                          @Valid @RequestBody ArrivalPushRequest request) {
        return pickupService.subscribeArrivalPush(id, auth, request.endpoint());
    }

    @DeleteMapping("/{id}/arrival-push")
    ArrivalPushState unsubscribeArrivalPush(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.unsubscribeArrivalPush(id, auth);
    }

    @DeleteMapping("/{id}/participant")
    Invitation disconnect(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.disconnectParticipant(id, auth);
    }

    @PostMapping("/{id}/participants/{participantId}/approval")
    Snapshot approve(@PathVariable UUID id, @PathVariable UUID participantId,
                     @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.approveParticipant(id, participantId, auth);
    }

    @GetMapping("/{id}")
    Snapshot get(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.getPickup(id, auth);
    }

    @PutMapping(value = "/{id}/readiness", consumes = "application/json")
    Snapshot readiness(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth,
                       @Valid @RequestBody ReadinessRequest request) {
        return pickupService.updateReadiness(id, auth, request);
    }

    @PutMapping(value = "/{id}/place-confirmation", consumes = "application/json")
    Snapshot confirmPlace(@PathVariable UUID id,
                          @RequestHeader(value = "Authorization", required = false) String auth,
                          @Valid @RequestBody PlaceConfirmation request) {
        return pickupService.confirmPlace(id, auth, request);
    }

    @PutMapping(value = "/{id}/readiness-receipt", consumes = "application/json")
    Snapshot receiveReadiness(@PathVariable UUID id,
                             @RequestHeader(value = "Authorization", required = false) String auth,
                             @Valid @RequestBody ReadinessReceipt request) {
        return pickupService.receiveReadiness(id, auth, request);
    }

    @PutMapping(value = "/{id}/vehicle-state", consumes = "application/json")
    VehicleState vehicle(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth,
                         @Valid @RequestBody VehicleRequest request) {
        return pickupService.updateVehicle(id, auth, request);
    }

    @DeleteMapping(value = "/{id}/vehicle-state", consumes = "application/json")
    VehicleState invalidateVehicle(@PathVariable UUID id,
                                  @RequestHeader(value = "Authorization", required = false) String auth,
                                  @Valid @RequestBody VehicleInvalidation request) {
        return pickupService.invalidateVehicle(id, auth, request.sequence(), request.placeVersion());
    }

    @GetMapping("/{id}/vehicle-state")
    VehicleState vehicle(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.getVehicle(id, auth);
    }

    @PutMapping(value = "/{id}/place-proposal", consumes = "application/json")
    Snapshot proposePlace(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth,
                          @Valid @RequestBody CreateRequest request) {
        return pickupService.proposePlace(id, auth, request.place());
    }

    @DeleteMapping("/{id}/place-proposal")
    Snapshot withdrawPlaceProposal(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.withdrawPlaceProposal(id, auth);
    }

    @PostMapping("/{id}/arrival")
    Snapshot arrive(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth,
                    @Valid @RequestBody(required = false) PlaceVersionRequest request) {
        return pickupService.arrivePickup(id, auth, request == null ? null : request.placeVersion());
    }

    @PostMapping("/{id}/completion")
    Snapshot complete(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.endPickup(id, auth, Pickup.Status.COMPLETED);
    }

    @PostMapping("/{id}/cancellation")
    Snapshot cancel(@PathVariable UUID id, @RequestHeader(value = "Authorization", required = false) String auth) {
        return pickupService.endPickup(id, auth, Pickup.Status.CANCELLED);
    }
}
