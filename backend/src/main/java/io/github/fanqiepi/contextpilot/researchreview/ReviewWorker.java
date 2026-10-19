package io.github.fanqiepi.contextpilot.researchreview;

import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Component
@ConditionalOnProperty(prefix="contextpilot.task-workspace",name="enabled",havingValue="true")
public class ReviewWorker {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ReviewWorker.class);
    private final ReviewService service;
    private final ReviewModelGateway model;
    private final ThreadPoolExecutor workers=pool(1,2,"research-review");
    private final ThreadPoolExecutor calls=pool(1,1,"research-review-call");
    private final Set<UUID> scheduled=ConcurrentHashMap.newKeySet();
    public ReviewWorker(ReviewService service,ReviewModelGateway model) {this.service=service;this.model=model;}
    @EventListener(ApplicationReadyEvent.class)
    public void recover() {service.recover();}
    public void submit(UUID id) {
        var row=service.internal(id);
        if(row==null || row.getStatus()!=ReviewData.Status.QUEUED) return;
        UUID token=row.getExecutionId();if(!scheduled.add(token)) return;
        try {workers.execute(()->{try {execute(id,token);} finally {scheduled.remove(token);}});}
        catch(RejectedExecutionException e) {scheduled.remove(token);service.fail(id,token,"REVIEW_QUEUE_FULL",null);}
    }
    private void execute(UUID id,UUID token) {
        Future<ReviewModelGateway.Result> future=null;
        io.github.fanqiepi.contextpilot.taskworkspace.TaskState.Call usage=null;
        try {
            var row=service.begin(id,token);if(row==null) return;
            var snapshot=ReviewService.snapshot(row);ReviewValidator.snapshot(snapshot);
            var deterministic=ReviewValidator.citationIssues(snapshot);
            if(deterministic!=null) {service.complete(id,token,deterministic,null);return;}
            future=calls.submit(()->{
                // Check at actual invocation time: a queued call may already have been cancelled.
                if(!service.modelStarted(id,token)) throw new CancellationException();
                return model.call(snapshot);
            });
            while(service.alive(id,token)) {
                try {
                    var response=future.get(250,TimeUnit.MILLISECONDS);usage=response.call();
                    var result=ReviewValidator.result(response.text(),snapshot);
                    service.complete(id,token,result,usage);return;
                } catch(TimeoutException ignored) { /* Poll the persisted token and hard deadline. */ }
            }
        } catch(CancellationException ignored) {
            // User decisions and terminal states always win over late provider results.
        } catch(Exception e) {
            Throwable root=e;while(root.getCause()!=null && root.getCause()!=root) root=root.getCause();
            LOG.warn("research.review.failed reviewId={} executionId={} errorType={} location={}",id,token,
                    root.getClass().getSimpleName(),root.getStackTrace().length==0?"unknown":root.getStackTrace()[0]);
            String code=root instanceof IllegalArgumentException?"REVIEW_RESPONSE_INVALID":
                    root instanceof RejectedExecutionException?"REVIEW_CALL_CAPACITY":"REVIEW_EXECUTION_FAILED";
            if(root instanceof IllegalArgumentException && "REVIEW_INPUT_BUDGET".equals(root.getMessage())) code="REVIEW_INPUT_BUDGET";
            service.fail(id,token,code,usage);
        } finally {
            if(future!=null) {future.cancel(true);calls.purge();}
        }
    }
    private static ThreadPoolExecutor pool(int count,int queue,String name) {
        return new ThreadPoolExecutor(count,count,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(queue),r->{
            var thread=new Thread(r,name);thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    @PreDestroy public void close() {workers.shutdownNow();calls.shutdownNow();}
}
