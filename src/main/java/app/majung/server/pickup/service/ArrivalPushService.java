package app.majung.server.pickup.service;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Approaching and actual-arrival notices, each claimed once per confirmed place version. */
@Service
public class ArrivalPushService {
    private final ArrivalPushRepository repository;
    private final PushTransport transport;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final long arrivalSeconds;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "arrival-push");
        thread.setDaemon(true);
        return thread;
    });

    public ArrivalPushService(ArrivalPushRepository repository, PushTransport transport, Clock clock,
                       PlatformTransactionManager transactionManager,
                       @Value("${majung.push.arrival-seconds:180}") long arrivalSeconds) {
        this.repository = repository;
        this.transport = transport;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.arrivalSeconds = arrivalSeconds;
    }

    public boolean enabled() { return transport.enabled(); }

    public String publicKey() { return transport.publicKey(); }

    public void subscribe(UUID pickupId, String endpoint, long placeVersion) {
        if (!transport.enabled()) {
            throw new ApiException(404, "PUSH_DISABLED", "이 서버에서는 도착 알림을 사용할 수 없습니다.");
        }
        subscribe(pickupId, new PickupApi.ArrivalPushRequest(endpoint), placeVersion, "/?pickup=" + pickupId);
    }

    public void subscribe(UUID pickupId, PickupApi.ArrivalPushRequest request, long placeVersion, String targetPath) {
        if (!transport.enabled()) throw new ApiException(404,"PUSH_DISABLED","이 서버에서는 도착 알림을 사용할 수 없습니다.");
        String valid = PushEndpoint.validate(request.endpoint());
        if ((request.p256dh() == null) != (request.auth() == null)) throw new ApiException(400,"INVALID_PUSH_KEY","알림 암호화 정보를 다시 확인해주세요.");
        if (request.p256dh() != null) {
            try {
                byte[] key = java.util.Base64.getUrlDecoder().decode(request.p256dh());
                byte[] auth = java.util.Base64.getUrlDecoder().decode(request.auth());
                if (key.length != 65 || key[0] != 4 || auth.length != 16) throw new IllegalArgumentException();
                java.security.AlgorithmParameters params = java.security.AlgorithmParameters.getInstance("EC");
                params.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
                java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.ECPublicKeySpec(
                    new java.security.spec.ECPoint(new java.math.BigInteger(1, java.util.Arrays.copyOfRange(key,1,33)),
                    new java.math.BigInteger(1, java.util.Arrays.copyOfRange(key,33,65))), params.getParameterSpec(java.security.spec.ECParameterSpec.class)));
            } catch (Exception e) { throw new ApiException(400,"INVALID_PUSH_KEY","알림 암호화 정보를 다시 확인해주세요."); }
        }
        ArrivalPush existing = repository.findById(pickupId).orElse(null);
        if (existing == null) {
            ArrivalPush subscription = new ArrivalPush(pickupId, valid, clock.instant());
            subscription.placeVersion = placeVersion;
            subscription.p256dh=request.p256dh(); subscription.auth=request.auth(); subscription.targetPath=targetPath;
            repository.save(subscription);
        } else {
            // Re-subscribing (a new browser, a refreshed endpoint) never re-arms a notice that was already sent.
            existing.endpoint = valid;
            existing.p256dh=request.p256dh(); existing.auth=request.auth(); existing.targetPath=targetPath;
            if (existing.placeVersion != placeVersion) { existing.placeVersion = placeVersion; existing.notifiedAt = null; existing.arrivedNotifiedAt = null; }
        }
    }

    public void rearm(UUID pickupId, long placeVersion) {
        repository.findById(pickupId).ifPresent(subscription -> {
            subscription.placeVersion = placeVersion;
            subscription.notifiedAt = null;
            subscription.arrivedNotifiedAt = null;
        });
    }

    public void remove(UUID pickupId) { repository.deleteByPickup(pickupId); }

    public int removeCreatedBefore(java.time.Instant cutoff) { return repository.deleteCreatedBefore(cutoff); }

    /** Called inside the telemetry update; the send itself happens after that transaction commits. */
    public void vehicleUpdated(UUID pickupId, Pickup.Status status, Long durationSeconds, long placeVersion) {
        if (!transport.enabled() || status != Pickup.Status.ACTIVE || durationSeconds == null
                || durationSeconds > arrivalSeconds) {
            return;
        }
        if (repository.claim(pickupId, clock.instant(), placeVersion) != 1) return;
        String endpoint = repository.findById(pickupId).map(subscription -> subscription.endpoint).orElse(null);
        if (endpoint == null) return;
        afterCommit(() -> executor.execute(() -> deliver(pickupId, endpoint, placeVersion, false)));
    }

    public void arrived(UUID pickupId, long placeVersion) {
        if (!transport.enabled() || repository.claimArrived(pickupId, clock.instant(), placeVersion) != 1) return;
        String endpoint = repository.findById(pickupId).map(subscription -> subscription.endpoint).orElse(null);
        if (endpoint != null) afterCommit(() -> executor.execute(() -> deliver(pickupId, endpoint, placeVersion, true)));
    }

    private void deliver(UUID pickupId, String endpoint, long placeVersion, boolean arrived) {
        // Skip queued notices for a cancelled pickup, another browser or an earlier place version.
        // A push already handed to the external provider cannot be recalled.
        ArrivalPush subscription=repository.findById(pickupId).orElse(null);
        if (subscription == null || subscription.placeVersion != placeVersion || !endpoint.equals(subscription.endpoint)
                || (arrived ? subscription.arrivedNotifiedAt == null : subscription.notifiedAt == null)) return;
        // A newer actual-arrival event supersedes an about-to-arrive event still queued locally.
        if (!arrived && subscription.arrivedNotifiedAt != null) return;
        String payload="{\"kind\":\"" + (arrived ? "ARRIVED" : "APPROACHING") + "\",\"pickupId\":\"" + pickupId
                + "\",\"path\":\"" + subscription.targetPath + "\",\"sentAt\":" + clock.millis() + "}";
        if (transport.send(endpoint, subscription.p256dh, subscription.auth, payload) == PushTransport.Result.GONE) {
            transactions.executeWithoutResult(status -> repository.deleteGone(pickupId, placeVersion, endpoint));
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { action.run(); }
        });
    }

    @PreDestroy
    public void shutdown() { executor.shutdown(); }
}
