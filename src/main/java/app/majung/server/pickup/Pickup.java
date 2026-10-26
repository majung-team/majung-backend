package app.majung.server.pickup;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** One pickup is the transaction boundary. Never serialize this persistence object. */
@Entity
class Pickup {
    enum Status { INVITING, WAITING_APPROVAL, ACTIVE, ARRIVED, COMPLETED, CANCELLED, EXPIRED }
    enum Readiness { WAITING, PREPARING, READY }
    enum Proposer { DRIVER, PASSENGER }

    @Id
    UUID id;
    @Version
    long version;
    UUID vehicleId;
    String ownerHash;
    String inviteHash;
    Instant inviteExpiresAt;
    Instant expiresAt;
    Instant createdAt;
    Instant endedAt;
    Instant arrivedAt;
    Instant lastVehicleActivityAt;
    @Enumerated(EnumType.STRING)
    Status status;
    long placeVersion = 1;
    UUID proposalId;
    UUID confirmedProposalId;
    String placeLabel;
    double placeLatitude;
    double placeLongitude;
    UUID participantId;
    String participantName;
    String participantHash;
    @Enumerated(EnumType.STRING)
    Readiness readiness;
    long readinessSequence;
    long readinessReceivedSequence;
    long vehicleSequence;
    Double vehicleLatitude;
    Double vehicleLongitude;
    Long durationSeconds;
    Instant vehicleSourceTime;
    Instant vehicleReceivedAt;
    // A suggested place stays beside the confirmed place until the driver acts on it.
    @Enumerated(EnumType.STRING)
    Proposer proposalBy;
    String proposalLabel;
    Double proposalLatitude;
    Double proposalLongitude;
    Instant proposalAt;

    protected Pickup() { }

    static Pickup create(String ownerHash, String inviteHash, Instant now, Instant inviteExpiresAt,
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

    boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.CANCELLED || status == Status.EXPIRED;
    }

    boolean isExpired(Instant now) {
        return !now.isBefore(expirationTime());
    }

    Instant expirationTime() {
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

    boolean expireIfDue(Instant now) {
        if (isTerminal() || !isExpired(now)) return false;
        endedAt = expirationTime();
        status = Status.EXPIRED;
        inviteHash = null;
        clearVehicle();
        return true;
    }

    void clearProposal() {
        proposalId = null;
        proposalBy = null;
        proposalLabel = null;
        proposalLatitude = null;
        proposalLongitude = null;
        proposalAt = null;
    }

    void clearVehicle() {
        vehicleLatitude = null;
        vehicleLongitude = null;
        durationSeconds = null;
        vehicleSourceTime = null;
        vehicleReceivedAt = null;
    }
}
