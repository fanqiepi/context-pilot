package io.github.fanqiepi.contextpilot.readingtask;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import io.github.fanqiepi.contextpilot.book.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingWork.*;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingTaskPolicy.*;

@Component
public class ReadingTaskRunner {
    private final ReadingTaskService service;
    private final ReadingAiGateway ai;
    // One downstream slot per domain. A provider ignoring interruption cannot create an unbounded thread leak.
    private final ThreadPoolExecutor taskCalls=pool("reading-ai-call"),indexCalls=pool("reading-index-call");
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"reading-watchdog");t.setDaemon(true);return t;});
    private static ThreadPoolExecutor pool(String name) {
        return new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public ReadingTaskRunner(ReadingTaskService service,ReadingAiGateway ai,ReadingTaskQueue queue) {
        this.service=service;this.ai=ai;queue.handler(this::execute);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        service.recover();
        watchdog.scheduleWithFixedDelay(()->{try{service.sweep();}catch(RuntimeException ignored){/* next bounded sweep retries metadata only */}},1,1,TimeUnit.SECONDS);
    }
    private void execute(boolean index,UUID id) {
        UUID token=null;
        try {
            var w=service.activate(index,id);token=w.executionId;
            if(index)index(w,token);else task(w,token);
        } catch(CancellationException ignored) {
            // The command that invalidated this execution already owns its durable state.
        } catch(Exception error) {
            String code=error instanceof ReadingException e?e.code():error instanceof TimeoutException?"TIMEOUT":"PROVIDER_UNAVAILABLE";
            String reason=error instanceof ReadingTaskPolicy.InvalidOutput invalid?invalid.reason():code;
            ReadingBudgetLog.providerRejected(id,code);
            service.stop(index,id,token,"SCOPE_CHANGED".equals(code)?"INVALIDATED":"FAILED",code,reason);
        }
    }
    private void task(ReadingWork initial,UUID token) throws Exception {
        UUID id=initial.id;var p=ReadingTaskService.payload(initial);
        if("CHARACTER_LOOKUP".equals(initial.type)) {
            if(p.queries==null) {
                var response=generate(initial,token,"PLAN",prompt(p,true),512,15000);
                var queries=plan(response.text());
                service.checkpoint(false,id,token,state->state.queries=queries,null);
            }
            p=ReadingTaskService.payload(service.current(false,id,token));
            while(p.searches<p.queries.size()) {
                String query=p.queries.get(p.searches);String phase="SEARCH_"+p.searches;
                UUID call=service.reserveCall(false,id,token,phase,query,0);
                final Payload frozen=p;
                var spans=bounded(false,id,token,15000,(timeout,audit)->{
                    var response=ai.embedWithUsage(List.of(query),timeout);audit.accept(response.usage());
                    var vectors=response.vectors();
                    if(vectors==null || vectors.size()!=1)throw failure("OUTPUT_INVALID");
                    return service.search(frozen,vectors.getFirst());
                },call);
                service.checkpoint(false,id,token,state->{
                    var merged=new ArrayList<>(state.evidence);
                    for(var span:spans)if(merged.stream().noneMatch(e->e.sources().contains(span)))
                        merged.add(new Evidence("E"+(merged.size()+1),List.of(span),span.text()));
                    if(merged.size()>24 || merged.stream().mapToInt(e->ReadingText.length(e.text())).sum()>24000)throw failure("COVERAGE_LIMIT");
                    state.evidence=merged;state.searches++;state.evidenceReady=state.searches==state.queries.size();
                },null);
                p=ReadingTaskService.payload(service.current(false,id,token));
            }
        }
        if(p.evidence.isEmpty()) {
            service.checkpoint(false,id,token,state->state.result=new Result(List.of(),false),"INSUFFICIENT_EVIDENCE");return;
        }
        var response=generate(initial,token,"ANSWER",prompt(p,false),"RECAP".equals(initial.type)?4096:2048,Long.MAX_VALUE);
        var result=result(response.text(),p,initial.type);
        service.checkpoint(false,id,token,state->state.result=result,result.facts().isEmpty()?"INSUFFICIENT_EVIDENCE":result.partial()?"PARTIAL":"COMPLETED");
    }
    private ReadingAiGateway.Response generate(ReadingWork w,UUID token,String phase,String prompt,int outputLimit,long timeout) throws Exception {
        UUID call=service.reserveCall(false,w.id,token,phase,ai.generationInput(prompt,outputLimit),outputLimit);
        var response=bounded(false,w.id,token,timeout,(left,audit)->{
            var result=ai.generate(prompt,outputLimit,left);audit.accept(result);return result;
        },call);
        if(response==null || response.text()==null)throw invalidOutput("GENERATION_EMPTY");
        if(!"stop".equalsIgnoreCase(response.finishReason()))throw invalidOutput(switch(safeFinishReason(response.finishReason())) {
            case "length" -> "GENERATION_LENGTH";
            case "content_filter" -> "GENERATION_CONTENT_FILTER";
            default -> "GENERATION_FINISH_REASON";
        });
        if(response.outputTokens()!=null && response.outputTokens()>outputLimit)throw invalidOutput("GENERATION_TOKEN_LIMIT");
        if(ReadingText.length(response.text())>32768 || bytes(response.text())>32768)throw invalidOutput("RESPONSE_SIZE");
        return response;
    }
    private void index(ReadingWork initial,UUID token) throws Exception {
        UUID id=initial.id;
        while(true) {
            var p=ReadingTaskService.payload(service.current(true,id,token));
            if(p.completedParagraph>=p.targetBoundary) {
                service.checkpoint(true,id,token,state->{},"READY");return;
            }
            var batch=service.indexBatch(p);if(batch.isEmpty())throw failure("INDEX_NOT_READY");
            String input=String.join("",batch.stream().map(Span::text).toList());
            limit(input,16000,64000,"INPUT_LIMIT");
            UUID call=service.reserveCall(true,id,token,"INDEX_"+p.completedParagraph,input,0);
            var vectors=bounded(true,id,token,30000,(left,audit)->{
                var response=ai.embedWithUsage(batch.stream().map(Span::text).toList(),left);
                audit.accept(response.usage());return response.vectors();
            },call);
            service.indexed(id,token,batch,vectors);
        }
    }
    @FunctionalInterface private interface Operation<T> {
        T call(Duration timeout,java.util.function.Consumer<ReadingAiGateway.Response> audit) throws Exception;
    }
    private <T> T bounded(boolean index,UUID id,UUID token,long limit,Operation<T> operation,UUID call) throws Exception {
        var w=service.current(index,id,token);
        long left=Math.min(limit,deadline(w)-ReadingTaskService.elapsed(w)),end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(left);
        Future<T> future;
        if(left<=0) { service.callFinished(call,"FAILED",null);throw new TimeoutException(); }
        try {
            future=(index?indexCalls:taskCalls).submit(()->{
                boolean[] audited={false};
                try {
                    // Audit survives cancellation and does not publish model text or mutate the task.
                    return operation.call(Duration.ofMillis(left),response->{
                        service.callFinished(call,"SUCCEEDED",response);audited[0]=true;
                    });
                } catch(Exception e) { if(!audited[0])service.callFinished(call,"UNKNOWN",null);throw e; }
            });
        } catch(RejectedExecutionException e) { service.callFinished(call,"FAILED",null);throw failure("PROVIDER_UNAVAILABLE"); }
        try {
            while(true) {
                service.current(index,id,token);
                long remaining=TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime());
                if(remaining<=0)throw new TimeoutException();
                try { return future.get(Math.min(remaining,100),TimeUnit.MILLISECONDS); }
                catch(TimeoutException e) { if(future.isDone())throw e; }
                catch(ExecutionException e) { if(e.getCause() instanceof Exception cause)throw cause;throw e; }
            }
        } finally { future.cancel(true); }
    }
    @PreDestroy public void stop() { watchdog.shutdownNow();taskCalls.shutdownNow();indexCalls.shutdownNow(); }
}
