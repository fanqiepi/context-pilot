package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.github.fanqiepi.contextpilot.document.*;
import io.github.fanqiepi.contextpilot.knowledgebase.*;
import io.github.fanqiepi.contextpilot.action.*;
import io.github.fanqiepi.contextpilot.researchreport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

/** Real production services/providers and mutable GitHub; never a human semantic score. */
@SpringBootTest(properties={"spring.ai.model.chat=deepseek","spring.ai.model.embedding=openai",
    "spring.ai.vectorstore.type=pgvector","contextpilot.document.processing.enabled=true",
    "contextpilot.upgrade-research.enabled=true","contextpilot.storage.root=target/representative-uploads"})
@ActiveProfiles("local-secrets") @Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named="contextpilot.evaluation.upgrade-representative",matches="true")
class UpgradeRepresentativeLiveTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName
        .parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("context_pilot").withUsername("context_pilot").withPassword("context_pilot_test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired KnowledgeBaseService bases;
    @Autowired DocumentService documents;
    @Autowired UpgradeResearchStartService starts;
    @Autowired UpgradeQueryService query;
    @Autowired ResearchReportProposalService proposals;
    @Autowired ResearchReportMapper reports;
    @Autowired ActionRequestService actions;
    @Autowired tools.jackson.databind.ObjectMapper json;

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> cases() throws Exception {
        var rows=UpgradeJson.MAPPER.readTree(Files.readString(Path.of("../evals/datasets/upgrade-representative-live-v1.json"))).path("cases");
        var result=new ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for(var row:rows) for(int repeat=1;repeat<=2;repeat++) result.add(org.junit.jupiter.params.provider.Arguments.of(row.path("id").asText(),repeat));
        return result.stream();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("cases")
    @Timeout(360) void collect(String caseId,int repeat) throws Exception {
        Path dataset=Path.of("../evals/datasets/upgrade-representative-live-v1.json");
        var rows=json.readTree(Files.readString(dataset)).path("cases");
        tools.jackson.databind.JsonNode fixture=null;
        for(var row:rows) if(row.path("id").asText().equals(caseId)) fixture=row;
        assertThat(fixture).isNotNull();
        var output=new LinkedHashMap<String,Object>();
        output.put("mode","LIVE_PRODUCTION_SERVICES_MUTABLE_GITHUB_NOT_UI_ACCEPTANCE");
        output.put("caseId",caseId);output.put("repeat",repeat);output.put("fixture",fixture);
        output.put("humanReview","NOT_REVIEWED");output.put("startedAt",OffsetDateTime.now().toString());
        var hashes=new TreeMap<String,String>();
        try(var files=Files.walk(Path.of("src/main"))){for(var file:files.filter(Files::isRegularFile).toList())hashes.put(file.toString().replace('\\','/'),sha(file));}
        hashes.put(dataset.toString(),sha(dataset));
        hashes.put("harness",sha(Path.of("src/test/java/io/github/fanqiepi/contextpilot/upgraderesearch/UpgradeRepresentativeLiveTests.java")));
        output.put("sourceHashes",hashes);
        Path artifact=Path.of("target/upgrade-representative-"+caseId+"-"+repeat+"-"+UUID.randomUUID()+".json");
        try {
            UUID kb=bases.create(new KnowledgeBaseCreateRequest(caseId+" repeat "+repeat,null)).id();
            UUID doc=documents.upload(kb,new MockMultipartFile("file","public-old-plan.md","text/markdown",
                fixture.path("local").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8))).id();
            long until=System.nanoTime()+Duration.ofSeconds(80).toNanos();
            while(documents.list(kb).stream().anyMatch(d->d.status()!=DocumentStatus.SUCCEEDED)){
                assertThat(documents.list(kb)).noneMatch(d->d.status()==DocumentStatus.FAILED);
                assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(250);
            }
            long began=System.nanoTime();
            var run=starts.start(new UpgradeStartRequest(UUID.randomUUID(),kb,List.of(doc),"spring-ai",
                List.of(fixture.path("tag").asText()),fixture.path("question").asText(),null),"representative-"+UUID.randomUUID()).run();
            UpgradeQueryService.View view;
            do {Thread.sleep(250);view=query.get(run.getId());assertThat(System.nanoTime()-began).isLessThan(Duration.ofSeconds(195).toNanos());}
            while(!view.run().getStatus().terminal());
            output.put("latencyMs",(System.nanoTime()-began)/1_000_000);
            output.put("run",view.run());output.put("tools",view.tools());output.put("modelCalls",view.modelCalls());
            // No third-party release body redistribution; retain identity/hash/time for review.
            output.put("sources",view.sources().stream().map(s->{var m=new LinkedHashMap<String,Object>();
                m.put("sourceId",s.sourceId());m.put("type",s.sourceType());m.put("hash",s.contentHash());
                m.put("truncated",s.truncated());m.put("fetchedAt",s.fetchedAt());return m;}).toList());
            output.put("expectedTerminal",fixture.path("expectedTerminal").asText());
            assertThat(view.run().getModelCalls()).isBetween(1,4);
            assertThat(view.run().getToolRequests()).isBetween(0,8);
            assertThat(reports.byRun(run.getId(),run.getOwnerId())).isNull();
            output.put("noAutomaticSave",true);
            if(view.run().getDraft()!=null){
                var proposal=proposals.propose(run.getId(),new ReportSaveRequest(UUID.randomUUID(),"公开代表场景 "+caseId,null));
                assertThat(reports.byRun(run.getId(),run.getOwnerId())).isNull();
                output.put("proposalId",proposal.id());
                assertThat(actions.confirm(proposal.id()).status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
                output.put("reportConfirmed",true);
            } else output.put("reportConfirmed",false);
            output.put("collectionStatus","OBSERVED_NOT_SEMANTIC_PASS");
        }catch(Exception|AssertionError error){output.put("collectionStatus","FAILED");output.put("errorType",error.getClass().getSimpleName());throw error;}
        finally{output.put("finishedAt",OffsetDateTime.now().toString());Files.writeString(artifact,json.writerWithDefaultPrettyPrinter().writeValueAsString(output));}
    }
    private static String sha(Path file)throws Exception{return HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
}
