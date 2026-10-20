package io.github.fanqiepi.contextpilot.readingtask;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import io.github.fanqiepi.contextpilot.book.ReadingException;

@Component
public class ReadingTaskQueue {
    private final Semaphore tasks=new Semaphore(3),indices=new Semaphore(2);
    private final ExecutorService taskPool=pool("reading-task"),indexPool=pool("reading-index");
    private volatile BiConsumer<Boolean,UUID> handler;
    private static ExecutorService pool(String name) {
        return Executors.newSingleThreadExecutor(r->{var t=new Thread(r,name);t.setDaemon(true);return t;});
    }
    public void handler(BiConsumer<Boolean,UUID> handler) { this.handler=handler; }
    public void reserve(boolean index) {
        if(!(index?indices:tasks).tryAcquire())throw new ReadingException(429,"QUEUE_FULL","阅读任务队列已满，请稍后主动重试");
    }
    public void release(boolean index) { (index?indices:tasks).release(); }
    public void submit(boolean index,UUID id) {
        try { (index?indexPool:taskPool).execute(()->{try{handler.accept(index,id);}finally{release(index);}}); }
        catch(RejectedExecutionException e) { release(index);throw e; }
    }
    @PreDestroy public void stop() { taskPool.shutdownNow();indexPool.shutdownNow(); }
}
