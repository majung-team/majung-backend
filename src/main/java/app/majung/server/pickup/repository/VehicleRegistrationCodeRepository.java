package app.majung.server.pickup.repository;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface VehicleRegistrationCodeRepository extends JpaRepository<VehicleRegistrationCode, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from VehicleRegistrationCode c where c.codeHash = :hash")
    Optional<VehicleRegistrationCode> findLockedByCodeHash(String hash);
}
