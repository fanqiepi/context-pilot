package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

@Service
@ConditionalOnProperty(prefix="contextpilot.upgrade-research",name="enabled",havingValue="true")
public class UpgradeResearchExecutor implements UpgradeRunWorker {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger(UpgradeResearchExecutor.class);
    private final UpgradeRunStateService runs;
    private final UpgradeExecutionState state;
    private final UpgradeResearchModelGateway model;
    private final RetrievalService retrieval;
    private final ReleaseNotesClient releases;
    private final String system;
    private final double minSimilarity;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),
            r->{Thread t=new Thread(r,"upgrade-bounded-io");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public UpgradeResearchExecutor(UpgradeRunStateService runs,UpgradeExecutionState state,UpgradeResearchModelGateway model,
            RetrievalService retrieval,ReleaseNotesClient releases,
            @Value("classpath:prompts/upgrade-research-v1.txt") Resource prompt,
            @Value("${contextpilot.chat.min-similarity:0.5}") double minSimilarity) throws java.io.IOException {
        this.runs=runs;this.state=state;this.model=model;this.retrieval=retrieval;this.releases=releases;
        this.system=prompt.getContentAsString(StandardCharsets.UTF_8);this.minSimilarity=minSimilarity;
    }
    @Override public void execute(UUID runId){
        if(!runs.claim(runId))return;
        try {research(runId);}
        catch(UpgradeToolException failure){runs.fail(runId,failure.code());}
        catch(Exception failure){
            Throwable cause=failure;
            for(int depth=0;depth<10 && cause.getCause()!=null && cause.getCause()!=cause;depth++)cause=cause.getCause();
            LOGGER.warn("upgrade_execution_failed runId={} exceptionType={} location={}",runId,cause.getClass().getSimpleName(),
                cause.getStackTrace().length==0?"unknown":cause.getStackTrace()[0]);
            runs.fail(runId,"UPGRADE_EXECUTION_FAILED");
        }
    }
    private void research(UUID runId) throws Exception {
        var run=state.current(runId);
        Set<UUID> documents=new LinkedHashSet<>();Map<UUID,String> profiles=new HashMap<>();
        for(var node:UpgradeJson.MAPPER.readTree(run.getDocumentSnapshots())){
            UUID id=UUID.fromString(node.path("id").asText());documents.add(id);profiles.put(id,node.path("profileId").asText());
        }
        Set<String> tags=new LinkedHashSet<>();for(var node:UpgradeJson.MAPPER.readTree(run.getReleaseTags()))tags.add(node.asText());
        Map<String,UpgradeEvidence> evidence=new LinkedHashMap<>();Map<String,ToolResult> cache=new HashMap<>();
        Set<String> gaps=new LinkedHashSet<>();
        Set<String> callIds=new HashSet<>();
        List<Message> history=new ArrayList<>(List.of(new SystemMessage(system),new UserMessage(UpgradeJson.write(Map.of(
                "question",run.getQuestion(),"selectedDocumentIds",documents.stream().map(UUID::toString).toList(),
                "repositoryKey",run.getRepositoryKey(),"releaseTags",tags,
                "coverageNotice","Only explicitly selected documents and tags are covered; intermediate releases are not automatically included.")))));
        for(int round=1;round<=UpgradeBudget.MODEL_CALLS;round++){
            requireSources(run);
            UUID modelId=state.beginModel(runId);long started=System.nanoTime();boolean toolsAllowed=round<UpgradeBudget.MODEL_CALLS;
            ChatResponse response=bounded(runId,()->model.call(history,toolsAllowed),UpgradeBudget.MODEL_SECONDS);
            var metadata=response.getMetadata();var usage=metadata==null?null:metadata.getUsage();
            if(usage instanceof org.springframework.ai.chat.metadata.EmptyUsage)usage=null;
            state.finishModel(runId,modelId,metadata==null?"deepseek-v4-flash":Objects.requireNonNullElse(metadata.getModel(),"deepseek-v4-flash"),
                    usage==null?null:usage.getPromptTokens(),usage==null?null:usage.getCompletionTokens(),usage==null?null:usage.getTotalTokens(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            var answer=response.getResult().getOutput();
            if(answer.getToolCalls().isEmpty()){
                complete(run,answer.getText(),evidence,documents,tags,gaps);return;
            }
            if(!toolsAllowed)throw new UpgradeToolException("TOOL_PROTOCOL_INVALID");
            int ordinal=state.requestTools(runId,answer.getToolCalls().size());
            List<Prepared> batch=new ArrayList<>();Set<String> unique=new HashSet<>(cache.keySet());
            int searches=0,external=0;
            for(var call:answer.getToolCalls()){
                if(call.id()==null || call.id().isBlank() || call.id().length()>200 || !callIds.add(call.id()))throw new UpgradeToolException("TOOL_PROTOCOL_INVALID");
                var args=UpgradeToolArguments.parse(call.name(),call.arguments(),documents,tags);
                if(args instanceof UpgradeToolArguments.Read read && !evidence.containsKey(read.evidenceId()))throw new UpgradeToolException("TOOL_SCOPE_VIOLATION");
                String key=UpgradeJson.hash(run.getRequestHash()+call.name()+UpgradeJson.write(args));
                if(unique.add(key)){
                    if(args instanceof UpgradeToolArguments.Search search)searches+=search.documentIds().size();
                    if(args instanceof UpgradeToolArguments.Release)external++;
                }
                batch.add(new Prepared(call.id(),call.name(),args,key));
            }
            state.preflight(runId,searches,external);history.add(answer);
            List<ToolResponseMessage.ToolResponse> responses=new ArrayList<>();
            for(var tool:batch){
                boolean cached=cache.containsKey(tool.key());
                UUID toolId=state.beginTool(runId,ordinal++,modelId,tool.callId(),tool.name(),tool.key(),summary(tool.arguments()),cached);
                ToolResult result=cache.get(tool.key());
                if(result==null){result=read(run,tool.arguments(),evidence,profiles);cache.put(tool.key(),result);}
                if(result.error()!=null)gaps.add(result.error());
                state.finishTool(runId,toolId,result.error());
                responses.add(new ToolResponseMessage.ToolResponse(tool.callId(),tool.name(),UpgradeJson.write(result.data())));
            }
            history.add(ToolResponseMessage.builder().responses(responses).build());
        }
        throw new UpgradeToolException("MODEL_BUDGET_EXCEEDED");
    }

    private ToolResult read(UpgradeRunEntity run,UpgradeToolArguments args,Map<String,UpgradeEvidence> evidence,Map<UUID,String> profiles){
        if(args instanceof UpgradeToolArguments.Read read)return new ToolResult(evidence.get(read.evidenceId()).toolData(),null);
        if(args instanceof UpgradeToolArguments.Release release){
            state.beginRead(run.getId(),true);
            try {
                var result=releases.read(release.tag(),run.getDeadline().toInstant());
                var item=new UpgradeEvidence(UUID.randomUUID(),run.getId(),"R"+(evidence.values().stream().filter(e->e.sourceType().equals("RELEASE")).count()+1),
                        "RELEASE",null,null,null,result.contentHash(),result.releaseId(),result.tag(),result.url(),
                        OffsetDateTime.parse(result.publishedAt()),OffsetDateTime.parse(result.fetchedAt()),result.body(),result.truncated(),result.prerelease());
                state.addEvidence(item);evidence.put(item.sourceId(),item);
                return new ToolResult(item.toolData(),result.truncated()?"RELEASE_TRUNCATED:"+release.tag():null);
            }catch(UpgradeToolException error){
                if(!error.code().startsWith("RELEASE_"))throw error;
                return failure(error.code()+":"+release.tag());
            }
        }
        var search=(UpgradeToolArguments.Search)args;List<Map<String,Object>> results=new ArrayList<>();String missing=null;
        for(UUID document:search.documentIds()){
            requireSources(run);state.beginRead(run.getId(),false);
            List<io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse> hits;
            try {hits=bounded(run.getId(),()->retrieval.searchDocument(run.getKnowledgeBaseId(),document,search.query(),3,run.getTraceId()),60);}
            catch(UpgradeToolException error){throw error;}
            catch(Exception error){missing="LOCAL_RETRIEVAL_FAILED:"+document;continue;}
            requireSources(run);boolean found=false;
            for(var hit:hits){
                if(!document.equals(hit.documentId()))throw new UpgradeToolException("TOOL_SCOPE_VIOLATION");
                if(hit.content()==null || hit.content().isBlank() || hit.score()==null || hit.score()<minSimilarity)continue;
                found=true;
                var existing=evidence.values().stream().filter(e->document.equals(e.documentId()) && hit.chunkId().equals(e.chunkId())).findFirst();
                if(existing.isPresent()){results.add(existing.get().toolData());continue;}
                String text=hit.content();var item=new UpgradeEvidence(UUID.randomUUID(),run.getId(),
                        "L"+(evidence.values().stream().filter(e->e.sourceType().equals("LOCAL")).count()+1),"LOCAL",document,
                        hit.chunkId(),profiles.get(document),UpgradeJson.hash(text),null,null,null,null,UpgradeRunStateService.now(),
                        text.substring(0,Math.min(text.length(),1200)),text.length()>1200,false);
                state.addEvidence(item);evidence.put(item.sourceId(),item);results.add(item.toolData());
                if(item.truncated())missing="LOCAL_EVIDENCE_TRUNCATED:"+document;
            }
            if(!found)missing="LOCAL_EVIDENCE_MISSING:"+document;
        }
        return new ToolResult(Map.of("untrusted",true,"evidence",results,"missing",Objects.requireNonNullElse(missing,"")),missing);
    }
    private void complete(UpgradeRunEntity run,String text,Map<String,UpgradeEvidence> evidence,Set<UUID> documents,Set<String> tags,Set<String> gaps) throws Exception {
        requireSources(run);
        // A later scoped search may recover an earlier empty/failed query. Keep the audit,
        // but base final coverage gaps on the completed ledger rather than each query.
        for(UUID doc:documents)if(evidence.values().stream().anyMatch(e->doc.equals(e.documentId()))){
            gaps.remove("LOCAL_EVIDENCE_MISSING:"+doc);gaps.remove("LOCAL_RETRIEVAL_FAILED:"+doc);
        }
        for(UUID doc:documents)if(evidence.values().stream().noneMatch(e->doc.equals(e.documentId())))gaps.add("LOCAL_EVIDENCE_MISSING:"+doc);
        for(String tag:tags)if(evidence.values().stream().noneMatch(e->tag.equals(e.releaseTag())))gaps.add("RELEASE_MISSING:"+tag);
        for(var source:evidence.values())if(source.truncated())gaps.add("SOURCE_TRUNCATED:"+source.sourceId());
        if(evidence.values().stream().noneMatch(e->e.sourceType().equals("LOCAL")) || evidence.values().stream().noneMatch(e->e.sourceType().equals("RELEASE"))){
            state.finish(run.getId(),UpgradeRunStatus.REFUSED,null,new ArrayList<>(gaps));return;
        }
        if(text==null || text.length()>10000)throw new UpgradeToolException("FINAL_RESPONSE_INVALID");
        com.fasterxml.jackson.databind.JsonNode json;
        try{json=UpgradeJson.MAPPER.readTree(text);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new UpgradeToolException("FINAL_RESPONSE_INVALID");}
        if(json==null || !json.isObject() || json.size()!=2 || !json.path("answer").isTextual() || !json.path("missingEvidence").isArray()
                || json.path("missingEvidence").size()>10)throw new UpgradeToolException("FINAL_RESPONSE_INVALID");
        String draft=json.path("answer").asText().strip();
        if(draft.isBlank() || draft.length()>UpgradeBudget.ANSWER_CHARACTERS)throw new UpgradeToolException("ANSWER_BUDGET_EXCEEDED");
        for(var missing:json.path("missingEvidence")){
            if(!missing.isTextual() || missing.asText().isBlank() || missing.asText().length()>200)throw new UpgradeToolException("FINAL_RESPONSE_INVALID");
            gaps.add(missing.asText());
        }
        if(Pattern.compile("https?://",Pattern.CASE_INSENSITIVE).matcher(draft).find())throw new UpgradeToolException("CITATION_INVALID");
        var matcher=Pattern.compile("\\[((?:[A-Za-z]+)?[0-9]+)]").matcher(draft);Set<String> cited=new HashSet<>();
        while(matcher.find()){if(!evidence.containsKey(matcher.group(1)))throw new UpgradeToolException("CITATION_INVALID");cited.add(matcher.group(1));}
        if(cited.size()>20 || cited.stream().noneMatch(id->id.startsWith("L")) || cited.stream().noneMatch(id->id.startsWith("R")))
            throw new UpgradeToolException("CITATION_INVALID");
        state.finish(run.getId(),gaps.isEmpty()?UpgradeRunStatus.SUCCEEDED:UpgradeRunStatus.PARTIAL,draft,new ArrayList<>(gaps));
    }
    private void requireSources(UpgradeRunEntity run){if(!runs.sourcesUnchanged(run))throw new UpgradeToolException("SOURCE_CHANGED");}
    private <T>T bounded(UUID runId,Callable<T> action,int seconds)throws Exception{
        var run=state.current(runId);long remaining=Math.min(TimeUnit.SECONDS.toMillis(seconds),Duration.between(Instant.now(),run.getDeadline().toInstant()).toMillis());
        if(remaining<=0)throw new UpgradeToolException("DEADLINE_EXCEEDED");
        Future<T> future;
        try{future=io.submit(action);}catch(RejectedExecutionException error){throw new UpgradeToolException("IO_CAPACITY_EXHAUSTED");}
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(remaining);
        try{
            while(true){
                long left=deadline-System.nanoTime();if(left<=0)throw new UpgradeToolException("CALL_TIMEOUT");
                try{return future.get(Math.min(left,TimeUnit.MILLISECONDS.toNanos(250)),TimeUnit.NANOSECONDS);}
                catch(TimeoutException ignored){state.current(runId);}
                catch(ExecutionException error){if(error.getCause() instanceof UpgradeToolException safe)throw safe;throw error;}
            }
        }finally{future.cancel(true);}
    }
    private static String summary(UpgradeToolArguments arguments){
        if(arguments instanceof UpgradeToolArguments.Search search)return "selectedDocuments="+search.documentIds().size()+", queryCharacters="+search.query().length();
        if(arguments instanceof UpgradeToolArguments.Read read)return "evidenceId="+read.evidenceId();
        return "tag="+((UpgradeToolArguments.Release)arguments).tag();
    }
    private static ToolResult failure(String error){return new ToolResult(Map.of("untrusted",true,"errorCode",error),error);}
    private record ToolResult(Map<String,Object> data,String error){}
    private record Prepared(String callId,String name,UpgradeToolArguments arguments,String key){}
    @PreDestroy public void shutdown(){io.shutdownNow();}
}
