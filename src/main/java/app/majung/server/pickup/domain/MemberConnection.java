package app.majung.server.pickup;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "member_connection", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"first_member", "second_member"})})
class MemberConnection {
    @Id UUID id;
    @Version long version;
    @Column(nullable = false) UUID firstMember;
    @Column(nullable = false) UUID secondMember;
    @Column(nullable = false) UUID requester;
    @Column(nullable = false) UUID recipient;
    @Column(nullable = false) String status;
    @Column(nullable = false) Instant createdAt;
    @Column(nullable = false) Instant updatedAt;
    protected MemberConnection() { }
}
