package app.majung.server.pickup;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface ArrivalPushRepository extends JpaRepository<ArrivalPush, UUID> {
    /** Atomic once-only claim: exactly one caller sees 1 for a pickup, however many updates race. */
    @Modifying
    @Query("update ArrivalPush a set a.notifiedAt = :now where a.pickupId = :pickupId and a.notifiedAt is null and a.placeVersion = :placeVersion")
    int claim(UUID pickupId, Instant now, long placeVersion);

    boolean existsByPickupIdAndPlaceVersionAndEndpointAndNotifiedAtIsNotNull(UUID pickupId, long placeVersion, String endpoint);

    @Modifying
    @Query("delete from ArrivalPush a where a.pickupId = :pickupId and a.placeVersion = :placeVersion and a.endpoint = :endpoint")
    int deleteGone(UUID pickupId, long placeVersion, String endpoint);

    @Modifying
    @Query("delete from ArrivalPush a where a.pickupId = :pickupId")
    int deleteByPickup(UUID pickupId);

    @Modifying
    @Query("delete from ArrivalPush a where a.createdAt <= :cutoff")
    int deleteCreatedBefore(Instant cutoff);
}
