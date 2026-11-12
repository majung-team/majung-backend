package app.majung.server.pickup.repository;

import app.majung.server.pickup.domain.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RegisteredVehicleRepository extends JpaRepository<RegisteredVehicle, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from RegisteredVehicle v where v.credentialHash = :hash")
    Optional<RegisteredVehicle> findLockedByCredentialHash(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from RegisteredVehicle v where v.id = :id")
    Optional<RegisteredVehicle> findLockedById(UUID id);

    Optional<RegisteredVehicle> findByVehicleCode(String vehicleCode);

    boolean existsByVehicleCode(String vehicleCode);
}
