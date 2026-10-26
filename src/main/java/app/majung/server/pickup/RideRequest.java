package app.majung.server.pickup;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
class RideRequest {
    @Id UUID id;
    @Version long version;
    UUID driverId;
    UUID passengerId;
    UUID vehicleId;
    UUID pickupId;
    String status;
    boolean driverInvitation;
    String placeLabel;
    double placeLatitude;
    double placeLongitude;
    Instant createdAt;
    Instant expiresAt;
    Long queueOrder;
    protected RideRequest() { }
}
