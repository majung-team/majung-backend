package app.majung.server.pickup;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface VehicleAccountChallengeRepository extends JpaRepository<VehicleAccountChallenge, UUID> {
    Optional<VehicleAccountChallenge> findByChallengeId(UUID challengeId);
    @Modifying @Query("delete from VehicleAccountChallenge c where c.claimant.id = :memberId")
    void deleteForMember(UUID memberId);
    @Modifying @Query("delete from VehicleAccountChallenge c where c.expiresAt <= :now")
    void deleteExpired(Instant now);
}
