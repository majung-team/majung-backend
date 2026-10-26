package app.majung.server.pickup;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

/** One browser subscription per pickup. The endpoint is a capability URL: never log or return it. */
@Entity
class ArrivalPush {
    @Id
    UUID pickupId;
    String endpoint;
    Instant createdAt;
    Instant notifiedAt;

    protected ArrivalPush() { }

    ArrivalPush(UUID pickupId, String endpoint, Instant createdAt) {
        this.pickupId = pickupId;
        this.endpoint = endpoint;
        this.createdAt = createdAt;
    }
}
