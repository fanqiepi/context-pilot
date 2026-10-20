package io.github.fanqiepi.contextpilot.readingtask;

import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.github.fanqiepi.contextpilot.book.ReadingException;

/** Events contain freshly authorized snapshots, never raw tokens or historical result bodies. */
@Service
public class ReadingTaskEvents {
    private final ReadingTaskService tasks;
    private final ConcurrentMap<SseEmitter,Subscription> clients=new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"reading-task-events");t.setDaemon(true);return t;});
    private static class Subscription {
        final UUID id;long revision=-1;int ticks;final long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30);
        Subscription(UUID id){this.id=id;}
    }
    public ReadingTaskEvents(ReadingTaskService tasks) { this.tasks=tasks;timer.scheduleWithFixedDelay(this::poll,1,1,TimeUnit.SECONDS); }
    public synchronized SseEmitter subscribe(UUID id) {
        tasks.get(false,id);
        if(clients.size()>=16)throw new ReadingException(429,"STREAM_LIMIT","任务通知连接过多");
        var e=new SseEmitter(TimeUnit.MINUTES.toMillis(30));clients.put(e,new Subscription(id));
        e.onCompletion(()->clients.remove(e));e.onTimeout(()->close(e));e.onError(x->clients.remove(e));return e;
    }
    private void poll() {
        clients.forEach((e,s)->{
            try {
                if(System.nanoTime()>s.deadline){close(e);return;}
                tasks.send(s.id,value->{
                    try {
                        var view=(Map<?,?>)value;long revision=((Number)view.get("revision")).longValue();
                        boolean done=ReadingWork.TERMINAL.contains(view.get("status")) || "HIDDEN_BY_SCOPE".equals(view.get("visibility"));
                        if(s.revision!=revision || done) {
                            String event=s.revision<0?"snapshot":"progress";
                            e.send(SseEmitter.event().name(event).id(s.id+"/"+revision).data(value));
                            if(view.get("validatedResult")!=null)e.send(SseEmitter.event().name("result").id(s.id+"/"+revision).data(value));
                            s.revision=revision;
                            if(done){e.send(SseEmitter.event().name("done").id(s.id+"/"+revision).data(Map.of("id",s.id)));close(e);}
                        } else if(++s.ticks%15==0)e.send(SseEmitter.event().comment("keepalive"));
                    } catch(java.io.IOException error) { close(e); }
                });
            } catch(RuntimeException error) { close(e); }
        });
    }
    private void close(SseEmitter e) { clients.remove(e);e.complete(); }
    @PreDestroy public void stop() { timer.shutdownNow();clients.keySet().forEach(this::close); }
}
