package io.github.fanqiepi.contextpilot.readingstate;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import io.github.fanqiepi.contextpilot.book.ReadingException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Only current, committed metadata is sent. No historical body is stored or replayed. */
@Service
public class ReadingScopeEvents {
    public record StateEvent(UUID versionId,int readBoundary,long scopeRevision,long visibilityEpoch,OffsetDateTime dataAsOf) {
        static StateEvent of(ReadingScopeService.Snapshot scope) {
            return new StateEvent(scope.versionId(),scope.boundaryOrdinal(),scope.scopeRevision(),scope.visibilityEpoch(),scope.dataAsOf());
        }
    }
    private static final int MAX_CONNECTIONS=16;
    private final ReadingScopeService scopes;
    private final ConcurrentMap<SseEmitter,Subscription> subscriptions=new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(
            runnable -> { var thread=new Thread(runnable,"reading-scope-events");thread.setDaemon(true);return thread; });
    private static final class Subscription {
        final UUID versionId,ownerId;
        final long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30);
        long revision=-1;
        int ticks;
        Subscription(ReadingScopeService.Snapshot initial) { versionId=initial.versionId();ownerId=initial.ownerId(); }
    }
    public ReadingScopeEvents(ReadingScopeService scopes) {
        this.scopes=scopes;
        scheduler.scheduleWithFixedDelay(this::poll,1,1,TimeUnit.SECONDS);
    }
    public synchronized SseEmitter subscribe(UUID versionId) {
        var initial=scopes.capture(versionId);
        if(subscriptions.size()>=MAX_CONNECTIONS) throw new ReadingException(429,"STREAM_LIMIT","范围通知连接过多，请关闭多余页面");
        var emitter=new SseEmitter(TimeUnit.MINUTES.toMillis(30)+5_000L);
        var subscription=new Subscription(initial);
        emitter.onCompletion(()->subscriptions.remove(emitter));
        emitter.onTimeout(()->close(emitter));
        emitter.onError(error->subscriptions.remove(emitter));
        // The first read happens in poll(), after registration, so no pre-registration snapshot can win a race.
        subscriptions.put(emitter,subscription);
        return emitter;
    }
    private void poll() {
        subscriptions.forEach((emitter,subscription)-> {
            try {
                if(System.nanoTime()>=subscription.deadline) { close(emitter);return; }
                var current=scopes.capture(subscription.versionId);
                if(!current.ownerId().equals(subscription.ownerId)) { close(emitter);return; }
                if(subscription.revision!=current.scopeRevision()) {
                    emitter.send(SseEmitter.event().name(subscription.revision<0?"snapshot":"scope_changed")
                            .id(current.versionId()+"/"+current.scopeRevision()).data(StateEvent.of(current)));
                    subscription.revision=current.scopeRevision();
                } else if(++subscription.ticks%15==0) emitter.send(SseEmitter.event().comment("keepalive"));
            } catch(IOException | RuntimeException error) { close(emitter); }
        });
    }
    private void close(SseEmitter emitter) { subscriptions.remove(emitter);emitter.complete(); }
    @PreDestroy public void stop() { scheduler.shutdownNow();subscriptions.keySet().forEach(this::close); }
}
