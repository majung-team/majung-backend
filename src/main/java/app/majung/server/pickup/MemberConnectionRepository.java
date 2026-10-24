package app.majung.server.pickup;

import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface MemberConnectionRepository extends JpaRepository<MemberConnection, UUID> {
    @Modifying
    @Query("delete from MemberConnection c where c.firstMember = :member or c.secondMember = :member")
    void deleteForMember(@Param("member") UUID member);
    Optional<MemberConnection> findByFirstMemberAndSecondMember(UUID firstMember, UUID secondMember);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MemberConnection c where c.id = :id")
    Optional<MemberConnection> lockById(@Param("id") UUID id);
    @Query("select c from MemberConnection c where (c.requester = :self or c.recipient = :self) "
            + "and c.status in ('PENDING', 'ACCEPTED') "
            + "and not exists (select m.id from Member m, KakaoUnlink u where m.providerSubject = u.providerSubject "
            + "and (m.id = c.firstMember or m.id = c.secondMember)) order by c.updatedAt desc, c.id")
    List<MemberConnection> listVisible(@Param("self") UUID self, Pageable pageable);
}
