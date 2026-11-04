package app.majung.server.pickup.repository;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface VehicleAccountLinkRepository extends JpaRepository<VehicleAccountLink, UUID> {
    List<VehicleAccountLink> findByMemberIdOrderByLinkedAtDesc(UUID memberId);
    @Modifying @Query("delete from VehicleAccountLink l where l.member.id = :memberId")
    void deleteForMember(UUID memberId);
}
