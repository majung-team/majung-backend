package app.majung.server.pickup;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
class RegisteredVehicle {
    @Id UUID id;
    @Version long version;
    @Column(nullable = false, unique = true) String credentialHash;
    @Column(nullable = false) String displayName;
    @Column(nullable = false) Instant registeredAt;
    Instant revokedAt;
    protected RegisteredVehicle() { }
}
