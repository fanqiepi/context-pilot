package io.github.fanqiepi.contextpilot.readingtask;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.github.fanqiepi.contextpilot.book.*;
import io.github.fanqiepi.contextpilot.readingstate.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadingReaderTrialTests {
    @TempDir Path root;
    Map<String,Object> approval;Path file;
    final Clock clock=Clock.fixed(Instant.parse("2026-10-22T04:00:00Z"),ZoneOffset.UTC);
    @BeforeEach void prepare() throws Exception {
        Files.createDirectories(root.resolve("evals/configs/reading"));Files.writeString(root.resolve("source.txt"),"fixture");
        String lock=ReadingText.json(Map.of("version",ReadingLiveBatch.VERSION,"sourceHashes",Map.of("source.txt",ReadingText.hash("fixture")),"assetHashes",Map.of("source.txt",ReadingText.hash("fixture"))));
        Files.writeString(root.resolve(ReadingLiveBatch.LOCK),lock);file=root.resolve("approval.json");
        approval=new LinkedHashMap<>();approval.put("schema","reading-reader-trial-v1");approval.put("status","APPROVED");approval.put("executionScope",ReadingReaderTrial.SCOPE);
        approval.put("version",ReadingLiveBatch.VERSION);approval.put("authorizationId",UUID.randomUUID());approval.put("independentBudget",true);approval.put("accountConditionsConfirmed",true);
        approval.put("pricingVersion",ReadingLiveBatch.PRICE);approval.put("implementationLockSha256",ReadingText.hash(lock));
        approval.put("generationModel",ReadingProviderClient.GENERATION_MODEL);approval.put("embeddingModel",ReadingProviderClient.EMBEDDING_MODEL);
        approval.put("generationEndpoint",ReadingProviderClient.GENERATION_URI.toString());approval.put("embeddingEndpoint",ReadingProviderClient.EMBEDDING_URI.toString());approval.put("embeddingDimensions",1024);
        approval.put("authorizedAt","2026-10-22T03:00:00Z");approval.put("pricingVerifiedAt","2026-10-22T03:00:00Z");approval.put("expiresAt","2026-10-23T03:00:00Z");
        approval.put("providerLimitsCny",Map.of("DEEPSEEK","20","DASHSCOPE","10"));approval.put("generationCallLimit",100);approval.put("embeddingCallLimit",200);write();
    }
    void write() throws Exception { Files.writeString(file,ReadingText.json(approval)); }
    ReadingLiveBatch load(){return ReadingReaderTrial.load(root,file,"127.0.0.1",clock);}
    @Test void explicitIndependentApprovalHasNoEvaluationEntriesOrPredecessor() {
        var batch=load();assertThat(batch.entries).isEmpty();assertThat(batch.previous).isNull();assertThat(batch.limit).isEqualByComparingTo("20");
        assertThat(batch.embeddingLimit).isEqualByComparingTo("10");assertThat(batch.embeddingId()).isNotEqualTo(batch.id);
    }
    @Test void approvalFailsClosedForExpiredStaleInflatedChangedOrUnapprovedInputs() throws Exception {
        for(var change:List.of(Map.entry("status",(Object)"NOT_AUTHORIZED"),Map.entry("executionScope",(Object)"DEBUG_RETEST"),
                Map.entry("independentBudget",(Object)false),Map.entry("expiresAt",(Object)"2026-10-22T03:30:00Z"),
                Map.entry("pricingVerifiedAt",(Object)"2026-10-21T03:00:00Z"),Map.entry("generationCallLimit",(Object)101),
                Map.entry("providerLimitsCny",(Object)Map.of("DEEPSEEK","21","DASHSCOPE","9")),Map.entry("generationEndpoint",(Object)"https://example.org"))) {
            Object old=approval.put(change.getKey(),change.getValue());write();assertThatThrownBy(this::load).isInstanceOf(ReadingException.class);approval.put(change.getKey(),old);
        }
        write();assertThatThrownBy(()->ReadingReaderTrial.load(root,file,"0.0.0.0",clock)).isInstanceOf(ReadingException.class);
        approval.put("previousAuthorizationId",UUID.randomUUID());write();assertThatThrownBy(this::load).isInstanceOf(ReadingException.class);
        approval.remove("previousAuthorizationId");write();Files.writeString(root.resolve("source.txt"),"changed");assertThatThrownBy(this::load).isInstanceOf(ReadingException.class);
    }
    @Test void duplicateApprovalAndSimultaneousModesAreRejected() throws Exception {
        Files.writeString(file,"{\"status\":\"NOT_AUTHORIZED\",\"status\":\"APPROVED\"}");assertThatThrownBy(this::load).isInstanceOf(ReadingException.class);
        var mapper=mock(ReadingTaskMapper.class);
        assertThatThrownBy(()->new ReadingLiveAuthorization(mapper,mock(BookMapper.class),mock(ReadingStateService.class),true,"missing","missing","missing",false,true,"missing","127.0.0.1"))
                .isInstanceOf(ReadingException.class);verifyNoInteractions(mapper);
    }
}
