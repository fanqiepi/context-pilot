package io.github.fanqiepi.contextpilot.readingtask;

import java.time.*;
import java.util.*;
import java.util.function.*;
import io.github.fanqiepi.contextpilot.book.*;
import io.github.fanqiepi.contextpilot.readingstate.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingWork.*;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingTaskPolicy.*;

@Service
public class ReadingTaskService {
    public record Create(UUID clientRequestId,UUID versionId,Long expectedScopeRevision,String type,Integer chapterCount,String question,String mode) {}
    public record IndexCreate(UUID clientRequestId,Long expectedScopeRevision,String estimateHash) {}
    public record Command(UUID clientRequestId,Long expectedRevision,String command) {}
    public record Registered(UUID id,boolean created) {}
    public record Estimate(int fromBoundary,int targetBoundary,int requestedBoundary,int segments,int characters,int batches,
                           String profile,String estimateHash,boolean liveAllowed,String estimatedCost,String reason) {}
    private final ReadingTaskMapper mapper;
    private final ReadingScopeService scopes;
    private final ReadingStateService states;
    private final BookMapper books;
    private final ReadingAiGateway ai;
    private final ReadingTaskQueue queue;
    private final ReadingSpendService spend;
    public ReadingTaskService(ReadingTaskMapper mapper,ReadingScopeService scopes,ReadingStateService states,BookMapper books,ReadingAiGateway ai,ReadingTaskQueue queue,ReadingSpendService spend) {
        this.mapper=mapper;this.scopes=scopes;this.states=states;this.books=books;this.ai=ai;this.queue=queue;
        this.spend=spend;
    }
    static Payload payload(ReadingWork w) { return ReadingText.read(w.payloadJson,Payload.class); }
    private ReadingWork owned(boolean index,UUID id) {
        var w=mapper.get(index,id);
        if(w==null || w.deleted!=0 || !w.ownerId.equals(states.owner()))throw ReadingException.missing();
        return w;
    }
    private void revision(ReadingWork w,Long expected) {
        if(expected==null || expected!=w.revision)throw ReadingException.conflict("REVISION_CONFLICT","任务已变化，请刷新");
    }
    private void save(boolean index,ReadingWork w,Payload p) {
        w.payloadJson=ReadingText.json(p);w.revision++;mapper.save(index,w);
        mapper.event(UUID.randomUUID(),index?null:w.id,index?w.id:null,w.revision,w.status);
    }
    private void dispatchAfterCommit(boolean index,UUID id) {
        queue.reserve(index);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCompletion(int status) {
                if(status==STATUS_COMMITTED) {
                    try { queue.submit(index,id); }
                    catch(RuntimeException e) { stop(index,id,null,"PAUSED","DISPATCH_INTERRUPTED"); }
                } else queue.release(index);
            }
        });
    }
    private Registered replay(boolean index,UUID owner,UUID key,String hash) {
        ReadingText.require(key!=null,"缺少幂等键");var old=mapper.replay(index,owner,key);
        if(old==null)return null;
        if(!old.requestHash.equals(hash))throw ReadingException.conflict("IDEMPOTENCY_CONFLICT","幂等键对应不同请求");
        if(old.deleted!=0)throw new ReadingException(410,"TASK_REMOVED","任务已移除");
        return new Registered(old.id,false);
    }
    public Registered create(Create r) {
        ReadingText.require(r.versionId()!=null && r.type()!=null,"任务字段不完整");
        String hash=ReadingText.hash(ReadingText.json(r));
        return scopes.current(r.versionId(),scope->{
            var old=replay(false,scope.ownerId(),r.clientRequestId(),hash);if(old!=null)return old;
            requireBoundary(scope,r.expectedScopeRevision());var p=new Payload();p.scope=scope;p.targetBoundary=scope.boundaryOrdinal();
            if("RECAP".equals(r.type())) {
                ReadingText.require(r.question()==null && r.mode()==null,"回顾不接受人物问题或模式");
                p.chapterCount=r.chapterCount()==null?3:r.chapterCount();
                ReadingText.require(p.chapterCount>=1 && p.chapterCount<=3,"请选择一至三章");
                p.fromOrdinal=mapper.recapStart(scope.versionId(),scope.boundaryOrdinal(),p.chapterCount);
                // Coverage must be complete before spending a generation call.
                p.evidence=recap(p);p.evidenceReady=true;prompt(p,false);
            } else if("CHARACTER_LOOKUP".equals(r.type())) {
                ReadingText.require(r.chapterCount()==null,"人物查证不接受章节范围");
                p.question=ReadingText.bounded(r.question(),1,1000,"人物问题");limit(p.question,1000,4000,"INPUT_LIMIT");
                p.mode=r.mode()==null?"DIRECT":r.mode();ReadingText.require(Set.of("DIRECT","MULTI_QUERY").contains(p.mode),"未知查证模式");
                if(mapper.readyBoundary(scope.versionId(),scope.boundaryOrdinal(),p.profile)<scope.boundaryOrdinal())throw failure("INDEX_NOT_READY");
                if("DIRECT".equals(p.mode))p.queries=List.of(p.question);
            } else throw ReadingException.invalid("INVALID_REQUEST","未知任务类型");
            ai.requireAuthorized();
            var w=row(scope,r.clientRequestId(),hash,r.type(),p);
            mapper.insert(false,w);persistEvidence(w,p);dispatchAfterCommit(false,w.id);return new Registered(w.id,true);
        });
    }
    private static void requireBoundary(ReadingScopeService.Snapshot s,Long revision) {
        if(revision==null || revision!=s.scopeRevision())throw failure("SCOPE_CHANGED");
        if(s.boundaryOrdinal()==0)throw failure("READ_BOUNDARY_REQUIRED");
    }
    private ReadingWork row(ReadingScopeService.Snapshot s,UUID key,String hash,String type,Payload p) {
        var w=new ReadingWork();w.id=UUID.randomUUID();w.ownerId=s.ownerId();w.bookId=s.bookId();w.versionId=s.versionId();
        w.clientRequestId=key;w.requestHash=hash;w.type=type;w.status="QUEUED";w.executionId=UUID.randomUUID();
        w.requiredBoundary=s.boundaryOrdinal();w.traceId=UUID.randomUUID();w.payloadJson=ReadingText.json(p);return w;
    }
    private List<Evidence> recap(Payload p) {
        var spans=new ArrayList<Span>();int next=p.fromOrdinal,characters=0;
        while(next<=p.scope.boundaryOrdinal()) {
            var rows=mapper.paragraphs(p.scope.ownerId(),p.scope.bookId(),p.scope.versionId(),next,p.scope.boundaryOrdinal(),100);
            if(rows.isEmpty())throw failure("COVERAGE_LIMIT");
            for(var paragraph:rows) {
                if(paragraph.paragraphOrdinal()!=next++)throw failure("COVERAGE_LIMIT");
                characters+=paragraph.codePointCount();if(characters>24000)throw failure("COVERAGE_LIMIT");
                spans.addAll(split(paragraph));
            }
        }
        return pack(spans);
    }
    public Estimate estimate(UUID version,Long revision) {
        return scopes.current(version,s->{requireBoundary(s,revision);return estimate(s);});
    }
    private Estimate estimate(ReadingScopeService.Snapshot s) {
        String profile=new Payload().profile;
        int ready=mapper.readyBoundary(s.versionId(),s.boundaryOrdinal(),profile),target=ready,chars=0,segments=0,batches=0,batchSize=0;
        // 500 paragraphs suffice: each paragraph consumes at least one of the 500 segments.
        for(var p:mapper.paragraphs(s.ownerId(),s.bookId(),s.versionId(),ready+1,s.boundaryOrdinal(),500)) {
            int count=(p.codePointCount()+999)/1000;
            int added=batchSize+count>16?1:0;
            if(chars+p.codePointCount()>100000 || segments+count>500 || batches+added+1>32)break;
            if(added==1){batches++;batchSize=0;}batchSize+=count;
            segments+=count;chars+=p.codePointCount();target=p.paragraphOrdinal();
        }
        if(batchSize>0)batches++;
        String hash=ReadingText.hash(ReadingText.json(List.of(s.ownerId(),s.versionId(),s.scopeRevision(),s.visibilityEpoch(),ready,target,segments,chars,batches,profile)));
        boolean allowed;
        try { ai.requireAuthorized();allowed=true; } catch(ReadingException e) { allowed=false; }
        return new Estimate(ready,target,s.boundaryOrdinal(),segments,chars,batches,profile,hash,allowed,null,
                allowed?"离线测试适配器；不代表真实费用估算":"LIVE_AI_NOT_AUTHORIZED");
    }
    public Object index(UUID version) {
        return scopes.current(version,s->{
            int ready=mapper.readyBoundary(version,s.boundaryOrdinal(),new Payload().profile);
            var result=new LinkedHashMap<String,Object>();
            result.putAll(Map.of("profile",new Payload().profile,"readyBoundary",ready,"requestedBoundary",s.boundaryOrdinal(),
                    "ready",s.boundaryOrdinal()>0 && ready==s.boundaryOrdinal(),"dataAsOf",s.dataAsOf(),"liveAllowed",authorized()));
            var active=mapper.activeIndexWork(s.ownerId(),version);result.put("activeJob",active==null?null:view(active,s));return result;
        });
    }
    private boolean authorized() { try { ai.requireAuthorized();return true; }catch(ReadingException e){return false;} }
    public Registered createIndex(UUID version,IndexCreate r) {
        String hash=ReadingText.hash(ReadingText.json(List.of(version,r)));
        return scopes.current(version,s->{
            var old=replay(true,s.ownerId(),r.clientRequestId(),hash);if(old!=null)return old;
            requireBoundary(s,r.expectedScopeRevision());var estimate=estimate(s);
            if(!estimate.estimateHash().equals(r.estimateHash()))throw failure("ESTIMATE_CHANGED");
            if(estimate.targetBoundary()<=estimate.fromBoundary())throw failure("INDEX_ALREADY_READY");
            if(mapper.activeIndex(s.ownerId(),version)>0)throw failure("INDEX_ACTIVE");
            ai.requireAuthorized();var p=new Payload();p.scope=s;p.fromOrdinal=estimate.fromBoundary()+1;
            p.completedParagraph=estimate.fromBoundary();p.targetBoundary=estimate.targetBoundary();
            var w=row(s,r.clientRequestId(),hash,"INDEX",p);mapper.insert(true,w);dispatchAfterCommit(true,w.id);
            return new Registered(w.id,true);
        });
    }
    public Object get(boolean index,UUID id) {
        var w=owned(index,id);return scopes.current(w.versionId,s->view(owned(index,id),s));
    }
    // Callback lets SSE serialize under the same scope lock as boundary writes.
    public void send(UUID id,Consumer<Object> send) {
        var w=owned(false,id);scopes.current(w.versionId,s->{send.accept(view(owned(false,id),s));return null;});
    }
    private Map<String,Object> view(ReadingWork w,ReadingScopeService.Snapshot current) {
        var v=new LinkedHashMap<String,Object>();v.put("id",w.id);v.put("status",w.status);v.put("revision",w.revision);
        if(!ReadingScopeService.visible(current,w.versionId,w.requiredBoundary)) { v.put("visibility","HIDDEN_BY_SCOPE");return v; }
        var p=payload(w);v.put("visibility","VISIBLE");v.put("type",w.type);v.put("errorCode",w.errorCode);v.put("traceId",w.traceId);
        v.put("scope",Map.of("versionId",w.versionId,"boundaryOrdinal",p.scope.boundaryOrdinal(),"scopeRevision",p.scope.scopeRevision(),"visibilityEpoch",p.scope.visibilityEpoch()));
        v.put("currentScopeRevision",current.scopeRevision());v.put("visibilityEpoch",current.visibilityEpoch());
        v.put("question",p.question);v.put("validatedResult",p.result);v.put("dataAsOf",current.dataAsOf());
        v.put("message","INSUFFICIENT_EVIDENCE".equals(w.status)?"在当前已读范围内无法确认":null);
        v.put("coverage",Map.of("requestedRange",List.of(p.fromOrdinal,w.requiredBoundary),"targetBoundary",p.targetBoundary,
                "completedParagraph",p.completedParagraph,"evidenceRanges",p.evidence.stream().flatMap(e->e.sources().stream()).map(Span::paragraphOrdinal).distinct().toList(),
                "completeness",p.result==null || p.result.facts().isEmpty()?"NONE":"RECAP".equals(w.type)?"FULL_WINDOW":p.result.partial()?"PARTIAL":"SEARCH_MATCHES"));
        v.put("budget",Map.of("activeMillis",elapsed(w),"limitMillis",deadline(w),"modelCalls",p.modelCalls,"embeddingCalls",p.embeddingCalls,
                "inputCharacters",p.inputCharacters,"inputBytes",p.inputBytes));
        // No usage or money is invented when the provider did not return it.
        var calls=new ArrayList<Map<String,Object>>();boolean known=true;long inputs=0,outputs=0;
        for(var raw:mapper.calls(w.id)) {
            var call=new LinkedHashMap<String,Object>();call.put("ordinal",raw.get("ordinal"));call.put("phase",raw.get("phase"));call.put("status",raw.get("status"));
            Map<?,?> actual=raw.get("usage")==null?Map.of():ReadingText.read(raw.get("usage").toString(),Map.class);
            call.put("inputTokens",actual.get("inputTokens"));call.put("outputTokens",actual.get("outputTokens"));
            call.put("usageStatus",actual.getOrDefault("usageStatus",null)==null?"UNKNOWN":actual.get("usageStatus"));
            call.put("estimatedInputTokens",raw.get("input_bytes"));call.put("reservedAmount",raw.get("reserved_amount"));call.put("settledAmount",raw.get("settled_amount"));
            if(actual.get("inputTokens") instanceof Number in && actual.get("outputTokens") instanceof Number out){inputs+=in.longValue();outputs+=out.longValue();}else known=false;
            calls.add(call);
        }
        var usage=new LinkedHashMap<String,Object>();usage.put("usageStatus",known && !calls.isEmpty()?"ACTUAL":"UNKNOWN");
        usage.put("inputTokens",known && !calls.isEmpty()?inputs:null);usage.put("outputTokens",known && !calls.isEmpty()?outputs:null);
        usage.put("calls",calls);usage.put("tokenEstimateMethod","UTF8_BYTES_UPPER_BOUND");v.put("usage",usage);
        return v;
    }
    public Object list(UUID version,int page,int size) {
        ReadingText.require(page>=1 && size>=1 && size<=50,"分页参数不正确");
        return scopes.current(version,s->Map.of("items",mapper.list(false,s.ownerId(),version,size,(long)(page-1)*size).stream().map(w->view(w,s)).toList(),"page",page,"size",size));
    }
    public Object command(boolean index,UUID id,Command r) {
        ReadingText.require(r.clientRequestId()!=null && r.command()!=null && Set.of("PAUSE","RESUME","CANCEL").contains(r.command()),"任务命令不正确");
        var initial=owned(index,id);String hash=ReadingText.hash(ReadingText.json(List.of(index,id,r)));
        return scopes.current(initial.versionId,s->{
            var w=owned(index,id);var p=payload(w);String old=mapper.commandHash(s.ownerId(),r.clientRequestId());
            if(old!=null) { if(!old.equals(hash))throw failure("IDEMPOTENCY_CONFLICT");return view(w,s); }
            revision(w,r.expectedRevision());if(w.terminal())throw failure("TASK_TERMINAL");
            if("RESUME".equals(r.command())) {
                if(!"PAUSED".equals(w.status))throw failure("TASK_NOT_PAUSED");
                checkScope(p,s);
                if(mapper.unfinished(id)>0)throw failure("INTERRUPTED_CALL");
                if(elapsed(w)>=deadline(w))throw failure("BUDGET_EXHAUSTED");
                ai.requireAuthorized();w.status="QUEUED";w.errorCode=null;w.executionId=UUID.randomUUID();w.queuedAt=OffsetDateTime.now();
                dispatchAfterCommit(index,w.id);
            } else { settle(w);w.status="CANCEL".equals(r.command())?"CANCELLED":"PAUSED";w.executionId=null;mapper.unknown(id); }
            save(index,w,p);mapper.command(UUID.randomUUID(),s.ownerId(),index?null:id,index?id:null,r.clientRequestId(),hash,r.command(),w.revision);
            return view(w,s);
        });
    }
    public void delete(UUID id,long revision) {
        var first=owned(false,id);scopes.current(first.versionId,s->{
            var w=owned(false,id);revision(w,revision);if(!w.terminal())throw failure("TASK_NOT_TERMINAL");
            w.deleted=1;save(false,w,payload(w));return null;
        });
    }
    public Object citation(UUID id,String key) {
        var first=owned(false,id);return scopes.current(first.versionId,s->{
            var w=owned(false,id);ReadingScopeService.requireVisible(s,w.versionId,w.requiredBoundary);
            var p=payload(w);
            if(p.result==null || p.result.facts().stream().noneMatch(f->f.evidenceIds().contains(key)))throw ReadingException.missing();
            String json=mapper.evidenceJson(id,key);if(json==null)throw ReadingException.missing();
            var e=ReadingText.read(json,Evidence.class);
            return Map.of("evidenceId",key,"sources",e.sources().stream().map(span->{
                verify(p,span);return scopes.source(s,span.paragraphId(),span.startOffset(),span.endOffset(),w.requiredBoundary);
            }).toList(),"scopeRevision",s.scopeRevision(),"visibilityEpoch",s.visibilityEpoch());
        });
    }
    static long elapsed(ReadingWork w) { return w.activeMillis+(w.startedAt==null?0:Math.max(0,Duration.between(w.startedAt,OffsetDateTime.now()).toMillis())); }
    private static void settle(ReadingWork w) { w.activeMillis=elapsed(w);w.startedAt=null; }
    private static void checkScope(Payload p,ReadingScopeService.Snapshot s) {
        if(p.scope.visibilityEpoch()!=s.visibilityEpoch() || p.scope.boundaryOrdinal()>s.boundaryOrdinal())throw failure("SCOPE_CHANGED");
    }
    ReadingWork activate(boolean index,UUID id) {
        var first=owned(index,id);
        return scopes.publish(payload(first).scope,s->{
            var w=owned(index,id);
            if(!"QUEUED".equals(w.status))throw new java.util.concurrent.CancellationException();
            if(Duration.between(w.queuedAt,OffsetDateTime.now()).toMillis()>30000)throw failure("QUEUE_TIMEOUT");
            w.status="RUNNING";w.startedAt=OffsetDateTime.now();save(index,w,payload(w));return w;
        });
    }
    ReadingWork current(boolean index,UUID id,UUID token) {
        var first=owned(index,id);
        return scopes.publish(payload(first).scope,s->{var w=owned(index,id);checkRunning(w,token);return w;});
    }
    private static void checkRunning(ReadingWork w,UUID token) {
        if(!"RUNNING".equals(w.status) || !Objects.equals(w.executionId,token))throw new java.util.concurrent.CancellationException();
        if(elapsed(w)>=deadline(w))throw failure("TIMEOUT");
    }
    void checkpoint(boolean index,UUID id,UUID token,Consumer<Payload> edit,String status) {
        var first=owned(index,id);scopes.publish(payload(first).scope,s->{
            var w=owned(index,id);checkRunning(w,token);var p=payload(w);edit.accept(p);
            if(!index)persistEvidence(w,p);
            if(status!=null){settle(w);w.status=status;w.executionId=null;}
            save(index,w,p);return null;
        });
    }
    private void persistEvidence(ReadingWork w,Payload p) {
        for(var e:p.evidence) {
            e.sources().forEach(span->verify(p,span));
            mapper.evidence(UUID.randomUUID(),w.id,w.versionId,e.id(),ReadingText.json(e),ReadingText.hash(e.text()));
        }
    }
    void verify(Payload p,Span span) {
        var original=books.scopedParagraph(p.scope.ownerId(),p.scope.bookId(),p.scope.versionId(),span.paragraphId(),p.scope.boundaryOrdinal());
        if(original==null || !original.chapterId().equals(span.chapterId()) || original.paragraphOrdinal()!=span.paragraphOrdinal()
                || !original.textHash().equals(span.textHash()) || span.startOffset()<0 || span.endOffset()>original.codePointCount()
                || span.endOffset()<=span.startOffset() || !ReadingText.slice(original.text(),span.startOffset(),span.endOffset()).equals(span.text()))
            throw failure("EVIDENCE_INVALID");
    }
    UUID reserveCall(boolean index,UUID id,UUID token,String phase,String input,int outputLimit) {
        ai.requireAuthorized();var first=owned(index,id);
        return scopes.publish(payload(first).scope,s->{
            var w=owned(index,id);checkRunning(w,token);var p=payload(w);
            if(mapper.phaseCalls(id,phase)>0)throw failure("INTERRUPTED_CALL");
            if("PLAN".equals(phase) || "ANSWER".equals(phase)) {
                int max="MULTI_QUERY".equals(p.mode)?2:1;
                if(p.modelCalls>=max)throw failure("BUDGET_EXHAUSTED");
                p.modelCalls++;p.inputCharacters+=ReadingText.length(input);p.inputBytes+=bytes(input);
                if(p.inputCharacters>36864 || p.inputBytes>73728)throw failure("INPUT_LIMIT");
            } else {
                if(p.embeddingCalls>=(index?32:"MULTI_QUERY".equals(p.mode)?3:1))throw failure("BUDGET_EXHAUSTED");p.embeddingCalls++;
            }
            UUID call=UUID.randomUUID();mapper.call(call,index?null:id,index?id:null,token,p.modelCalls+p.embeddingCalls,phase,ReadingText.length(input),bytes(input),outputLimit);
            if(!ai.offline())spend.reserve(w.ownerId,call,ai.pricing(phase),bytes(input),outputLimit);
            save(index,w,p);return call;
        });
    }
    void callFinished(UUID call,String status,ReadingAiGateway.Response response) {
        var usage=new LinkedHashMap<String,Object>();usage.put("inputTokens",response==null?null:response.inputTokens());usage.put("outputTokens",response==null?null:response.outputTokens());
        usage.put("usageStatus",response!=null && response.inputTokens()!=null && response.outputTokens()!=null?"ACTUAL":"UNKNOWN");
        mapper.finishCall(call,status,ReadingText.json(usage));
        if(response!=null)spend.settle(states.owner(),call,response);
    }
    List<Span> search(Payload p,float[] vector) {
        return scopes.publish(p.scope,s->{
            if(mapper.readyBoundary(s.versionId(),s.boundaryOrdinal(),p.profile)<s.boundaryOrdinal())throw failure("INDEX_NOT_READY");
            var rows=mapper.search(s.ownerId(),s.bookId(),s.versionId(),s.boundaryOrdinal(),p.profile,vector(vector));
            rows.forEach(span->verify(p,span));return rows;
        });
    }
    List<Span> indexBatch(Payload p) {
        return scopes.publish(p.scope,s->{
            var spans=new ArrayList<Span>();
            for(var paragraph:mapper.paragraphs(s.ownerId(),s.bookId(),s.versionId(),p.completedParagraph+1,p.targetBoundary,16)) {
                var next=split(paragraph);if(spans.size()+next.size()>16)break;spans.addAll(next);
            }
            return spans;
        });
    }
    void indexed(UUID id,UUID token,List<Span> spans,List<float[]> vectors) {
        if(vectors==null || vectors.size()!=spans.size())throw failure("OUTPUT_INVALID");
        var first=owned(true,id);scopes.publish(payload(first).scope,s->{
            var w=owned(true,id);checkRunning(w,token);var p=payload(w);
            for(int i=0;i<spans.size();i++) {
                var span=spans.get(i);verify(p,span);UUID segment=UUID.randomUUID();
                mapper.segment(segment,w.versionId,span,p.profile,w.id);mapper.vector(segment,span.text(),vector(vectors.get(i)));
            }
            p.completedParagraph=spans.getLast().paragraphOrdinal();save(true,w,p);return null;
        });
    }
    void stop(boolean index,UUID id,UUID token,String status,String code) {
        var first=mapper.get(index,id);if(first==null)return;
        try { scopes.current(first.versionId,s->{stopLocked(index,id,token,status,code);return null;}); }
        catch(ReadingException e) { if(e.status()!=404)throw e;stopLocked(index,id,token,"INVALIDATED","RESOURCE_REMOVED"); }
    }
    private void stopLocked(boolean index,UUID id,UUID token,String status,String code) {
        var w=mapper.get(index,id);if(w==null || w.terminal() || (token!=null && !token.equals(w.executionId)))return;
        settle(w);w.status=status;w.errorCode=code;w.executionId=null;mapper.unknown(id);save(index,w,payload(w));
    }
    void recover() {
        for(boolean index:List.of(false,true))active(index,w->{
            if(!"PAUSED".equals(w.status))stop(index,w.id,null,"PAUSED","RESTARTED");
        });
    }
    private void active(boolean index,Consumer<ReadingWork> visit) {
        UUID after=null;
        while(true) {
            var page=mapper.active(index,after);if(page.isEmpty())return;
            page.forEach(visit);after=page.getLast().id;if(page.size()<100)return;
        }
    }
    void sweep() {
        for(boolean index:List.of(false,true))active(index,w->{
            try {
                scopes.publish(payload(w).scope,s->{
                    var latest=mapper.get(index,w.id);
                    if("QUEUED".equals(latest.status) && Duration.between(latest.queuedAt,OffsetDateTime.now()).toMillis()>30000)
                        stopLocked(index,w.id,latest.executionId,"FAILED","QUEUE_TIMEOUT");
                    if("RUNNING".equals(latest.status) && elapsed(latest)>=deadline(latest))
                        stopLocked(index,w.id,latest.executionId,"FAILED","TIMEOUT");
                    return null;
                });
            } catch(ReadingException e) { stop(index,w.id,null,"INVALIDATED",e.status()==404?"RESOURCE_REMOVED":"SCOPE_CHANGED"); }
        });
    }
}
