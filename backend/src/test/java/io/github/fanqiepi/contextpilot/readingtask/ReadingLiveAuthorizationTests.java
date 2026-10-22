package io.github.fanqiepi.contextpilot.readingtask;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.github.fanqiepi.contextpilot.book.*;
import io.github.fanqiepi.contextpilot.readingstate.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadingLiveAuthorizationTests {
    @TempDir Path root;
    final JsonMapper json=JsonMapper.builder().build();
    Path plan,approval;
    Map<String,Object> approved;
    Clock clock=Clock.fixed(Instant.parse("2026-10-22T04:00:00Z"),ZoneOffset.UTC);
    @BeforeEach void prepare() throws Exception {
        Files.createDirectories(root.resolve("evals/configs/reading"));Files.createDirectories(root.resolve("evals/datasets/reading"));
        byte[] datasetBytes=Files.readAllBytes(Path.of("../evals/datasets/reading/acceptance-v1.json"));
        Files.write(root.resolve(ReadingLiveBatch.DATA),datasetBytes);Files.writeString(root.resolve("probe.txt"),"frozen fixture");
        var dataset=json.readTree(datasetBytes);var bindings=new ArrayList<Object>();
        for(var corpus:dataset.path("corpora")) {
            var paragraphs=new ArrayList<Object>();for(var p:corpus.path("paragraphs"))paragraphs.add(Map.of("id",UUID.randomUUID(),"ordinal",p.path("ordinal").asInt(),"sha256",p.path("sha256").asText()));
            bindings.add(Map.of("corpusId",corpus.path("id").asText(),"corpusSha256",corpus.path("sha256").asText(),"bookId",UUID.randomUUID(),"versionId",UUID.randomUUID(),"paragraphs",paragraphs));
        }
        byte[] lock=json.writeValueAsBytes(Map.of("version",ReadingLiveBatch.VERSION,"assetHashes",Map.of(ReadingLiveBatch.DATA,ReadingText.hash(datasetBytes)),"sourceHashes",Map.of("probe.txt",ReadingText.hash("frozen fixture"))));
        Files.write(root.resolve(ReadingLiveBatch.LOCK),lock);
        var proposal=new LinkedHashMap<String,Object>();proposal.put("schema","reading-live-plan-v1");proposal.put("version",ReadingLiveBatch.VERSION);
        proposal.put("batchId",UUID.randomUUID());proposal.put("implementationLockSha256",ReadingText.hash(lock));proposal.put("datasetSha256",ReadingText.hash(datasetBytes));
        proposal.put("generationModel",ReadingProviderClient.GENERATION_MODEL);proposal.put("embeddingModel",ReadingProviderClient.EMBEDDING_MODEL);
        proposal.put("generationEndpoint",ReadingProviderClient.GENERATION_URI);proposal.put("embeddingEndpoint",ReadingProviderClient.EMBEDDING_URI);proposal.put("embeddingDimensions",1024);proposal.put("bindings",bindings);
        plan=root.resolve("plan.json");Files.write(plan,json.writeValueAsBytes(proposal));approval=root.resolve("approval.json");
        approved=new LinkedHashMap<>();approved.put("schema","reading-live-approval-v1");approved.put("status","APPROVED");approved.put("authorizationId",UUID.randomUUID());
        approved.put("planSha256",ReadingText.hash(Files.readAllBytes(plan)));approved.put("accountConditionsConfirmed",true);approved.put("pricingVersion",ReadingLiveBatch.PRICE);
        approved.put("maxCalls",346);approved.put("limitCny","30");approved.put("providerLimitsCny",Map.of("DEEPSEEK","20","DASHSCOPE","10"));approved.put("authorizedAt","2026-10-22T03:00:00Z");approved.put("expiresAt","2026-10-23T02:00:00Z");approved.put("pricingVerifiedAt","2026-10-22T03:00:00Z");writeApproval();
    }
    void writeApproval() throws Exception { Files.write(approval,json.writeValueAsBytes(approved)); }
    ReadingLiveBatch load(){return ReadingLiveBatch.load(root,plan,approval,clock);}
    @Test void validApprovalDerivesOnlyFrozenCasesRepeatsAndIncrementalIndexes() {
        var batch=load();assertThat(batch.entries).hasSize(135);
        assertThat(batch.entries.values().stream().filter(e->"INDEX".equals(e.type())).count()).isEqualTo(27);
        assertThat(batch.entries.values().stream().filter(e->"DEBUG".equals(e.split())&&!"INDEX".equals(e.type())).count()).isEqualTo(12);
        assertThat(ReadingLiveAuthorization.tokenBound("ANSWER")).isEqualTo(1048576);
        assertThat(ReadingLiveAuthorization.tokenBound("SEARCH_0")).isEqualTo(128000);
        assertThat(ReadingLiveAuthorization.tokenBound("INDEX_0")).isEqualTo(2048000);
        assertThatThrownBy(()->ReadingLiveAuthorization.tokenBound("UNKNOWN")).isInstanceOf(ReadingException.class);
    }
    @Test void unapprovedExpiredOverBudgetStalePriceAndChangedPlanFailClosed() throws Exception {
        for(var change:List.of(Map.entry("status",(Object)"NOT_AUTHORIZED"),Map.entry("limitCny",(Object)"20.01"),
                Map.entry("expiresAt",(Object)"2026-10-22T03:30:00Z"),Map.entry("pricingVerifiedAt",(Object)"2026-10-20T03:00:00Z"),
                Map.entry("planSha256",(Object)"wrong"),Map.entry("accountConditionsConfirmed",(Object)false),Map.entry("maxCalls",(Object)347))) {
            Object old=approved.put(change.getKey(),change.getValue());writeApproval();
            assertThatThrownBy(this::load).isInstanceOf(ReadingException.class);approved.put(change.getKey(),old);
        }
        writeApproval();Files.writeString(root.resolve("probe.txt"),"changed implementation");assertThatThrownBy(this::load).isInstanceOf(ReadingException.class);
    }
    @Test void duplicateJsonCannotGrantApproval() throws Exception {
        Files.writeString(approval,"{\"status\":\"NOT_AUTHORIZED\",\"status\":\"APPROVED\"}");assertThatThrownBy(this::load).isInstanceOf(ReadingException.class);
    }
    @Test void providerLimitsCannotBorrowOrExceedApprovedAmounts() throws Exception {
        for(var limits:List.of(Map.of("DEEPSEEK","20.01","DASHSCOPE","9.99"),Map.of("DEEPSEEK","19","DASHSCOPE","11"),Map.of("DEEPSEEK","30"))) {
            approved.put("providerLimitsCny",limits);writeApproval();
            assertThatThrownBy(this::load).isInstanceOf(ReadingException.class);
        }
    }
    @Test void authorizationBindsRequestVersionBoundaryQuestionAndCorpus() {
        var batch=load();var mapper=mock(ReadingTaskMapper.class);var books=mock(BookMapper.class);var states=mock(ReadingStateService.class);UUID owner=UUID.randomUUID();
        when(states.owner()).thenReturn(owner);when(mapper.ledger(batch.id,owner)).thenReturn(Map.of("approval_digest",batch.digest,"limit_amount",batch.limit,"pricing_version",ReadingLiveBatch.PRICE,"call_limit",154));
        when(mapper.ledger(batch.embeddingId(),owner)).thenReturn(Map.of("approval_digest",batch.embeddingDigest(),"limit_amount",batch.embeddingLimit,"pricing_version",ReadingLiveBatch.PRICE,"call_limit",192));
        when(mapper.liveAvailable(batch.embeddingId(),owner,batch.embeddingDigest())).thenReturn(1);
        when(mapper.liveAvailable(batch.id,owner,batch.digest)).thenReturn(1);
        var gate=new ReadingLiveAuthorization(mapper,books,states,batch,false);
        var item=batch.entries.entrySet().stream().filter(e->e.getValue().caseId().equals("debug-005")).findFirst().orElseThrow();var e=item.getValue();
        var w=new ReadingWork();w.clientRequestId=item.getKey();w.ownerId=owner;w.bookId=e.bookId();w.versionId=e.versionId();w.type=e.type();
        var p=new ReadingWork.Payload();p.scope=new ReadingScopeService.Snapshot(owner,e.bookId(),e.versionId(),e.boundary(),1,1,OffsetDateTime.now(clock));p.mode=e.mode();p.question=e.question();
        when(states.version(owner,e.versionId())).thenReturn(new BookRows.Version(e.versionId(),owner,e.bookId(),e.fileHash(),"text",e.parserId(),1,"options",4,e.paragraphs().size(),100,OffsetDateTime.now(clock),0));
        for(var part:e.paragraphs())when(books.paragraph(e.versionId(),part.id())).thenReturn(new BookRows.Paragraph(part.id(),e.versionId(),UUID.randomUUID(),part.ordinal(),1,"fixture",7,part.hash()));
        assertThat(gate.pricing("ANSWER").authorizationId()).isEqualTo(batch.id);
        assertThat(gate.pricing("INDEX_0").authorizationId()).isEqualTo(batch.embeddingId());
        gate.authorize(w,p);assertThat(p.liveApprovalDigest).isEqualTo(batch.digest);
        p.question="不同问题";assertThatThrownBy(()->gate.authorize(w,p)).isInstanceOf(ReadingException.class);p.question=e.question();
        w.clientRequestId=UUID.randomUUID();assertThatThrownBy(()->gate.authorize(w,p)).isInstanceOf(ReadingException.class);w.clientRequestId=item.getKey();
        p.scope=new ReadingScopeService.Snapshot(owner,e.bookId(),e.versionId(),e.boundary()+1,2,1,OffsetDateTime.now(clock));assertThatThrownBy(()->gate.authorize(w,p)).isInstanceOf(ReadingException.class);
        var test=batch.entries.entrySet().stream().filter(x->x.getValue().caseId().equals("test-001")).findFirst().orElseThrow();var t=test.getValue();
        w.clientRequestId=test.getKey();w.versionId=t.versionId();w.bookId=t.bookId();w.type=t.type();p.question=t.question();p.mode=t.mode();p.chapterCount=t.chapters();
        p.scope=new ReadingScopeService.Snapshot(owner,t.bookId(),t.versionId(),t.boundary(),3,1,OffsetDateTime.now(clock));
        assertThatThrownBy(()->gate.authorize(w,p)).isInstanceOf(ReadingException.class).extracting(x->((ReadingException)x).code()).isEqualTo("DEBUG_REVIEW_REQUIRED");
        var reviewed=new ReadingLiveAuthorization(mapper,books,states,batch,true);
        assertThatThrownBy(()->reviewed.authorize(w,p)).isInstanceOf(ReadingException.class).extracting(x->((ReadingException)x).code()).isEqualTo("DEBUG_REVIEW_REQUIRED");
    }
    @Test void disabledGateDoesNotReadApprovalFilesOrTouchDatabase() {
        var mapper=mock(ReadingTaskMapper.class);
        var gate=new ReadingLiveAuthorization(mapper,mock(BookMapper.class),mock(ReadingStateService.class),false,"missing","missing","missing",false);
        assertThat(gate.status()).containsEntry("liveAllowed",false);assertThatThrownBy(gate::check).isInstanceOf(ReadingException.class);verifyNoInteractions(mapper);
    }
}
