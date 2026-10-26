package app.majung.server.pickup;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RideRequestRepository extends JpaRepository<RideRequest, UUID> {
    List<RideRequest> findByDriverIdOrderByCreatedAtAscIdAsc(UUID driverId);
    @Query("select r from RideRequest r where r.passengerId = :self order by r.createdAt desc, r.id")
    List<RideRequest> passengerRequests(UUID self, Pageable page);
    @Query("select r from RideRequest r where r.driverId = :member or r.passengerId = :member")
    List<RideRequest> forMember(UUID member);
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from RideRequest r where (r.pickupId is null and coalesce(r.endedAt, r.expiresAt) <= :cutoff) or (r.pickupId is not null and r.expiresAt <= :cutoff and not exists (select p.id from Pickup p where p.id = r.pickupId)) or exists (select p.id from Pickup p where p.id = r.pickupId and (p.endedAt <= :cutoff or (p.endedAt is null and p.expiresAt <= :cutoff)))")
    int deleteRetainedBefore(java.time.Instant cutoff);
}

