package app.majung.server.pickup;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "member_account", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"provider", "provider_subject"})})
class Member {
    @Id UUID id;
    @Version long version;
    @Column(nullable = false) String provider;
    @Column(name = "provider_subject", nullable = false) String providerSubject;
    @Column(nullable = false, unique = true) String majungId;
    @Column(nullable = false) String nickname;
    @Column(nullable = false) String normalizedNickname;
    @Column(nullable = false) boolean profileReady;
    @Column(nullable = false) Instant createdAt;
    protected Member() { }
}
