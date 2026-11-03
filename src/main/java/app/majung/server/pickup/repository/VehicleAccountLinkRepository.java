package app.majung.server.pickup;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface VehicleAccountLinkRepository extends JpaRepository<VehicleAccountLink, UUID> {
    List<VehicleAccountLink> findByMemberIdOrderByLinkedAtDesc(UUID memberId);
    @Modifying @Query("delete from VehicleAccountLink l where l.member.id = :memberId")
    void deleteForMember(UUID memberId);
}
