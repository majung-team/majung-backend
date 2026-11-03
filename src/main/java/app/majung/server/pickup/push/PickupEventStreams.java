package app.majung.server.pickup;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded snapshot streams. Rechecks authorization on every read; stores no replay history. */
@Service
class PickupEventStreams {
    record State(PickupApi.Snapshot pickup, PickupApi.VehicleState vehicle) {
        Object revision() {
            return List.of(pickup, vehicle.freshness(), vehicle.sequence(), vehicle.placeVersion(), vehicle.latitude() != null);
        }
    }
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "pickup-sse");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<UUID, Integer> counts = new ConcurrentHashMap<>();
    private int total;
    private volatile boolean stopping;
    private final java.util.Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    SseEmitter open(UUID id, Supplier<State> read) {
        State initial = read.get(); // Reject invalid credentials before starting an HTTP stream.
        synchronized (this) {
            if (stopping) throw new ApiException(503, "STREAM_PAUSED", "서버 연결을 다시 준비하고 있어요.");
            if (total >= 64 || counts.getOrDefault(id, 0) >= 4)
                throw new ApiException(429, "STREAM_LIMIT", "열린 픽업 화면이 많아요. 다른 탭을 닫고 다시 확인해주세요.");
            total++;
            counts.merge(id, 1, Integer::sum);
        }
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(25));
        emitters.add(emitter);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            emitters.remove(emitter);
            ScheduledFuture<?> task = scheduled.get();
            if (task != null) task.cancel(false);
            synchronized (this) {
                total--;
                counts.computeIfPresent(id, (key, count) -> count == 1 ? null : count - 1);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> { cleanup.run(); emitter.complete(); });
        emitter.onError(error -> cleanup.run());
        if (stopping) { cleanup.run(); emitter.complete(); return emitter; }
        AtomicReference<Object> previous = new AtomicReference<>();
        AtomicReference<State> first = new AtomicReference<>(initial);
        long[] lastSent = {0};
        ScheduledFuture<?> task = executor.scheduleWithFixedDelay(() -> {
            if (closed.get()) return;
            if (stopping) { cleanup.run(); emitter.complete(); return; }
            try {
                State state = first.getAndSet(null);
                if (state == null) state = read.get();
                Object revision = state.revision();
                long now = System.nanoTime();
                if (!revision.equals(previous.get())) {
                    emitter.send(SseEmitter.event().name("changed").data("refresh"));
                    previous.set(revision);
                    lastSent[0] = now;
                } else if (now - lastSent[0] >= TimeUnit.SECONDS.toNanos(15)) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    lastSent[0] = now;
                }
                if (List.of("COMPLETED", "CANCELLED", "EXPIRED").contains(state.pickup().status())) { cleanup.run(); emitter.complete(); }
            } catch (ApiException denied) {
                try { emitter.send(SseEmitter.event().name("access-ended").data("refresh")); }
                catch (IOException | IllegalStateException ignored) { }
                cleanup.run();
                emitter.complete();
            } catch (Exception failed) {
                cleanup.run();
                emitter.complete();
            }
        }, 0, 1, TimeUnit.SECONDS);
        scheduled.set(task);
        if (closed.get()) task.cancel(false);
        return emitter;
    }

    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    void closing() {
        stopping = true;
        emitters.forEach(SseEmitter::complete);
    }
    @PreDestroy void close() { closing(); executor.shutdownNow(); }
}
