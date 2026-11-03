package app.majung.server.pickup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
class VehicleAccountChallenge {
    @Id UUID vehicleId;
    @Version long version;
    @Column(nullable = false, unique = true) UUID challengeId;
    @Column(nullable = false, unique = true) String codeHash;
    @Column(nullable = false) Instant expiresAt;
    @ManyToOne @JoinColumn(name = "claimant_id") Member claimant;
    Instant confirmedAt;
    protected VehicleAccountChallenge() { }
}
