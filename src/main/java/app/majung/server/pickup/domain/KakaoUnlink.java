package app.majung.server.pickup.domain;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Durable work item: acknowledge only after storing the provider's unlink notification. */
@Entity
@Table(name = "kakao_unlink")
public class KakaoUnlink {
    @Id @Column(name = "provider_subject", length = 30) public String providerSubject;
    @Column(nullable = false) public Instant receivedAt;
    public KakaoUnlink() { }
}
