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
    @Query("delete from RideRequest r where r.expiresAt <= :cutoff")
    int deleteRetainedBefore(java.time.Instant cutoff);
}

