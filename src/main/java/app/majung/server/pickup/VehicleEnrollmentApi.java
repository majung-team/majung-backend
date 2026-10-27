package app.majung.server.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
class VehicleEnrollmentApi {
    record Started(UUID enrollmentId, String deviceToken, String code, Instant expiresAt) { }
    record Claim(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String code) { }
    record Confirm(@NotNull String majungId) { }
    record State(UUID enrollmentId, String status, String nickname, String majungId, Instant expiresAt) { }
    private final VehicleEnrollmentService service;
    VehicleEnrollmentApi(VehicleEnrollmentService service) { this.service = service; }

    @PostMapping("/api/v1/vehicles/enrollments")
    Started start() { return service.start(); }
    @GetMapping("/api/v1/vehicles/enrollments/{id}")
    State device(@PathVariable UUID id, @RequestHeader(value="Authorization", required=false) String authorization) {
        return service.device(id, authorization);
    }
    @PostMapping("/api/v1/vehicles/enrollments/{id}/confirm")
    State confirm(@PathVariable UUID id, @RequestHeader(value="Authorization", required=false) String authorization,
                  @Valid @RequestBody Confirm request) { return service.confirm(id, authorization, request.majungId()); }
    @PostMapping("/api/v1/vehicles/enrollments/{id}/cancel")
    State cancelDevice(@PathVariable UUID id, @RequestHeader(value="Authorization", required=false) String authorization) {
        return service.cancelDevice(id, authorization);
    }
    @PostMapping("/api/v1/vehicle-links/enrollments/{id}")
    State claim(Authentication authentication, @PathVariable UUID id, @Valid @RequestBody Claim request) {
        return service.claim(AccountIdentity.requireMember(authentication), id, request.code());
    }
    @GetMapping("/api/v1/vehicle-links/enrollments/{id}")
    State account(Authentication authentication, @PathVariable UUID id) {
        return service.account(AccountIdentity.requireMember(authentication), id);
    }
    @PostMapping("/api/v1/vehicle-links/enrollments/{id}/cancel")
    State cancelAccount(Authentication authentication, @PathVariable UUID id) {
        return service.cancelAccount(AccountIdentity.requireMember(authentication), id);
    }
}
