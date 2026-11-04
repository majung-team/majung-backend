package app.majung.server.pickup.domain;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
public class RideRequest {
    @Id public UUID id;
    @Version public long version;
    public UUID driverId;
    public UUID passengerId;
    public UUID vehicleId;
    public UUID pickupId;
    public String status;
    public boolean driverInvitation;
    public String placeLabel;
    public double placeLatitude;
    public double placeLongitude;
    public Instant createdAt;
    public Instant expiresAt;
    public Instant endedAt;
    public Long queueOrder;
    public RideRequest() { }
}
