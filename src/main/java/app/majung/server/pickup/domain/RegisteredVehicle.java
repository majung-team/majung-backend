package app.majung.server.pickup.domain;

import app.majung.server.pickup.service.VehicleCodeGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
public class RegisteredVehicle {
    @Id public UUID id;
    @Version public long version;
    @Column(nullable = false, unique = true) public String credentialHash;
    @Column(nullable = false, unique = true) public String vehicleCode;
    @Column(nullable = false) public String displayName;
    public String vehicleModel;
    public String licensePlate;
    public String ownerHint;
    @Column(nullable = false) public boolean directRequestEnabled = true;
    @Column(nullable = false) public Instant registeredAt;
    public Instant revokedAt;
    public RegisteredVehicle() { }

    @PrePersist
    void assignDefaultCode() {
        if (vehicleCode == null || vehicleCode.isBlank()) {
            vehicleCode = VehicleCodeGenerator.randomCode();
        }
    }

    public String summaryDisplayName() {
        String model = vehicleModel == null ? "" : vehicleModel.strip();
        String plate = licensePlate == null ? "" : licensePlate.strip();
        if (!model.isEmpty() && !plate.isEmpty()) return model + " · " + plate;
        if (!model.isEmpty()) return model;
        if (!plate.isEmpty()) return plate;
        return displayName;
    }
}
