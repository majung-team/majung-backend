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
    record View(UUID requestId, String status, String nickname, String majungId, PickupApi.Place place,
                Instant expiresAt, Integer peopleAhead, Long currentPickupRemainingSeconds, long currentPickupValidForMillis) { }
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
    @PutMapping("/api/v1/ride-requests/{id}/readiness")
    PickupApi.Snapshot readiness(Authentication auth, @PathVariable UUID id, @Valid @RequestBody PickupApi.ReadinessRequest body) {
        return service.readiness(AccountIdentity.requireMember(auth), id, body);
    }
    @GetMapping("/api/v1/vehicles/ride-requests")
    ListView received(@RequestHeader(value="Authorization", required=false) String auth) { return new ListView(service.received(auth)); }
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/accept")
    View accept(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) { return service.accept(auth, id); }
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/reject")
    View reject(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) { return service.reject(auth, id); }
    @PostMapping("/api/v1/vehicles/ride-requests/{id}/start")
    Started start(@RequestHeader(value="Authorization", required=false) String auth, @PathVariable UUID id) { return service.start(auth, id); }
}
