package app.majung.server.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class VehicleRegistrationApi {
    record IssuedCode(UUID registrationCodeId, String code, Instant expiresAt) { }
    record CodeStatus(UUID registrationCodeId, Instant expiresAt, Instant consumedAt, UUID vehicleId) { }
    record Registration(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String code,
                        @NotBlank @Size(max = 32) String displayName) { }
    record Registered(UUID vehicleId, String vehicleToken) { }
    record VehicleInfo(UUID vehicleId, String displayName, Instant registeredAt, Instant revokedAt) { }
    private final DeploymentPolicy deploymentPolicy;
    private final VehicleRegistrationService vehicleRegistrationService;

    VehicleRegistrationApi(DeploymentPolicy deploymentPolicy, VehicleRegistrationService vehicleRegistrationService) {
        this.deploymentPolicy = deploymentPolicy;
        this.vehicleRegistrationService = vehicleRegistrationService;
    }

    @PostMapping(value = "/api/v1/admin/vehicle-registration-codes", produces = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedCode issue(@RequestHeader(value = "Authorization", required = false) String authorization) {
        deploymentPolicy.requireAdmin(authorization);
        return vehicleRegistrationService.issueCode();
    }

    @GetMapping(value = "/api/v1/admin/vehicle-registration-codes/{id}", produces = "application/json")
    CodeStatus codeStatus(@PathVariable UUID id,
                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        deploymentPolicy.requireAdmin(authorization);
        return vehicleRegistrationService.codeStatus(id);
    }

    @PostMapping(value = "/api/v1/vehicles/registrations", consumes = "application/json", produces = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    Registered register(@Valid @RequestBody Registration request) {
        return vehicleRegistrationService.register(request);
    }

    @GetMapping(value = "/api/v1/vehicles/me", produces = "application/json")
    VehicleInfo current(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return vehicleRegistrationService.current(authorization);
    }

    @PostMapping(value = "/api/v1/admin/vehicles/{id}/revocation", produces = "application/json")
    VehicleInfo revoke(@PathVariable UUID id,
                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        deploymentPolicy.requireAdmin(authorization);
        return vehicleRegistrationService.revoke(id);
    }
}
