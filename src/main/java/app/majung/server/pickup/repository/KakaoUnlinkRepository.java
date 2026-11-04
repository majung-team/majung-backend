package app.majung.server.pickup.repository;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KakaoUnlinkRepository extends JpaRepository<KakaoUnlink, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from KakaoUnlink u where u.providerSubject = :subject")
    Optional<KakaoUnlink> lockBySubject(@Param("subject") String subject);
}
