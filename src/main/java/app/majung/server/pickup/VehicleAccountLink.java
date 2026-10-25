package app.majung.server.pickup;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
class VehicleAccountLink {
    @Id UUID vehicleId;
    @Version long version;
    @ManyToOne(optional = false) @JoinColumn(name = "member_id") Member member;
    Instant linkedAt;
    protected VehicleAccountLink() { }
}
