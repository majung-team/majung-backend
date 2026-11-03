package app.majung.server.pickup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable work item: acknowledge only after storing the provider's unlink notification. */
@Entity
@Table(name = "kakao_unlink")
class KakaoUnlink {
    @Id @Column(name = "provider_subject", length = 30) String providerSubject;
    @Column(nullable = false) Instant receivedAt;
    protected KakaoUnlink() { }
}
