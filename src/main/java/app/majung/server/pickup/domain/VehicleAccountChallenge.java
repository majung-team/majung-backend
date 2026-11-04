package app.majung.server.pickup.domain;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
public class VehicleAccountChallenge {
    @Id public UUID vehicleId;
    @Version public long version;
    @Column(nullable = false, unique = true) public UUID challengeId;
    @Column(nullable = false, unique = true) public String codeHash;
    @Column(nullable = false) public Instant expiresAt;
    @ManyToOne @JoinColumn(name = "claimant_id") public Member claimant;
    public Instant confirmedAt;
    public VehicleAccountChallenge() { }
}
