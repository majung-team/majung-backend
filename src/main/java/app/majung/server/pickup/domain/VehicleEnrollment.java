package app.majung.server.pickup;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
class VehicleEnrollment {
    @Id UUID id;
    @Version long version;
    @Column(nullable = false, unique = true) String deviceHash;
    @Column(nullable = false, unique = true) String codeHash;
    @Column(nullable = false) Instant expiresAt;
    UUID claimantId;
    Instant confirmedAt;
    protected VehicleEnrollment() { }
}
