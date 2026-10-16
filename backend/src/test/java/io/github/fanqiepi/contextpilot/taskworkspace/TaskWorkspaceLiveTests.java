package io.github.fanqiepi.contextpilot.taskworkspace;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import io.github.fanqiepi.contextpilot.document.*;
import io.github.fanqiepi.contextpilot.knowledgebase.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

/** Public synthetic corpus, opt-in provider calls; no claim of independent semantic acceptance. */
@SpringBootTest(properties={"spring.ai.model.chat=deepseek","spring.ai.model.embedding=openai",
        "spring.ai.vectorstore.type=pgvector","contextpilot.task-workspace.enabled=true",
        "contextpilot.document.processing.enabled=true","contextpilot.storage.root=target/task-live-uploads"})
@ActiveProfiles("local-secrets")
@Testcontainers
@EnabledIfSystemProperty(named="contextpilot.evaluation.task-live",matches="true")
class TaskWorkspaceLiveTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired TaskService tasks;
    @Autowired TaskWorker worker;
    @Autowired KnowledgeBaseService bases;
    @Autowired DocumentService documents;

    @Test @Timeout(480) void planExecuteSupplementAndContinueAgainstRealProviders() throws Exception {
        var output=new LinkedHashMap<String,Object>();output.put("mode","PUBLIC_DEBUG_NOT_FINAL_ACCEPTANCE");
        output.put("startedAt",OffsetDateTime.now().toString());
        try {
            var kb=bases.create(new KnowledgeBaseCreateRequest("Task public debug",null)).id();
            var source=Files.readAllBytes(Path.of("../evals/corpora/v6-upgrade-debug-v1/legacy-worker.md"));
            var doc=documents.upload(kb,new MockMultipartFile("file","legacy-worker.md","text/markdown",source)).id();
            long deadline=System.nanoTime()+Duration.ofSeconds(80).toNanos();
            while(documents.list(kb).stream().anyMatch(d->d.status()!=DocumentStatus.SUCCEEDED)) {
                assertThat(System.nanoTime()).isLessThan(deadline);Thread.sleep(200);
            }
            var task=tasks.create(new TaskRequests.Create(UUID.randomUUID(),kb,
                    "只根据所选文档的 scheduling 段落，说明旧工作进程的调度方式和是否支持任务优先级。无需选型或迁移建议。",List.of(doc)));
            worker.submit(task.id());task=waitIdle(task.id());output.put("initialPlan",task);
            assertThat(task.status()).isEqualTo(TaskStatus.READY);
            task=tasks.command(task.id(),new TaskRequests.Command(TaskRequests.Operation.START,task.revision(),null));
            worker.submit(task.id());task=waitIdle(task.id());output.put("firstResult",task);
            assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(task.state().evidence).isNotEmpty();
            task=tasks.command(task.id(),new TaskRequests.Command(TaskRequests.Operation.SUPPLEMENT,task.revision(),
                    "保持相同研究范围，只用一句话回答调度方式和优先级支持情况，并保留引用。"));
            worker.submit(task.id());task=waitIdle(task.id());assertThat(task.status()).isEqualTo(TaskStatus.READY);
            task=tasks.command(task.id(),new TaskRequests.Command(TaskRequests.Operation.START,task.revision(),null));
            worker.submit(task.id());task=waitIdle(task.id());output.put("continuedResult",task);
            assertThat(task.status()).isEqualTo(TaskStatus.COMPLETED);assertThat(task.state().round).isEqualTo(2);
            output.put("status","ENGINEERING_PASSED_REQUIRES_ANSWER_REVIEW");
        } catch(Exception|AssertionError error) {output.put("status","FAILED");output.put("errorType",error.getClass().getSimpleName());throw error;}
        finally {output.put("finishedAt",OffsetDateTime.now().toString());Files.writeString(Path.of("target/task-workspace-live.json"),TaskJson.write(output));}
    }
    private TaskRequests.View waitIdle(UUID id) throws Exception {
        long until=System.nanoTime()+Duration.ofSeconds(190).toNanos();
        while(System.nanoTime()<until) {var task=tasks.get(id);if(!task.status().active())return task;Thread.sleep(200);}
        throw new AssertionError("Task deadline exceeded");
    }
}
