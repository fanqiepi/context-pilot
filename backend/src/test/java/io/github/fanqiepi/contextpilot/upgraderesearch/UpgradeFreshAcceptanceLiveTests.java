package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.github.fanqiepi.contextpilot.document.*;
import io.github.fanqiepi.contextpilot.knowledgebase.*;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Frozen fresh TEST corpus; real model/retrieval and explicitly synthetic release. */
@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=openai",
        "spring.ai.vectorstore.type=pgvector","contextpilot.document.processing.enabled=true",
        "contextpilot.upgrade-research.enabled=false","contextpilot.storage.root=target/upgrade-scope-debug-uploads"})
@ActiveProfiles("local-secrets") @Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named="contextpilot.evaluation.upgrade-fresh-acceptance",matches="true")
class UpgradeFreshAcceptanceLiveTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("context_pilot").withUsername("context_pilot").withPassword("context_pilot_test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired KnowledgeBaseService bases;
    @Autowired DocumentService documents;
    @Autowired UpgradeRunStateService state;
    @Autowired UpgradeExecutionState execution;
    @Autowired UpgradeQueryService query;
    @Autowired RetrievalService retrieval;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Value("${spring.ai.deepseek.api-key}") String key;

    static java.util.stream.Stream<String> caseIds() throws Exception {
        var rows=UpgradeJson.MAPPER.readTree(Files.readString(Path.of("../evals/datasets/upgrade-fresh-acceptance-v1.json"))).path("cases");
        var ids=new ArrayList<String>(); for(var row:rows) ids.add(row.path("id").asText()); return ids.stream();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("caseIds")
    @Timeout(600) void collectDebugScopeEvidence(String debugCase) throws Exception {
        Path dataset=Path.of("../evals/datasets/upgrade-fresh-acceptance-v1.json");
        var cases=json.readTree(Files.readString(dataset)).path("cases");
        tools.jackson.databind.JsonNode fixture=null;
        for(var candidate:cases) if(candidate.path("id").asText().equals(debugCase)) fixture=candidate;
        assertThat(fixture).isNotNull();
        String prompt="upgrade-research-v6";
        Path output=Path.of("target/upgrade-fresh-acceptance-"+UUID.randomUUID()+".json");
        var report=new LinkedHashMap<String,Object>();
        report.put("mode","LIVE_FROZEN_TEST_REAL_RETRIEVAL_SYNTHETIC_RELEASE_HUMAN_REVIEW_PENDING");
        report.put("startedAt",OffsetDateTime.now().toString());report.put("actualPrompt",prompt);
        report.put("debugCase",debugCase); report.put("fixture",fixture);
        report.put("humanReview","NOT_REVIEWED");
        var hashes=new TreeMap<String,String>();
        try(var files=Files.walk(Path.of("src/main"))){for(var file:files.filter(Files::isRegularFile).toList())hashes.put(file.toString().replace('\\','/'),sha(file));}
        hashes.put(dataset.toString(),sha(dataset));
        hashes.put("harness",sha(Path.of("src/test/java/io/github/fanqiepi/contextpilot/upgraderesearch/UpgradeFreshAcceptanceLiveTests.java")));
        report.put("sourceHashes",hashes);
        UUID kb=bases.create(new KnowledgeBaseCreateRequest("Independent upgrade scope debug "+debugCase,null)).id();
        UUID doc=documents.upload(kb,new MockMultipartFile("file","legacy-worker.md","text/markdown",
                fixture.path("local").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8))).id();
        long deadline=System.nanoTime()+Duration.ofMinutes(2).toNanos();
        while(documents.list(kb).stream().anyMatch(d->d.status()!=DocumentStatus.SUCCEEDED)){
            assertThat(documents.list(kb)).noneMatch(d->d.status()==DocumentStatus.FAILED);
            assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(250);
        }
        String body=fixture.path("release").asText();
        var releases=mock(ReleaseNotesClient.class);
        when(releases.read(eq("debug-release"),any())).thenAnswer(call->new ReleaseNotesClient.Release(1,"debug-release",
                "Synthetic debug release",body,false,false,"https://github.com/spring-projects/spring-ai/releases/tag/debug-release",
                "2026-01-01T00:00:00Z",OffsetDateTime.now().toString(),UpgradeJson.hash(body)));
        var worker=new UpgradeResearchExecutor(state,execution,new UpgradeResearchModelGateway(key) {
            @Override public org.springframework.ai.chat.model.ChatResponse call(List<org.springframework.ai.chat.messages.Message> history, boolean toolsAllowed) {
                var response=super.call(history,toolsAllowed);
                if(response.getResult().getOutput().getToolCalls().isEmpty()) report.put("rawFinalOutput",response.getResult().getOutput().getText());
                return response;
            }
        },retrieval,releases,
                new org.springframework.core.io.ClassPathResource("prompts/"+prompt+".txt"),0.5);
        try {
            String question=fixture.path("question").asText();
            var run=state.register(new UpgradeStartRequest(UUID.randomUUID(),kb,List.of(doc),"spring-ai",List.of("debug-release"),question,null),
                    "upgrade-scope-debug-"+UUID.randomUUID()).run();
            worker.execute(run.getId());var view=query.get(run.getId());
            report.put("question",question);report.put("run",view.run());report.put("sources",view.sources());
            report.put("tools",view.tools());report.put("modelCalls",view.modelCalls());
            report.put("expectedTerminal",fixture.path("expectedTerminal").asText());
            // Observation collector: a terminal mismatch remains evidence, never a semantic pass.
            assertThat(view.run().getStatus().terminal()).isTrue();
        }finally{
            worker.shutdown();report.put("finishedAt",OffsetDateTime.now().toString());
            Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
    private static String sha(Path file)throws Exception{return HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
}
