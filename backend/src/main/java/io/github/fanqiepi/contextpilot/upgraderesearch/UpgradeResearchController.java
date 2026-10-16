package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.*;
import java.util.concurrent.*;
import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.researchreport.*;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/upgrade-research")
public class UpgradeResearchController {
    private final ObjectProvider<UpgradeResearchStartService> start;
    private final UpgradeRunStateService state;
    private final UpgradeQueryService query;
    private final ResearchReportProposalService proposals;
    private final ThreadPoolExecutor streams=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),
            r->{Thread t=new Thread(r,"upgrade-sse");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public UpgradeResearchController(ObjectProvider<UpgradeResearchStartService> start,UpgradeRunStateService state,UpgradeQueryService query,ResearchReportProposalService proposals){
        this.start=start;this.state=state;this.query=query;this.proposals=proposals;
    }
    @GetMapping("/repositories") public Object repositories(){return Map.of("enabled",start.getIfAvailable()!=null,"repositories",List.of(
            Map.of("key","spring-ai","name","Spring AI","url","https://github.com/spring-projects/spring-ai")));}
    @PostMapping("/runs") public ResponseEntity<UpgradeRunEntity> create(@Valid @RequestBody UpgradeStartRequest request){
        var service=start.getIfAvailable();if(service==null)throw new ConflictException("UPGRADE_RESEARCH_DISABLED","Upgrade research is disabled");
        var result=service.start(request,UUID.randomUUID().toString());return ResponseEntity.status(result.replay()?200:202).body(result.run());
    }
    @GetMapping("/runs") public List<UpgradeRunEntity> list(@RequestParam UUID knowledgeBaseId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return query.list(knowledgeBaseId,page,size);}
    @GetMapping("/runs/{id}") public UpgradeQueryService.View get(@PathVariable UUID id){return query.get(id);}
    @PostMapping("/runs/{id}/cancel") public UpgradeRunEntity cancel(@PathVariable UUID id){return state.cancel(id);}
    @DeleteMapping("/runs/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id){query.delete(id);return ResponseEntity.noContent().build();}
    @PostMapping("/runs/{id}/save-proposals") public ActionRequestResponse propose(@PathVariable UUID id,@Valid @RequestBody ReportSaveRequest request){return proposals.propose(id,request);}
    @GetMapping(value="/runs/{id}/events",produces="text/event-stream") public SseEmitter events(@PathVariable UUID id){
        var initial=query.get(id);var emitter=new SseEmitter(190000L);var connected=new java.util.concurrent.atomic.AtomicBoolean(true);
        emitter.onCompletion(()->connected.set(false));emitter.onTimeout(()->connected.set(false));emitter.onError(error->connected.set(false));
        try{streams.execute(()->{
            long revision=-1;
            try{
                while(connected.get()){
                    var view=query.get(id);var run=view.run();
                    if(run.getRevision()!=revision){
                        emitter.send(SseEmitter.event().name(revision<0?"snapshot":"progress").id(Long.toString(run.getRevision())).data(view));
                        revision=run.getRevision();
                    }
                    if(run.getStatus().terminal()){
                        if(run.getDraft()!=null)emitter.send(SseEmitter.event().name("result").id(Long.toString(revision)).data(view));
                        emitter.send(SseEmitter.event().name("done").data(Map.of("runId",id,"traceId",run.getTraceId(),"revision",revision,"status",run.getStatus())));
                        emitter.complete();return;
                    }
                    Thread.sleep(500);
                }
            }catch(InterruptedException error){Thread.currentThread().interrupt();emitter.complete();}
            catch(Exception error){try{emitter.send(SseEmitter.event().name("error").data(Map.of("code","UPGRADE_STREAM_UNAVAILABLE","runId",id,"traceId",initial.run().getTraceId(),"revision",Math.max(initial.run().getRevision(),revision))));}catch(Exception ignored){}emitter.complete();}
        });}catch(RejectedExecutionException error){throw new UpgradeQueueFullException();}
        return emitter;
    }
    @PreDestroy public void shutdown(){streams.shutdownNow();}
}
