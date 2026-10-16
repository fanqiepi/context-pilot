package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UpgradeExecutionState {
    private final UpgradeRunMapper runs;
    private final UpgradeExecutionMapper calls;
    private final UpgradeRunStateService runState;
    public UpgradeExecutionState(UpgradeRunMapper runs,UpgradeExecutionMapper calls,UpgradeRunStateService runState){this.runs=runs;this.calls=calls;this.runState=runState;}
    private UpgradeRunEntity active(UUID id){
        var run=runs.lockInternal(id);
        if(run==null || run.getStatus()!=UpgradeRunStatus.RUNNING) throw new UpgradeToolException("RUN_NOT_ACTIVE");
        if(!run.getDeadline().isAfter(UpgradeRunStateService.now()))throw new UpgradeToolException("DEADLINE_EXCEEDED");
        return run;
    }
    @Transactional public UpgradeRunEntity current(UUID id){return active(id);}
    @Transactional public UUID beginModel(UUID id){
        var run=active(id);if(run.getModelCalls()>=UpgradeBudget.MODEL_CALLS)throw new UpgradeToolException("MODEL_BUDGET_EXCEEDED");
        var callId=UUID.randomUUID();var now=UpgradeRunStateService.now();
        calls.count(id,1,0,0,0,now);calls.beginModel(callId,run,run.getModelCalls()+1,now);return callId;
    }
    @Transactional public void finishModel(UUID run,UUID call,String model,Integer input,Integer output,Integer total,long latency){
        active(run);calls.finishModel(call,model,input,output,total,latency,UpgradeRunStateService.now());
    }
    @Transactional public int requestTools(UUID id,int size){
        var run=active(id);
        if(size<1 || size>UpgradeBudget.TOOL_CALLS_PER_ROUND || run.getToolRequests()+size>UpgradeBudget.TOOL_REQUESTS)
            throw new UpgradeToolException("TOOL_BUDGET_EXCEEDED");
        calls.count(id,0,size,0,0,UpgradeRunStateService.now());return run.getToolRequests()+1;
    }
    @Transactional public void preflight(UUID id,int retrievals,int http){
        var run=active(id);
        if(retrievals<0 || http<0 || run.getRetrievalCalls()+retrievals>UpgradeBudget.RETRIEVAL_CALLS
                || run.getHttpCalls()+http>UpgradeBudget.HTTP_CALLS)throw new UpgradeToolException("READ_BUDGET_EXCEEDED");
    }
    @Transactional public UUID beginTool(UUID runId,int ordinal,UUID model,String callId,String name,String hash,String summary,boolean cache){
        active(runId);var id=UUID.randomUUID();calls.beginTool(id,runId,ordinal,model,callId,name,hash,summary,cache,UpgradeRunStateService.now());return id;
    }
    @Transactional public void beginRead(UUID runId,boolean http){
        preflight(runId,http?0:1,http?1:0);
        calls.count(runId,0,0,http?0:1,http?1:0,UpgradeRunStateService.now());
    }
    @Transactional public void finishTool(UUID runId,UUID toolId,String error){
        active(runId);calls.finishTool(toolId,error==null?"SUCCEEDED":"FAILED",error,UpgradeRunStateService.now());
        calls.count(runId,0,0,0,0,UpgradeRunStateService.now());
    }
    @Transactional public void addEvidence(UpgradeEvidence evidence){
        active(evidence.runId());var prior=calls.sources(evidence.runId());
        long local=prior.stream().filter(e->e.sourceType().equals("LOCAL")).count();
        long remote=prior.size()-local;
        int characters=prior.stream().mapToInt(e->e.excerpt().length()).sum();
        if(characters+evidence.excerpt().length()>UpgradeBudget.EVIDENCE_CHARACTERS
                || (evidence.sourceType().equals("LOCAL")?local>=UpgradeBudget.LOCAL_EVIDENCE:remote>=UpgradeBudget.RELEASE_EVIDENCE))
            throw new UpgradeToolException("EVIDENCE_BUDGET_EXCEEDED");
        calls.source(evidence);
    }
    @Transactional public void finish(UUID id,UpgradeRunStatus status,String draft,List<String> gaps){
        var run=active(id);
        try {
            for(var snapshot:UpgradeJson.MAPPER.readTree(run.getDocumentSnapshots()))
                if(runs.lockDocument(UUID.fromString(snapshot.path("id").asText()))==null)throw new UpgradeToolException("SOURCE_CHANGED");
        }catch(UpgradeToolException error){throw error;}
        catch(Exception error){throw new UpgradeToolException("SOURCE_CHANGED");}
        if(!runState.sourcesUnchanged(run))throw new UpgradeToolException("SOURCE_CHANGED");
        if(status!=UpgradeRunStatus.SUCCEEDED && status!=UpgradeRunStatus.PARTIAL && status!=UpgradeRunStatus.REFUSED)
            throw new IllegalArgumentException("Invalid completion");
        if(status==UpgradeRunStatus.REFUSED)draft=null;
        calls.finish(id,status,draft,draft==null?null:UpgradeJson.hash(draft),UpgradeJson.write(gaps),UpgradeRunStateService.now());
    }
}
