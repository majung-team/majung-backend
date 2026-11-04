package app.majung.server.pickup.repository;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface VehicleAccountChallengeRepository extends JpaRepository<VehicleAccountChallenge, UUID> {
    Optional<VehicleAccountChallenge> findByChallengeId(UUID challengeId);
    @Modifying @Query("delete from VehicleAccountChallenge c where c.claimant.id = :memberId")
    void deleteForMember(UUID memberId);
    @Modifying @Query("delete from VehicleAccountChallenge c where c.expiresAt <= :now")
    void deleteExpired(Instant now);
}
