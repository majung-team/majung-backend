package app.majung.server.pickup.domain;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "member_account", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"provider", "provider_subject"})})
public class Member {
    @Id public UUID id;
    @Version public long version;
    @Column(nullable = false) public String provider;
    @Column(name = "provider_subject", nullable = false) public String providerSubject;
    @Column(nullable = false, unique = true) public String majungId;
    @Column(nullable = false) public String nickname;
    @Column(nullable = false) public String normalizedNickname;
    @Column(nullable = false) public boolean profileReady;
    @Column(nullable = false) public Instant createdAt;
    public Member() { }
}
