package app.majung.server.pickup.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicle_passenger_trust")
public class VehiclePassengerTrust {
    @Id public UUID id;
    @Version public long version;
    @Column(nullable = false) public UUID vehicleId;
    @Column(nullable = false) public UUID passengerId;
    @Column(nullable = false) public String status;
    @Column(nullable = false) public Instant updatedAt;

    public VehiclePassengerTrust() { }
}
