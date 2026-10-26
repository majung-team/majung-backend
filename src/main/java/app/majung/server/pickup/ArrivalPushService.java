package app.majung.server.pickup;

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

/** "About three minutes away" web push, sent at most once per pickup. */
@Service
class ArrivalPushService {
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

    ArrivalPushService(ArrivalPushRepository repository, PushTransport transport, Clock clock,
                       PlatformTransactionManager transactionManager,
                       @Value("${majung.push.arrival-seconds:180}") long arrivalSeconds) {
        this.repository = repository;
        this.transport = transport;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.arrivalSeconds = arrivalSeconds;
    }

    boolean enabled() { return transport.enabled(); }

    String publicKey() { return transport.publicKey(); }

    void subscribe(UUID pickupId, String endpoint) {
        if (!transport.enabled()) {
            throw new ApiException(404, "PUSH_DISABLED", "이 서버에서는 도착 알림을 사용할 수 없습니다.");
        }
        String valid = PushEndpoint.validate(endpoint);
        ArrivalPush existing = repository.findById(pickupId).orElse(null);
        if (existing == null) {
            repository.save(new ArrivalPush(pickupId, valid, clock.instant()));
        } else {
            // Re-subscribing (a new browser, a refreshed endpoint) never re-arms a notice that was already sent.
            existing.endpoint = valid;
        }
    }

    void remove(UUID pickupId) { repository.deleteByPickup(pickupId); }

    int removeCreatedBefore(java.time.Instant cutoff) { return repository.deleteCreatedBefore(cutoff); }

    /** Called inside the telemetry update; the send itself happens after that transaction commits. */
    void vehicleUpdated(UUID pickupId, Pickup.Status status, Long durationSeconds) {
        if (!transport.enabled() || status != Pickup.Status.ACTIVE || durationSeconds == null
                || durationSeconds > arrivalSeconds) {
            return;
        }
        if (repository.claim(pickupId, clock.instant()) != 1) return;
        String endpoint = repository.findById(pickupId).map(subscription -> subscription.endpoint).orElse(null);
        if (endpoint == null) return;
        afterCommit(() -> executor.execute(() -> deliver(pickupId, endpoint)));
    }

    private void deliver(UUID pickupId, String endpoint) {
        if (transport.send(endpoint) == PushTransport.Result.GONE) {
            transactions.executeWithoutResult(status -> repository.deleteByPickup(pickupId));
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
    void shutdown() { executor.shutdown(); }
}
