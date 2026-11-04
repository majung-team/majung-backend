package app.majung.server.pickup.domain;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
public class VehicleAccountLink {
    @Id public UUID vehicleId;
    @Version public long version;
    @ManyToOne(optional = false) @JoinColumn(name = "member_id") public Member member;
    public Instant linkedAt;
    public VehicleAccountLink() { }
}
