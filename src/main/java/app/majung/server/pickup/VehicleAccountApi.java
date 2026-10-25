package app.majung.server.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

@RestController
class VehicleAccountApi {
    record Challenge(UUID challengeId, String code, Instant expiresAt) { }
    record Claim(@NotNull UUID challengeId, @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String code) { }
    record Confirm(@NotNull UUID challengeId) { }
    record DeviceView(String status, UUID challengeId, String nickname, String majungId, Instant expiresAt) { }
    record RequestView(UUID challengeId, UUID vehicleId, String vehicleName, String status, Instant expiresAt) { }
    record LinkView(UUID vehicleId, String vehicleName, Instant linkedAt) { }
    private final VehicleAccountService service;
    VehicleAccountApi(VehicleAccountService service) { this.service = service; }

    @PostMapping("/api/v1/vehicles/account-link/challenges")
    Challenge issue(@RequestHeader(value = "Authorization", required = false) String authorization) { return service.issue(authorization); }
    @GetMapping("/api/v1/vehicles/account-link")
    DeviceView state(@RequestHeader(value = "Authorization", required = false) String authorization) { return service.device(authorization); }
    @PostMapping("/api/v1/vehicles/account-link/confirm")
    DeviceView confirm(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @Valid @RequestBody Confirm request) { return service.confirm(authorization, request.challengeId()); }
    @DeleteMapping("/api/v1/vehicles/account-link") @ResponseStatus(HttpStatus.NO_CONTENT)
    void detachDevice(@RequestHeader(value = "Authorization", required = false) String authorization) { service.detachDevice(authorization); }
    @GetMapping("/api/v1/vehicle-links")
    List<LinkView> links(Authentication authentication) { return service.links(AccountIdentity.requireMember(authentication)); }
    @PostMapping("/api/v1/vehicle-links/requests")
    RequestView claim(Authentication authentication, @Valid @RequestBody Claim request) {
        return service.claim(AccountIdentity.requireMember(authentication), request);
    }
    @GetMapping("/api/v1/vehicle-links/requests/{id}")
    RequestView request(Authentication authentication, @PathVariable UUID id) { return service.request(AccountIdentity.requireMember(authentication), id); }
    @PostMapping("/api/v1/vehicle-links/requests/{id}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(Authentication authentication, @PathVariable UUID id) { service.cancel(AccountIdentity.requireMember(authentication), id); }
    @DeleteMapping("/api/v1/vehicle-links/{vehicleId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void detach(Authentication authentication, @PathVariable UUID vehicleId) { service.detach(AccountIdentity.requireMember(authentication), vehicleId); }
}
