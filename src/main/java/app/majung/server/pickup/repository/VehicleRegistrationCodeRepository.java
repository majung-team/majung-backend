package app.majung.server.pickup;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface VehicleRegistrationCodeRepository extends JpaRepository<VehicleRegistrationCode, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from VehicleRegistrationCode c where c.codeHash = :hash")
    Optional<VehicleRegistrationCode> findLockedByCodeHash(String hash);
}
