package app.majung.server.pickup;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
class VehicleRegistrationCode {
    @Id UUID id;
    @Version long version;
    @Column(nullable = false, unique = true) String codeHash;
    @Column(nullable = false) Instant expiresAt;
    Instant consumedAt;
    UUID vehicleId;
    protected VehicleRegistrationCode() { }
}
