package app.majung.server.pickup;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface MemberRepository extends JpaRepository<Member, UUID> {
    Optional<Member> findByProviderAndProviderSubject(String provider, String providerSubject);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> lockById(@Param("id") UUID id);
    @Query("select m from Member m where m.profileReady = true and m.id <> :self "
            + "and not exists (select u from KakaoUnlink u where u.providerSubject = m.providerSubject) "
            + "and (m.normalizedNickname like :query escape '!' or m.majungId = :exactId) order by m.id")
    List<Member> search(@Param("self") UUID self, @Param("query") String query,
                        @Param("exactId") String exactId, Pageable pageable);
}
