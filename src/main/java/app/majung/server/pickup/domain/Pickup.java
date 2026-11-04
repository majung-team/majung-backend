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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** One pickup is the transaction boundary. Never serialize this persistence object. */
@Entity
public class Pickup {
    public enum Status { INVITING, WAITING_APPROVAL, ACTIVE, ARRIVED, COMPLETED, CANCELLED, EXPIRED }
    public enum Readiness { WAITING, PREPARING, READY }
    public enum Proposer { DRIVER, PASSENGER }

    @Id
    public UUID id;
    @Version
    public long version;
    public UUID vehicleId;
    public String ownerHash;
    public String inviteHash;
    public Instant inviteExpiresAt;
    public Instant expiresAt;
    public Instant createdAt;
    public Instant endedAt;
    public Instant arrivedAt;
    public Instant lastVehicleActivityAt;
    @Enumerated(EnumType.STRING)
    public Status status;
    public long placeVersion = 1;
    public UUID proposalId;
    public UUID confirmedProposalId;
    public String placeLabel;
    public double placeLatitude;
    public double placeLongitude;
    public UUID participantId;
    public String participantName;
    public String participantHash;
    @Enumerated(EnumType.STRING)
    public Readiness readiness;
    public long readinessSequence;
    public long readinessReceivedSequence;
    public long vehicleSequence;
    public Double vehicleLatitude;
    public Double vehicleLongitude;
    public Long durationSeconds;
    public Instant vehicleSourceTime;
    public Instant vehicleReceivedAt;
    // A suggested place stays beside the confirmed place until the driver acts on it.
    @Enumerated(EnumType.STRING)
    public Proposer proposalBy;
    public String proposalLabel;
    public Double proposalLatitude;
    public Double proposalLongitude;
    public Instant proposalAt;
    @Column(length = 100)
    public String quickMessage;
    public Instant quickMessageAt;

    public Pickup() { }

    public static Pickup create(String ownerHash, String inviteHash, Instant now, Instant inviteExpiresAt,
                         Instant expiresAt, PickupApi.Place place) {
        Pickup pickup = new Pickup();
        pickup.id = UUID.randomUUID();
        pickup.ownerHash = ownerHash;
        pickup.inviteHash = inviteHash;
        pickup.createdAt = now;
        pickup.inviteExpiresAt = inviteExpiresAt;
        pickup.expiresAt = expiresAt;
        pickup.status = Status.INVITING;
        pickup.placeLabel = place.label();
        pickup.placeLatitude = place.latitude();
        pickup.placeLongitude = place.longitude();
        pickup.readiness = Readiness.WAITING;
        return pickup;
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.CANCELLED || status == Status.EXPIRED;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expirationTime());
    }

    public Instant expirationTime() {
        Instant deadline = expiresAt;
        if (status == Status.ARRIVED && arrivedAt != null) {
            Instant arrivalLimit = arrivedAt.plusSeconds(1800);
            if (arrivalLimit.isBefore(deadline)) deadline = arrivalLimit;
        } else if (status == Status.ACTIVE) {
            Instant baseline = lastVehicleActivityAt != null ? lastVehicleActivityAt
                    : vehicleReceivedAt != null ? vehicleReceivedAt : createdAt;
            Instant inactivityLimit = baseline.plusSeconds(1800);
            if (inactivityLimit.isBefore(deadline)) deadline = inactivityLimit;
        }
        return deadline;
    }

    public boolean expireIfDue(Instant now) {
        if (isTerminal() || !isExpired(now)) return false;
        endedAt = expirationTime();
        status = Status.EXPIRED;
        inviteHash = null;
        clearProposal();
        clearVehicle();
        return true;
    }

    public void clearProposal() {
        proposalId = null;
        proposalBy = null;
        proposalLabel = null;
        proposalLatitude = null;
        proposalLongitude = null;
        proposalAt = null;
    }

    public void clearVehicle() {
        vehicleLatitude = null;
        vehicleLongitude = null;
        durationSeconds = null;
        vehicleSourceTime = null;
        vehicleReceivedAt = null;
    }
}
