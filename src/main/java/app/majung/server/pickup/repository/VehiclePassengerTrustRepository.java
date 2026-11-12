package app.majung.server.pickup.repository;

import app.majung.server.pickup.domain.VehiclePassengerTrust;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VehiclePassengerTrustRepository extends JpaRepository<VehiclePassengerTrust, UUID> {
    Optional<VehiclePassengerTrust> findByVehicleIdAndPassengerId(UUID vehicleId, UUID passengerId);

    List<VehiclePassengerTrust> findByPassengerIdAndStatusOrderByUpdatedAtDesc(UUID passengerId, String status);
}
