package app.majung.server.pickup.domain;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
public class VehicleRegistrationCode {
    @Id public UUID id;
    @Version public long version;
    @Column(nullable = false, unique = true) public String codeHash;
    @Column(nullable = false) public Instant expiresAt;
    public Instant consumedAt;
    public UUID vehicleId;
    public VehicleRegistrationCode() { }
}
