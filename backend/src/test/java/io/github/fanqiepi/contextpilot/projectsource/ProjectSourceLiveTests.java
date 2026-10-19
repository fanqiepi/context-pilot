package io.github.fanqiepi.contextpilot.projectsource;

import io.github.fanqiepi.contextpilot.action.*;
import io.github.fanqiepi.contextpilot.document.*;
import io.github.fanqiepi.contextpilot.knowledgebase.*;
import io.github.fanqiepi.contextpilot.taskworkspace.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Public debug flow. Opt-in real GitHub, Embedding and chat calls, not a semantic benchmark. */
@SpringBootTest(properties={"spring.ai.model.chat=deepseek","spring.ai.model.embedding=openai","spring.ai.vectorstore.type=pgvector",
        "PROJECT_SOURCE_ENABLED=true","contextpilot.task-workspace.enabled=true","contextpilot.document.processing.enabled=true",
        "contextpilot.storage.root=target/project-source-live-uploads"})
@ActiveProfiles("local-secrets")
@Testcontainers
@EnabledIfSystemProperty(named="contextpilot.evaluation.project-source-live",matches="true")
class ProjectSourceLiveTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    static final ProjectSourceMcpServer SERVER=new ProjectSourceMcpServer(0,new GitHubProjectSourceReader(System.getenv("GITHUB_PROJECT_SOURCE_TOKEN")));
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("PROJECT_SOURCE_MCP_URL",()->"http://127.0.0.1:"+SERVER.port());
    }
    @AfterAll static void closeServer(){SERVER.close();}
    @Autowired ProjectSourcePreviewService previews;
    @Autowired ProjectImportProposalService proposals;
    @Autowired ProjectImportMapper imports;
    @Autowired ActionRequestService actions;
    @Autowired KnowledgeBaseService bases;
    @Autowired DocumentService documents;
    @Autowired TaskService tasks;
    @Autowired TaskWorker worker;

    @Test @Timeout(420) void publicProjectPreviewConfirmIndexAndResearch() throws Exception {
        Map<String,Object> output=new LinkedHashMap<>();output.put("mode","PUBLIC_DEBUG_NOT_SEMANTIC_ACCEPTANCE");
        output.put("startedAt",Instant.now().toString());output.put("mcpProtocol","2025-11-25");
        try {
            var preview=previews.create(new ProjectSourcePreviewService.CreateRequest("spring-ai","v1.0.0",List.of("README.md"),UUID.randomUUID()));
            Instant until=Instant.now().plusSeconds(95);
            while(preview.status().equals("PENDING") || preview.status().equals("RUNNING")) {
                assertThat(Instant.now()).isBefore(until);Thread.sleep(200);preview=previews.get(preview.id());
            }
            output.put("previewStatus",preview.status());output.put("previewError",Objects.toString(preview.errorCode(),""));
            assertThat(preview.status()).as(preview.errorCode()).isEqualTo("READY");
            output.put("commit",preview.resolvedCommit());output.put("sourceHash",preview.items().getFirst().sha256());
            UUID base=bases.create(new KnowledgeBaseCreateRequest("Public project source flow",null)).id();
            var proposal=proposals.propose(preview.id(),new ProjectImportProposalService.Request(base,UUID.randomUUID()));
            assertThat(documents.list(base)).isEmpty();
            assertThat(actions.confirm(proposal.id()).status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
            assertThat(actions.confirm(proposal.id()).status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
            UUID document=imports.results(proposal.id()).getFirst();until=Instant.now().plusSeconds(100);
            while(documents.get(document).status()!=DocumentStatus.SUCCEEDED) {
                assertThat(documents.get(document).status()).isNotEqualTo(DocumentStatus.FAILED);
                assertThat(Instant.now()).isBefore(until);Thread.sleep(200);
            }
            var task=tasks.create(new TaskRequests.Create(UUID.randomUUID(),base,
                    "只根据 README 文档，简要说明 Spring AI 的目标以及它提到的主要功能。不要推测未列出的功能，保留引用。",List.of(document)));
            worker.submit(task.id());task=idle(task.id());output.put("plan",task.state().plan);
            assertThat(task.status()).isEqualTo(TaskStatus.READY);
            task=tasks.command(task.id(),new TaskRequests.Command(TaskRequests.Operation.START,task.revision(),null));
            worker.submit(task.id());task=idle(task.id());
            output.put("researchStatus",task.status());output.put("answer",task.state().answer);
            output.put("evidence",task.state().evidence);output.put("calls",task.state().calls);
            assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);assertThat(task.state().evidence).isNotEmpty();
            output.put("status","FLOW_PASSED_REQUIRES_HUMAN_REVIEW");
        } catch(Exception|AssertionError e){output.put("status","FAILED");output.put("errorType",e.getClass().getSimpleName());throw e;}
        finally{output.put("finishedAt",Instant.now().toString());Files.writeString(Path.of("target/project-source-live.json"),JsonMapper.builder().build().writeValueAsString(output));}
    }
    private TaskRequests.View idle(UUID id) throws Exception {
        Instant end=Instant.now().plusSeconds(190);
        while(Instant.now().isBefore(end)){var task=tasks.get(id);if(!task.status().active())return task;Thread.sleep(200);}
        throw new AssertionError("Research timeout");
    }
}
