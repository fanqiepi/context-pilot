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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Frozen TEST inputs. Real model and retrieval; release fixtures are explicitly synthetic. */
@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=openai",
    "spring.ai.vectorstore.type=pgvector","contextpilot.document.processing.enabled=true",
    "contextpilot.upgrade-research.enabled=false","contextpilot.storage.root=target/v6-fixture-uploads"})
@ActiveProfiles("local-secrets") @Testcontainers
@EnabledIfSystemProperty(named="contextpilot.evaluation.v6-fixture",matches="true")
class V6FixtureEvaluationTests {
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
    @Autowired tools.jackson.databind.ObjectMapper json;
    @MockitoSpyBean RetrievalService retrieval;
    @Value("${spring.ai.deepseek.api-key}") String key;

    @Test @Timeout(3600) void collectFrozenSemanticCasesWithoutInventingHumanScores() throws Exception {
        Path root=Path.of("../evals"), corpus=root.resolve("corpora/v6-upgrade-research-v1");
        var results=new ArrayList<Map<String,Object>>();
        var report=new LinkedHashMap<String,Object>();report.put("datasetId","v6-upgrade-research-v1");
        report.put("mode","REAL_MODEL_REAL_RETRIEVAL_SYNTHETIC_RELEASE");report.put("startedAt",OffsetDateTime.now().toString());
        report.put("results",results);report.put("semanticReview","NOT_REVIEWED");
        UUID kb=bases.create(new KnowledgeBaseCreateRequest("V6 frozen fixture evaluation",null)).id();
        Map<String,UUID> ids=new LinkedHashMap<>();
        for(String name:List.of("legacy-client.md","legacy-storage.md"))ids.put(name,documents.upload(kb,
            new MockMultipartFile("file",name,"text/markdown",Files.readAllBytes(corpus.resolve(name)))).id());
        long until=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        while(documents.list(kb).stream().anyMatch(d->d.status()!=DocumentStatus.SUCCEEDED)){
            assertThat(System.nanoTime()).isLessThan(until);Thread.sleep(250);
        }
        for(String line:Files.readAllLines(root.resolve("datasets/v6-upgrade-research-v1.jsonl"))){
            var task=UpgradeJson.MAPPER.readTree(line);String id=task.path("id").asText();
            var row=new LinkedHashMap<String,Object>();row.put("caseId",id);row.put("expectedTerminal",task.path("terminal").asText());
            row.put("manualReview","NOT_REVIEWED");results.add(row);
            if(Integer.parseInt(id.substring(3))>=20){row.put("status","CONTROLLED_FAULT_TEST_SEE_UpgradeRunPersistenceTests");continue;}
            Set<UUID> selected=new LinkedHashSet<>();Set<String> tags=new LinkedHashSet<>();
            for(var source:task.path("sources")){
                String file=source.asText().split("#")[0];if(ids.containsKey(file))selected.add(ids.get(file));
                if(file.startsWith("release-fixture-"))tags.add(file.replace("release-","").replace(".md",""));
            }
            if(selected.isEmpty())selected.add(ids.get("legacy-client.md"));if(tags.isEmpty())tags.add("fixture-2");
            if(id.equals("V6-016"))tags.add("fixture-missing");
            var releases=mock(ReleaseNotesClient.class);
            when(releases.read(anyString(),any())).thenAnswer(call->{
                String tag=call.getArgument(0);Path file=corpus.resolve("release-"+tag+".md");
                if(!Files.exists(file))throw new UpgradeToolException("RELEASE_NOT_FOUND");
                String body=Files.readString(file);return new ReleaseNotesClient.Release(tag.equals("fixture-2")?2:3,tag,
                    "Synthetic evaluation",body,false,false,"https://github.com/spring-projects/spring-ai/releases/tag/"+tag,
                    "2026-01-01T00:00:00Z",OffsetDateTime.now().toString(),UpgradeJson.hash(body));
            });
            if(id.equals("V6-018"))doReturn(List.of()).when(retrieval).searchDocument(any(),any(),anyString(),anyInt(),anyString());
            long started=System.nanoTime();
            var worker=new UpgradeResearchExecutor(state,execution,new UpgradeResearchModelGateway(key),retrieval,releases,
                new org.springframework.core.io.ClassPathResource("prompts/upgrade-research-v1.txt"),0.5);
            try{
                var run=state.register(new UpgradeStartRequest(UUID.randomUUID(),kb,new ArrayList<>(selected),"spring-ai",new ArrayList<>(tags),task.path("question").asText(),null),id+"-"+UUID.randomUUID()).run();
                worker.execute(run.getId());var view=query.get(run.getId());
                row.put("status",view.run().getStatus());row.put("run",view.run());row.put("sources",view.sources());
                row.put("tools",view.tools());row.put("modelCalls",view.modelCalls());
                row.put("terminalMatches",view.run().getStatus().name().equals(task.path("terminal").asText()));
                assertThat(view.run().getModelCalls()).isLessThanOrEqualTo(4);assertThat(view.run().getToolRequests()).isLessThanOrEqualTo(8);
            }catch(Exception error){row.put("status","HARNESS_FAILED");row.put("errorType",error.getClass().getSimpleName());}
            finally{worker.shutdown();reset(retrieval);row.put("latencyMs",(System.nanoTime()-started)/1_000_000);
                Files.writeString(Path.of("target/v6-fixture-evaluation.json"),json.writeValueAsString(report));}
        }
        report.put("finishedAt",OffsetDateTime.now().toString());
        Files.writeString(Path.of("target/v6-fixture-evaluation.json"),json.writeValueAsString(report));
        assertThat(results).hasSize(24);
    }
}
