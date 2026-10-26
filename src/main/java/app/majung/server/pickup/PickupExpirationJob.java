package app.majung.server.pickup;

import java.time.Clock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Each pickup is locked separately; scanning never holds a vehicle or driver account lock. */
@Service
class PickupExpirationJob {
    private final PickupRepository pickups;
    private final Clock clock;
    private final TransactionTemplate transaction;
    PickupExpirationJob(PickupRepository pickups, Clock clock, PlatformTransactionManager manager) {
        this.pickups = pickups;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(5);
    }
    @Scheduled(fixedDelayString = "${majung.expiration.interval-ms:60000}", initialDelayString = "${majung.expiration.interval-ms:60000}")
    int expireDuePickups() {
        int expired = 0;
        java.util.UUID after = null;
        for (;;) {
            var ids = pickups.openIds(after, PageRequest.of(0, 100));
            if (ids.isEmpty()) break;
            for (var id : ids) {
                Boolean changed = transaction.execute(status -> pickups.findLockedById(id)
                        .map(pickup -> pickup.expireIfDue(clock.instant())).orElse(false));
                if (Boolean.TRUE.equals(changed)) expired++;
            }
            if (ids.size() < 100) break;
            after = ids.get(ids.size() - 1);
        }
        return expired;
    }
}
