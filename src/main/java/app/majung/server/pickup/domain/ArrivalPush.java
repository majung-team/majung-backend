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
import java.time.Instant;
import java.util.UUID;

/** One browser subscription per pickup. The endpoint is a capability URL: never log or return it. */
@Entity
public class ArrivalPush {
    @Id
    public UUID pickupId;
    public long placeVersion = 1;
    public String endpoint;
    public Instant createdAt;
    public Instant notifiedAt;
    public Instant arrivedNotifiedAt;
    public String p256dh;
    public String auth;
    public String targetPath;

    public ArrivalPush() { }

    public ArrivalPush(UUID pickupId, String endpoint, Instant createdAt) {
        this.pickupId = pickupId;
        this.endpoint = endpoint;
        this.createdAt = createdAt;
    }
}
