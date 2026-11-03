package app.majung.server.pickup;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

interface VehicleEnrollmentRepository extends JpaRepository<VehicleEnrollment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from VehicleEnrollment e where e.id = :id")
    Optional<VehicleEnrollment> lockById(UUID id);
    @Modifying @Query("delete from VehicleEnrollment e where e.expiresAt <= :before")
    void deleteExpired(Instant before);
}
