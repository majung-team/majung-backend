package app.majung.server.pickup.repository;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface VehicleEnrollmentRepository extends JpaRepository<VehicleEnrollment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from VehicleEnrollment e where e.id = :id")
    Optional<VehicleEnrollment> lockById(UUID id);
    @Modifying @Query("delete from VehicleEnrollment e where e.expiresAt <= :before")
    void deleteExpired(Instant before);
}
