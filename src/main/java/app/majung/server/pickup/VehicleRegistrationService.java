package app.majung.server.pickup;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class VehicleRegistrationService {
    private final RegisteredVehicleRepository registeredVehicleRepository;
    private final VehicleRegistrationCodeRepository vehicleRegistrationCodeRepository;
    private final PickupRepository pickupRepository;
    private final TokenService tokenService;
    private final Clock clock;

    VehicleRegistrationService(RegisteredVehicleRepository registeredVehicleRepository,
                               VehicleRegistrationCodeRepository vehicleRegistrationCodeRepository,
                               PickupRepository pickupRepository, TokenService tokenService, Clock clock) {
        this.registeredVehicleRepository = registeredVehicleRepository;
        this.vehicleRegistrationCodeRepository = vehicleRegistrationCodeRepository;
        this.pickupRepository = pickupRepository;
        this.tokenService = tokenService;
        this.clock = clock;
    }

    VehicleRegistrationApi.IssuedCode issueCode() {
        String code = tokenService.issue();
        VehicleRegistrationCode record = new VehicleRegistrationCode();
        record.id = UUID.randomUUID();
        record.codeHash = tokenService.hash(code);
        record.expiresAt = clock.instant().plus(Duration.ofMinutes(10));
        vehicleRegistrationCodeRepository.save(record);
        return new VehicleRegistrationApi.IssuedCode(record.id, code, record.expiresAt);
    }

    VehicleRegistrationApi.Registered register(VehicleRegistrationApi.Registration request) {
        VehicleRegistrationCode code = vehicleRegistrationCodeRepository.findLockedByCodeHash(tokenService.hash(request.code()))
                .orElseThrow(VehicleRegistrationService::unavailableCode);
        if (code.consumedAt != null || !clock.instant().isBefore(code.expiresAt)) throw unavailableCode();
        code.consumedAt = clock.instant();
        String credential = tokenService.issue();
        RegisteredVehicle vehicle = new RegisteredVehicle();
        vehicle.id = UUID.randomUUID();
        vehicle.credentialHash = tokenService.hash(credential);
        vehicle.displayName = request.displayName().strip();
        vehicle.registeredAt = clock.instant();
        registeredVehicleRepository.save(vehicle);
        code.vehicleId = vehicle.id;
        return new VehicleRegistrationApi.Registered(vehicle.id, credential);
    }

    VehicleRegistrationApi.CodeStatus codeStatus(UUID id) {
        VehicleRegistrationCode code = vehicleRegistrationCodeRepository.findById(id)
                .orElseThrow(() -> new ApiException(404, "REGISTRATION_CODE_NOT_FOUND", "등록 코드 기록을 찾을 수 없습니다."));
        return new VehicleRegistrationApi.CodeStatus(code.id, code.expiresAt, code.consumedAt, code.vehicleId);
    }

    RegisteredVehicle requireActive(String authorization) {
        if (authorization == null) throw new ApiException(401, "VEHICLE_REGISTRATION_REQUIRED", "차량 최초 등록이 필요합니다.");
        String credential = tokenService.bearer(authorization);
        RegisteredVehicle vehicle = registeredVehicleRepository.findLockedByCredentialHash(tokenService.hash(credential))
                .orElseThrow(() -> new ApiException(403, "VEHICLE_CREDENTIAL_INVALID", "차량 등록 권한을 확인할 수 없습니다."));
        if (vehicle.revokedAt != null) {
            throw new ApiException(403, "VEHICLE_REVOKED", "차량 권한이 철회되었습니다. 관리자에게 새 등록 코드를 요청해주세요.");
        }
        return vehicle;
    }

    VehicleRegistrationApi.VehicleInfo current(String authorization) {
        RegisteredVehicle vehicle = requireActive(authorization);
        return new VehicleRegistrationApi.VehicleInfo(vehicle.id, vehicle.displayName, vehicle.registeredAt, vehicle.revokedAt);
    }

    VehicleRegistrationApi.VehicleInfo revoke(UUID id) {
        RegisteredVehicle vehicle = registeredVehicleRepository.findLockedById(id)
                .orElseThrow(() -> new ApiException(404, "VEHICLE_NOT_FOUND", "등록된 차량을 찾을 수 없습니다."));
        if (vehicle.revokedAt == null) {
            vehicle.revokedAt = clock.instant();
            for (Pickup pickup : pickupRepository.findLockedByVehicleId(id)) {
                if (!pickup.isTerminal()) {
                    pickup.status = Pickup.Status.CANCELLED;
                    pickup.endedAt = clock.instant();
                    pickup.inviteHash = null;
                    pickup.clearVehicle();
                }
            }
        }
        return new VehicleRegistrationApi.VehicleInfo(vehicle.id, vehicle.displayName, vehicle.registeredAt, vehicle.revokedAt);
    }

    private static ApiException unavailableCode() {
        return new ApiException(410, "REGISTRATION_CODE_UNAVAILABLE", "등록 코드가 만료되었거나 이미 사용됐습니다. 새 코드를 요청해주세요.");
    }
}
