package app.majung.server.pickup;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface KakaoUnlinkRepository extends JpaRepository<KakaoUnlink, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from KakaoUnlink u where u.providerSubject = :subject")
    Optional<KakaoUnlink> lockBySubject(@Param("subject") String subject);
}
