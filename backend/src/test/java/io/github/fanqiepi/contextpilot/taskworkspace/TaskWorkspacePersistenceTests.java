package io.github.fanqiepi.contextpilot.taskworkspace;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import io.github.fanqiepi.contextpilot.knowledgebase.*;
import io.github.fanqiepi.contextpilot.retrieval.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.vectorstore.type=none",
        "contextpilot.task-workspace.enabled=true"})
@Testcontainers
class TaskWorkspacePersistenceTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired TaskService tasks;
    @Autowired TaskMapper mapper;
    @Autowired TaskWorker worker;
    @Autowired TaskSources sources;
    @Autowired KnowledgeBaseService bases;
    @Autowired EmbeddingIndexProperties profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    @MockitoBean TaskModelGateway model;
    @MockitoBean RetrievalService retrieval;

    @Test void httpRejectsMalformedCommandsAndUnknownResources() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/research-tasks")
                .contentType("application/json").content("{\"goal\":\"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/research-tasks/"+UUID.randomUUID()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/research-tasks?page=0"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    @Test void creationIsConcurrentIdempotentAndRejectsDeletedKeyReplay() throws Exception {
        var request=request();
        try(var pool=Executors.newFixedThreadPool(4)) {
            var results=pool.invokeAll(Collections.<Callable<TaskRequests.View>>nCopies(4,()->tasks.create(request)));
            Set<UUID> ids=new HashSet<>(); for(var result:results) ids.add(result.get().id());
            assertThat(ids).hasSize(1);
            var task=tasks.get(ids.iterator().next());
            task=tasks.command(task.id(),new TaskRequests.Command(TaskRequests.Operation.CANCEL,task.revision(),null));
            tasks.delete(task.id(),task.revision());
            assertThatThrownBy(()->tasks.create(request)).isInstanceOf(ConflictException.class);
        }
    }

    @Test void pauseFencesLateResultsAndResumePreservesCompletedSteps() {
        var task=tasks.create(request()); var old=tasks.internal(task.id()).getExecutionId();
        var plan=new TaskState.Plan(List.of(),List.of(new TaskState.Step("Read","SEARCH_DOCUMENTS","query",List.of(task.state().sources.getFirst().id()))));
        tasks.checkpoint(task.id(),old,"PLAN_READY",s->s.plan=plan,TaskStatus.READY);
        task=command(task.id(),TaskRequests.Operation.START,null); var token=tasks.internal(task.id()).getExecutionId();
        tasks.checkpoint(task.id(),token,"STEP_COMPLETED",s->s.completed.add(new TaskState.StepResult(0,1,"COMPLETED")),null);
        var paused=command(task.id(),TaskRequests.Operation.PAUSE,null);
        assertThat(tasks.checkpoint(task.id(),token,"LATE",s->s.answer="late",TaskStatus.COMPLETED)).isFalse();
        var resumed=command(task.id(),TaskRequests.Operation.RESUME,null);
        assertThat(resumed.state().completed).hasSize(1); assertThat(resumed.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(tasks.internal(task.id()).getExecutionId()).isNotEqualTo(token);
        UUID id=task.id();
        assertThatThrownBy(()->tasks.command(id,new TaskRequests.Command(TaskRequests.Operation.CANCEL,paused.revision(),null))).isInstanceOf(ConflictException.class);
        command(id,TaskRequests.Operation.CANCEL,null);
    }

    @Test void supplementArchivesPreviousRoundAndClearsEvidenceAndPlan() {
        var task=tasks.create(request());var token=tasks.internal(task.id()).getExecutionId();
        tasks.checkpoint(task.id(),token,"CLARIFICATION",s->s.plan=new TaskState.Plan(List.of("budget?"),List.of()),TaskStatus.WAITING_INPUT);
        var next=command(task.id(),TaskRequests.Operation.SUPPLEMENT,"Only PostgreSQL");
        assertThat(next.state().round).isEqualTo(2);assertThat(next.state().supplements).containsExactly("Only PostgreSQL");
        assertThat(next.state().plan).isNull();assertThat(next.state().evidence).isEmpty();
        assertThat(mapper.events(task.id()).stream().anyMatch(e->e.checkpoint().contains("budget?"))).isTrue();
        command(task.id(),TaskRequests.Operation.CANCEL,null);
    }

    @Test void changedSourcesBlockResumeAndSupplementRefreshesSnapshot() {
        var task=tasks.create(request());command(task.id(),TaskRequests.Operation.PAUSE,null);
        jdbc.update("UPDATE source_document SET indexed_at=indexed_at+INTERVAL '1 second' WHERE id=?",task.state().sources.getFirst().id());
        assertThatThrownBy(()->command(task.id(),TaskRequests.Operation.RESUME,null)).isInstanceOf(ConflictException.class);
        var next=command(task.id(),TaskRequests.Operation.SUPPLEMENT,"Use the updated source");
        assertThat(next.state().sources).isNotEqualTo(task.state().sources);
        command(task.id(),TaskRequests.Operation.CANCEL,null);
    }

    @Test void recoveryRequiresUserResumeAndDeadlineCannotComplete() {
        var task=tasks.create(request());var old=tasks.internal(task.id()).getExecutionId();
        tasks.recover();assertThat(tasks.get(task.id()).status()).isEqualTo(TaskStatus.PAUSED);
        assertThat(tasks.checkpoint(task.id(),old,"LATE",s->{},TaskStatus.READY)).isFalse();
        command(task.id(),TaskRequests.Operation.RESUME,null);var token=tasks.internal(task.id()).getExecutionId();
        jdbc.update("UPDATE research_task SET checkpoint=jsonb_set(checkpoint,'{deadline}',CAST(? AS jsonb)) WHERE id=?",
                TaskJson.write(java.time.OffsetDateTime.now().minusSeconds(1).toString()),task.id());
        assertThat(tasks.checkpoint(task.id(),token,"LATE",s->{},TaskStatus.READY)).isFalse();
        assertThat(tasks.get(task.id()).status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test void workerExecutesPlanAndGroundedSynthesisWithPersistedUsage() throws Exception {
        var request=request();var doc=request.documentIds().getFirst();
        when(model.call(eq("PLANNING"),any())).thenReturn(result("PLANNING",TaskJson.write(new TaskState.Plan(List.of(),List.of(new TaskState.Step("Read deployment","SEARCH_DOCUMENTS","deployment",List.of(doc)))))));
        when(model.call(eq("SYNTHESIS"),any())).thenReturn(result("SYNTHESIS","{\"answer\":\"Deployment uses PostgreSQL [E1].\"}"));
        when(retrieval.searchDocument(eq(request.knowledgeBaseId()),eq(doc),anyString(),eq(3),anyString()))
                .thenReturn(List.of(new RetrievalResultResponse("chunk",doc,"public.md",0,null,"Deployment uses PostgreSQL.",0.9)));
        var task=tasks.create(request);worker.submit(task.id());await(task.id(),TaskStatus.READY);
        command(task.id(),TaskRequests.Operation.START,null);worker.submit(task.id());var completed=await(task.id(),TaskStatus.COMPLETED);
        assertThat(completed.state().answer).contains("[E1]");assertThat(completed.state().completed).hasSize(1);
        assertThat(completed.state().calls).hasSize(2).allMatch(c->c.status().equals("SUCCEEDED"));
        assertThat(tasks.events(task.id()).get("truncated")).isEqualTo(false);
    }

    @Test void emptyRetrievalDoesNotCallSynthesisAndForeignPlanNeverRetrieves() throws Exception {
        var request=request();var doc=request.documentIds().getFirst();
        when(model.call(eq("PLANNING"),any())).thenReturn(result("PLANNING",TaskJson.write(new TaskState.Plan(List.of(),List.of(new TaskState.Step("Read","SEARCH_DOCUMENTS","empty",List.of(doc)))))));
        when(retrieval.searchDocument(any(),any(),anyString(),anyInt(),anyString())).thenReturn(List.of());
        var task=tasks.create(request);worker.submit(task.id());await(task.id(),TaskStatus.READY);
        command(task.id(),TaskRequests.Operation.START,null);worker.submit(task.id());await(task.id(),TaskStatus.INSUFFICIENT_EVIDENCE);
        verify(model,never()).call(eq("SYNTHESIS"),any());
        reset(model,retrieval);
        when(model.call(anyString(),any())).thenReturn(result("PLANNING",TaskJson.write(new TaskState.Plan(List.of(),List.of(new TaskState.Step("Escape","SEARCH_DOCUMENTS","secret",List.of(UUID.randomUUID())))))));
        var bad=tasks.create(request());worker.submit(bad.id());await(bad.id(),TaskStatus.FAILED);verifyNoInteractions(retrieval);
    }

    @Test void pausingLiveWorkerThenResumingDoesNotRepeatCompletedRetrieval() throws Exception {
        var request=request();var doc=request.documentIds().getFirst();
        when(model.call(eq("PLANNING"),any())).thenReturn(result("PLANNING",TaskJson.write(new TaskState.Plan(List.of(),List.of(new TaskState.Step("Read","SEARCH_DOCUMENTS","deployment",List.of(doc)))))));
        when(retrieval.searchDocument(any(),any(),anyString(),anyInt(),anyString())).thenReturn(List.of(new RetrievalResultResponse("chunk",doc,"public.md",0,null,"PostgreSQL",0.9)));
        var entered=new CountDownLatch(1);var calls=new AtomicInteger();
        when(model.call(eq("SYNTHESIS"),any())).thenAnswer(invocation->{
            if(calls.incrementAndGet()==1) { entered.countDown();new CountDownLatch(1).await(5,TimeUnit.SECONDS); }
            return result("SYNTHESIS","{\"answer\":\"PostgreSQL [E1]\"}");
        });
        var task=tasks.create(request);worker.submit(task.id());await(task.id(),TaskStatus.READY);
        command(task.id(),TaskRequests.Operation.START,null);worker.submit(task.id());
        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        var paused=command(task.id(),TaskRequests.Operation.PAUSE,null);
        assertThat(paused.state().completed).hasSize(1);
        command(task.id(),TaskRequests.Operation.RESUME,null);worker.submit(task.id());
        var completed=await(task.id(),TaskStatus.COMPLETED);
        assertThat(completed.state().calls).anyMatch(c->c.status().equals("INTERRUPTED"));
        verify(retrieval,times(1)).searchDocument(any(),any(),anyString(),anyInt(),anyString());
    }

    private TaskModelGateway.Result result(String phase,String text) {
        return new TaskModelGateway.Result(text,new TaskState.Call(phase,"SUCCEEDED","fixture",10L,5L,15L,10));
    }
    private TaskRequests.View command(UUID id,TaskRequests.Operation op,String text) {
        return tasks.command(id,new TaskRequests.Command(op,tasks.get(id).revision(),text));
    }
    private TaskRequests.View await(UUID id,TaskStatus status) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) {
            var task=tasks.get(id);if(task.status()==status) return task;
            if(task.status()==TaskStatus.FAILED && status!=TaskStatus.FAILED) fail("Task failed: "+task.state().errorCode);
            Thread.sleep(30);
        }
        throw new AssertionError("Task did not reach "+status+": "+tasks.get(id).status());
    }
    private TaskRequests.Create request() {
        UUID kb=bases.create(new KnowledgeBaseCreateRequest("task-"+UUID.randomUUID(),null)).id();
        UUID doc=UUID.randomUUID();var p=profiles.currentProfile();
        jdbc.update("""
            INSERT INTO source_document(id,knowledge_base_id,original_filename,file_type,size_bytes,storage_key,sha256,status,
              embedding_profile_id,embedding_provider,embedding_model,embedding_dimensions,embedding_profile_version,indexed_at)
            VALUES (?,?,'public.md','MARKDOWN',1,?,?,'SUCCEEDED',?,?,?,?,?,CURRENT_TIMESTAMP)
            """,doc,kb,doc.toString(),"b".repeat(64),p.id(),p.provider(),p.model(),p.dimensions(),p.version());
        jdbc.update("INSERT INTO vector_store(id,content,metadata,embedding) VALUES (?,'public synthetic',CAST(? AS json),CAST(? AS vector))",
                UUID.randomUUID(),TaskJson.write(Map.of("document_id",doc.toString(),"knowledge_base_id",kb.toString(),"embedding_profile_id",p.id())),
                "["+String.join(",",Collections.nCopies(1024,"0.1"))+"]");
        return new TaskRequests.Create(UUID.randomUUID(),kb,"Compare deployment options",List.of(doc));
    }
}
