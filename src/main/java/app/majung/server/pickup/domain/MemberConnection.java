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
@Table(name = "member_connection", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"first_member", "second_member"})})
public class MemberConnection {
    @Id public UUID id;
    @Version public long version;
    @Column(nullable = false) public UUID firstMember;
    @Column(nullable = false) public UUID secondMember;
    @Column(nullable = false) public UUID requester;
    @Column(nullable = false) public UUID recipient;
    @Column(nullable = false) public String status;
    @Column(nullable = false) public Instant createdAt;
    @Column(nullable = false) public Instant updatedAt;
    public MemberConnection() { }
}
