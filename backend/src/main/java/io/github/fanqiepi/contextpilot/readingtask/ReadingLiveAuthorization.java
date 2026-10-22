package io.github.fanqiepi.contextpilot.readingtask;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import io.github.fanqiepi.contextpilot.book.*;
import io.github.fanqiepi.contextpilot.readingstate.ReadingStateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.github.fanqiepi.contextpilot.readingtask.ReadingTaskPolicy.failure;

@Service
public class ReadingLiveAuthorization {
    private final ReadingTaskMapper mapper;
    private final BookMapper books;
    private final ReadingStateService states;
    private final ReadingLiveBatch batch;
    private final boolean testReviewed;
    @org.springframework.beans.factory.annotation.Autowired
    public ReadingLiveAuthorization(ReadingTaskMapper mapper,BookMapper books,ReadingStateService states,
            @Value("${contextpilot.reading.live-enabled:false}")boolean enabled,
            @Value("${contextpilot.reading.live-repository:..}")String repository,
            @Value("${contextpilot.reading.live-plan:}")String plan,
            @Value("${contextpilot.reading.live-approval:}")String approval,
            @Value("${contextpilot.reading.live-debug-reviewed:false}")boolean testReviewed) {
        this(mapper,books,states,enabled?ReadingLiveBatch.load(Path.of(repository),Path.of(plan),Path.of(approval),Clock.systemUTC()):null,testReviewed);
    }
    ReadingLiveAuthorization(ReadingTaskMapper mapper,BookMapper books,ReadingStateService states,ReadingLiveBatch batch,boolean testReviewed) {
        this.mapper=mapper;this.books=books;this.states=states;this.batch=batch;this.testReviewed=testReviewed;
    }
    public void check() {
        if(batch==null)throw failure("LIVE_AI_NOT_AUTHORIZED");
        checkLedger(batch.id,batch.digest,batch.limit,154);
        checkLedger(batch.embeddingId(),batch.embeddingDigest(),batch.embeddingLimit,192);
    }
    private void checkLedger(UUID id,String digest,BigDecimal limit,int calls) {
        UUID owner=states.owner();mapper.installLive(id,owner,limit,ReadingLiveBatch.PRICE,batch.expiresAt,digest,calls);
        var row=mapper.ledger(id,owner);
        if(row==null || !digest.equals(row.get("approval_digest")) || limit.compareTo((BigDecimal)row.get("limit_amount"))!=0
                || !ReadingLiveBatch.PRICE.equals(row.get("pricing_version")) || ((Number)row.get("call_limit")).intValue()!=calls)
            throw failure("LIVE_APPROVAL_MISMATCH");
        if(mapper.liveAvailable(id,owner,digest)!=1)throw failure("LIVE_AUTHORIZATION_STOPPED");
    }
    @Transactional(timeout=5)
    public void authorize(ReadingWork work,ReadingWork.Payload payload) {
        check();var e=batch.entries.get(work.clientRequestId);
        if(e==null || !work.ownerId.equals(states.owner()) || !e.versionId().equals(work.versionId) || !e.bookId().equals(work.bookId)
                || e.boundary()!=payload.scope.boundaryOrdinal() || !e.type().equals(work.type)
                || !Objects.equals(e.mode(),payload.mode) || !Objects.equals(e.question(),payload.question)
                || e.chapters()!=payload.chapterCount || ("INDEX".equals(work.type) && (e.from()!=payload.fromOrdinal || e.target()!=payload.targetBoundary))
                || (payload.liveApprovalDigest!=null && !batch.digest.equals(payload.liveApprovalDigest)))throw failure("LIVE_BATCH_MISMATCH");
        if("TEST".equals(e.split())) {
            if(!testReviewed)throw failure("DEBUG_REVIEW_REQUIRED");
            for(var item:batch.entries.entrySet())if("DEBUG".equals(item.getValue().split())) {
                var debug=mapper.replay("INDEX".equals(item.getValue().type()),work.ownerId,item.getKey());
                if(debug==null || !Set.of("READY","COMPLETED","PARTIAL","INSUFFICIENT_EVIDENCE").contains(debug.status))throw failure("DEBUG_REVIEW_REQUIRED");
            }
        }
        var version=states.version(work.ownerId,work.versionId);
        if(!e.fileHash().equals(version.fileHash()) || !e.parserId().equals(version.parserId()) || version.paragraphCount()!=e.paragraphs().size())throw failure("LIVE_CORPUS_MISMATCH");
        for(var p:e.paragraphs()) {
            var actual=books.paragraph(work.versionId,p.id());
            if(actual==null || actual.paragraphOrdinal()!=p.ordinal() || !actual.textHash().equals(p.hash()))throw failure("LIVE_CORPUS_MISMATCH");
        }
        payload.liveApprovalDigest=batch.digest;
    }
    public ReadingAiGateway.Pricing pricing(String phase) {
        check();boolean generation=Set.of("PLAN","ANSWER").contains(phase);
        if(!generation && !phase.matches("(SEARCH|INDEX)_\\d+"))throw failure("PRICING_UNKNOWN");
        return new ReadingAiGateway.Pricing(generation?batch.id:batch.embeddingId(),ReadingLiveBatch.PRICE,generation?ReadingLiveBatch.INPUT:ReadingLiveBatch.EMBEDDING,
                generation?ReadingLiveBatch.OUTPUT:BigDecimal.ZERO);
    }
    @Transactional(timeout=5)
    public void halt() { halt("BATCH_STOPPED"); }
    @Transactional(timeout=5)
    public void halt(String reason) {
        if(batch==null)return;
        for(UUID id:List.of(batch.id,batch.embeddingId())) {
            var row=mapper.ledger(id,states.owner());
            if(row!=null)ReadingBudgetLog.stop(mapper,id,null,row,reason);
        }
    }
    static int tokenBound(String phase) {
        if(Set.of("PLAN","ANSWER").contains(phase))return ReadingLiveBatch.GENERATION_INPUT_BOUND;
        if(phase.matches("SEARCH_\\d+"))return ReadingLiveBatch.EMBEDDING_LINE_BOUND;
        if(phase.matches("INDEX_\\d+"))return 16*ReadingLiveBatch.EMBEDDING_LINE_BOUND;
        throw failure("PRICING_UNKNOWN");
    }
    @Transactional(timeout=5)
    public Map<String,Object> status() {
        if(batch==null)return Map.of("liveAllowed",false,"reason","LIVE_AI_NOT_AUTHORIZED");
        String reason="READY";try{check();}catch(ReadingException e){reason=e.code();}
        var row=mapper.ledger(batch.id,states.owner());var embedding=mapper.ledger(batch.embeddingId(),states.owner());var result=new LinkedHashMap<String,Object>();
        result.put("liveAllowed","READY".equals(reason));result.put("reason",reason);result.put("authorizationId",batch.id);
        result.put("planSha256",batch.planHash);result.put("implementationLockSha256",batch.lockHash);
        result.put("limitCny",batch.limit.add(batch.embeddingLimit));result.put("expiresAt",batch.expiresAt);result.put("maxCalls",ReadingLiveBatch.CALL_LIMIT);
        result.put("callsStarted",number(row,"calls_started")+number(embedding,"calls_started"));result.put("reservedCny",amount(row,"reserved_amount").add(amount(embedding,"reserved_amount")));
        result.put("budgetValuedCny",amount(row,"settled_amount").add(amount(embedding,"settled_amount")));result.put("debugReviewed",testReviewed);
        result.put("warningThresholdPercent",80);result.put("warningEmitted",warned(row)||warned(embedding));
        result.put("stopReason",row!=null && row.get("stop_reason")!=null?row.get("stop_reason"):embedding==null?null:embedding.get("stop_reason"));
        result.put("providers",Map.of("DEEPSEEK",provider(batch.id,batch.limit,row),"DASHSCOPE",provider(batch.embeddingId(),batch.embeddingLimit,embedding)));
        result.put("costBasis","PEAK_UNCACHED_BUDGET_VALUE_NOT_INVOICE");result.put("reservationBasis","PROVIDER_CONTEXT_CEILING");
        result.put("environment",Map.of("os",System.getProperty("os.name"),"java",System.getProperty("java.version"),"database",mapper.databaseVersion(),
                "model",ReadingProviderClient.GENERATION_MODEL,"embeddingProfile","dashscope_qwen3_7_1024_v1"));
        return result;
    }
    private static long number(Map<String,Object> row,String key) { return row==null?0:((Number)row.get(key)).longValue(); }
    private static BigDecimal amount(Map<String,Object> row,String key) { return row==null?BigDecimal.ZERO:(BigDecimal)row.get(key); }
    private static boolean warned(Map<String,Object> row) { return row!=null && Boolean.TRUE.equals(row.get("warning_emitted")); }
    private static Map<String,Object> provider(UUID id,BigDecimal limit,Map<String,Object> row) {
        var data=new LinkedHashMap<String,Object>();data.put("authorizationId",id);data.put("limitCny",limit);
        data.put("reservedCny",amount(row,"reserved_amount"));data.put("budgetValuedCny",amount(row,"settled_amount"));
        data.put("callsStarted",number(row,"calls_started"));data.put("warningEmitted",warned(row));data.put("stopReason",row==null?null:row.get("stop_reason"));return data;
    }
}
