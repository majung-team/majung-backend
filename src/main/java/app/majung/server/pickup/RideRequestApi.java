package app.majung.server.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
class RideRequestApi {
    record Submit(@NotNull UUID driverId, @NotNull @Valid PickupApi.Place place, @NotNull UUID requestId) { }
    record Invite(@NotNull UUID connectionId, @NotNull @Valid PickupApi.Place place, @NotNull UUID requestId) { }
    record Contacts(List<Contact> items) { }
    record Contact(UUID connectionId, String nickname, String majungId) { }
    record View(UUID requestId, String status, String nickname, String majungId, PickupApi.Place place,
                Instant expiresAt, Integer peopleAhead, Long currentPickupRemainingSeconds, long currentPickupValidForMillis, boolean driverInvitation) { }
    record ListView(List<View> requests) { }
    record Started(UUID pickupId, String driverToken, PickupApi.Snapshot pickup) { }
    record Tracking(PickupApi.Snapshot pickup, PickupApi.VehicleState vehicle) { }
    private final RideRequestService service;
    RideRequestApi(RideRequestService service) { this.service = service; }
    @PostMapping("/api/v1/ride-requests")
    View submit(Authentication auth, @Valid @RequestBody Submit body) { return service.submit(AccountIdentity.requireMember(auth), body); }
    @GetMapping("/api/v1/ride-requests")
    ListView list(Authentication auth) { return new ListView(service.passenger(AccountIdentity.requireMember(auth))); }
    @PostMapping("/api/v1/ride-requests/{id}/cancel")
    View cancel(Authentication auth, @PathVariable UUID id) { return service.cancel(AccountIdentity.requireMember(auth), id); }
    @GetMapping("/api/v1/ride-requests/{id}/tracking")
    Tracking tracking(Authentication auth, @PathVariable UUID id) { return service.tracking(AccountIdentity.requireMember(auth), id); }
    @PutMapping("/api/v1/ride-requests/{id}/arrival-push")
    PickupApi.ArrivalPushState subscribeArrivalPush(Authentication auth, @PathVariable UUID id, @Valid @RequestBody PickupApi.ArrivalPushRequest body) {
        return service.subscribeArrivalPush(AccountIdentity.requireMember(auth), id, body.endpoint());
    }
    @DeleteMapping("/api/v1/ride-requests/{id}/arrival-push")
    PickupApi.ArrivalPushState unsubscribeArrivalPush(Authentication auth, @PathVariable UUID id) {
        return service.unsubscribeArrivalPush(AccountIdentity.requireMember(auth), id);
    }
    @PutMapping("/api/v1/ride-requests/{id}/place-proposal")
    PickupApi.Snapshot proposePlace(Authentication auth, @PathVariable UUID id, @Valid @RequestBody PickupApi.CreateRequest body) {
        return service.proposePlace(AccountIdentity.requireMember(auth), id, body.place());
    }
    @DeleteMapping("/api/v1/ride-requests/{id}/place-proposal")
    PickupApi.Snapshot withdrawPlaceProposal(Authentication auth, @PathVariable UUID id) {
        return service.withdrawPlaceProposal(AccountIdentity.requireMember(auth), id);
    }
    @PutMapping("/api/v1/ride-requests/{id}/readiness")
    PickupApi.Snapshot readiness(Authentication auth, @PathVariable UUID id, @Valid @RequestBody PickupApi.ReadinessRequest body) {
        return service.readiness(AccountIdentity.requireMember(auth), id, body);
    }
    @GetMapping("/api/v1/vehicles/connections")
    Contacts contacts(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestParam(defaultValue="0") int page) { return new Contacts(service.contacts(auth, page)); }
    @PostMapping("/api/v1/vehicles/invitations")
    View invite(@RequestHeader(value="Authorization", required=false) String auth, @Valid @RequestBody Invite body) {
        return service.invite(auth, body);
    }
    @PostMapping("/api/v1/ride-requests/{id}/accept-invitation")
    View acceptInvitation(Authentication auth, @PathVariable UUID id) { return service.answerInvitation(AccountIdentity.requireMember(auth), id, true); }
    @PostMapping("/api/v1/ride-requests/{id}/reject-invitation")
    View rejectInvitation(Authentication auth, @PathVariable UUID id) { return service.answerInvitation(AccountIdentity.requireMember(auth), id, false); }
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/cancel-invitation")
    View cancelInvitation(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) { return service.cancelInvitation(auth, id); }
    @GetMapping("/api/v1/vehicles/ride-requests")
    ListView received(@RequestHeader(value="Authorization", required=false) String auth) { return new ListView(service.received(auth)); }
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/accept")
    View accept(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) { return service.accept(auth, id); }
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/reject")
    View reject(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) { return service.reject(auth, id); }
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/start")
    Started start(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) { return service.start(auth, id); }
}
