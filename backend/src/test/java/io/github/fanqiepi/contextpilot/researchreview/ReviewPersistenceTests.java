package io.github.fanqiepi.contextpilot.researchreview;

import java.util.*;
import java.util.concurrent.*;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import io.github.fanqiepi.contextpilot.knowledgebase.*;
import io.github.fanqiepi.contextpilot.taskworkspace.*;
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
import static io.github.fanqiepi.contextpilot.researchreview.ReviewData.*;

@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.vectorstore.type=none","contextpilot.task-workspace.enabled=true"})
@Testcontainers
class ReviewPersistenceTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired ReviewService reviews;
    @Autowired ReviewWorker worker;
    @Autowired TaskService tasks;
    @Autowired KnowledgeBaseService bases;
    @Autowired EmbeddingIndexProperties profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.web.context.WebApplicationContext web;
    @MockitoBean ReviewModelGateway model;
    @MockitoBean TaskModelGateway taskModel;

    @AfterEach void stopActive() { reviews.recover(); }

    @Test void concurrentCreationReplaysExactlyOneSnapshotAndRejectsChangedOrDeletedKey() throws Exception {
        var task=task();var request=new Create(UUID.randomUUID(),task.revision());
        try(var pool=Executors.newFixedThreadPool(4)) {
            var results=pool.invokeAll(Collections.<Callable<View>>nCopies(4,()->reviews.create(task.id(),request)));
            var ids=new HashSet<UUID>();for(var result:results) ids.add(result.get().id());assertThat(ids).hasSize(1);
            var review=reviews.get(ids.iterator().next());
            assertThatThrownBy(()->reviews.create(task.id(),new Create(request.clientRequestId(),task.revision()+1))).isInstanceOf(ConflictException.class);
            assertThatThrownBy(()->reviews.create(task.id(),new Create(UUID.randomUUID(),task.revision()))).isInstanceOf(ConflictException.class);
            review=reviews.cancel(review.id(),review.revision());reviews.delete(review.id(),review.revision());
            assertThatThrownBy(()->reviews.create(task.id(),request)).isInstanceOf(ConflictException.class);
        }
    }
    @Test void cancelledAndDeletedAttemptsStillConsumeThreeAttemptBudget() {
        var task=task();
        for(int i=0;i<3;i++) {var r=create(task);r=reviews.cancel(r.id(),r.revision());reviews.delete(r.id(),r.revision());}
        assertThatThrownBy(()->create(task)).isInstanceOf(ConflictException.class).hasMessageContaining("refresh");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM research_review WHERE task_id=?",Integer.class,task.id())).isEqualTo(3);
    }
    @Test void workerPersistsResultAndUsageWithoutChangingOriginalTask() throws Exception {
        var task=task();when(model.call(any())).thenReturn(response());
        var review=create(task);worker.submit(review.id());var result=await(review.id(),Status.COMPLETED);
        assertThat(result.overallVerdict()).isEqualTo(Overall.NO_ISSUES_FOUND);
        assertThat(result.result().claims()).hasSize(1);assertThat(result.usage().totalTokens()).isEqualTo(15);
        assertThat(tasks.get(task.id()).revision()).isEqualTo(task.revision());
        assertThat(reviews.list(task.id(),1,20).getFirst().snapshot()).isNull();
        verify(model,times(1)).call(any());
    }
    @Test void invalidCitationsSkipProviderAndInvalidModelOutputFailsWithUsage() throws Exception {
        var task=task();jdbc.update("UPDATE research_task SET checkpoint=jsonb_set(checkpoint,'{answer}',CAST(? AS jsonb)) WHERE id=?",write("FIFO [E99]"),task.id());
        var review=create(task);worker.submit(review.id());var result=await(review.id(),Status.COMPLETED);
        assertThat(result.overallVerdict()).isEqualTo(Overall.ISSUES_FOUND);assertThat(result.usage()).isNull();verifyNoInteractions(model);
        when(model.call(any())).thenReturn(new ReviewModelGateway.Result("{}",response().call()));
        review=create(task());worker.submit(review.id());result=await(review.id(),Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("REVIEW_RESPONSE_INVALID");assertThat(result.usage().totalTokens()).isEqualTo(15);
    }
    @Test void cancellationAndRevisionChecksFenceLateCompletions() {
        var review=create(task());var token=reviews.internal(review.id()).getExecutionId();reviews.begin(review.id(),token);
        var running=reviews.get(review.id());
        assertThatThrownBy(()->reviews.cancel(running.id(),0)).isInstanceOf(ConflictException.class);
        var cancelled=reviews.cancel(running.id(),running.revision());reviews.complete(running.id(),token,ReviewValidatorTests.result(Verdict.SUPPORTED),response().call());
        assertThat(reviews.get(running.id()).status()).isEqualTo(Status.CANCELLED);
        assertThat(reviews.cancel(running.id(),0).revision()).isEqualTo(cancelled.revision());
    }
    @Test void supplementInvalidatesActiveReviewAndPreservesHistoricalCompletedReview() {
        var task=task();var review=create(task);var token=reviews.internal(review.id()).getExecutionId();
        reviews.begin(review.id(),token);reviews.complete(review.id(),token,ReviewValidatorTests.result(Verdict.SUPPORTED),response().call());
        var pending=create(task);var pendingToken=reviews.internal(pending.id()).getExecutionId();
        tasks.command(task.id(),new TaskRequests.Command(TaskRequests.Operation.SUPPLEMENT,task.revision(),"Explain priority"));
        assertThat(reviews.alive(pending.id(),pendingToken)).isFalse();assertThat(reviews.get(pending.id()).errorCode()).isEqualTo("REVIEW_TASK_CHANGED");
        var historical=reviews.get(review.id());assertThat(historical.freshness()).isEqualTo("HISTORICAL");assertThat(historical.result()).isNotNull();
        var next=tasks.get(task.id());tasks.command(next.id(),new TaskRequests.Command(TaskRequests.Operation.CANCEL,next.revision(),null));
    }
    @Test void sourceChangePreventsCompletionAndHidesStoredEvidenceAndParaphrases() {
        var task=task();var review=create(task);var token=reviews.internal(review.id()).getExecutionId();reviews.begin(review.id(),token);
        jdbc.update("UPDATE source_document SET indexed_at=indexed_at+INTERVAL '1 second' WHERE id=?",task.state().sources.getFirst().id());
        reviews.complete(review.id(),token,ReviewValidatorTests.result(Verdict.SUPPORTED),response().call());
        var result=reviews.get(review.id());assertThat(result.status()).isEqualTo(Status.FAILED);assertThat(result.errorCode()).isEqualTo("REVIEW_SOURCE_CHANGED");
        assertThat(result.snapshot().evidence()).isEmpty();assertThat(result.snapshot().answer()).isNull();assertThat(result.result()).isNull();
        assertThatThrownBy(()->create(task)).isInstanceOf(ConflictException.class);
    }
    @Test void deletedSourcesHideCompletedReviewAndDeletedParentMakesAllReviewEndpointsUnavailable() {
        var task=task();var review=create(task);var token=reviews.internal(review.id()).getExecutionId();reviews.begin(review.id(),token);
        reviews.complete(review.id(),token,ReviewValidatorTests.result(Verdict.SUPPORTED),response().call());
        jdbc.update("UPDATE source_document SET deleted=1 WHERE id=?",task.state().sources.getFirst().id());
        assertThat(reviews.get(review.id()).result()).isNull();assertThat(reviews.get(review.id()).snapshot().evidence()).isEmpty();
        tasks.delete(task.id(),task.revision());
        assertThatThrownBy(()->reviews.get(review.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(()->reviews.list(task.id(),1,20)).isInstanceOf(ResourceNotFoundException.class);
    }
    @Test void ownerAndKnowledgeBaseBoundariesAreEnforced() {
        var task=task();var review=create(task);
        var otherOwner=UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,user_key,display_name,status) VALUES (?,?,'Review fixture','ACTIVE')",otherOwner,"review-"+otherOwner);
        jdbc.update("UPDATE research_task SET owner_id=? WHERE id=?",otherOwner,task.id());
        assertThatThrownBy(()->reviews.get(review.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(()->create(task)).isInstanceOf(ResourceNotFoundException.class);
        var other=task();jdbc.update("UPDATE source_document SET knowledge_base_id=? WHERE id=?",task.knowledgeBaseId(),other.state().sources.getFirst().id());
        assertThatThrownBy(()->create(other)).isInstanceOf(ConflictException.class);
    }
    @Test void recoveryAndQueueAndExecutionDeadlinesNeverReplayOrCompleteLate() {
        var r=create(task());var token=reviews.internal(r.id()).getExecutionId();reviews.recover();
        assertThat(reviews.get(r.id()).errorCode()).isEqualTo("REVIEW_SERVER_RESTARTED");assertThat(reviews.begin(r.id(),token)).isNull();
        var queued=create(task());jdbc.update("UPDATE research_review SET created_at=CURRENT_TIMESTAMP-INTERVAL '181 seconds' WHERE id=?",queued.id());
        assertThat(reviews.get(queued.id()).errorCode()).isEqualTo("REVIEW_QUEUE_TIMEOUT");
        var running=create(task());var runningToken=reviews.internal(running.id()).getExecutionId();reviews.begin(running.id(),runningToken);reviews.modelStarted(running.id(),runningToken);
        jdbc.update("UPDATE research_review SET started_at=CURRENT_TIMESTAMP-INTERVAL '91 seconds' WHERE id=?",running.id());
        reviews.complete(running.id(),runningToken,ReviewValidatorTests.result(Verdict.SUPPORTED),response().call());
        assertThat(reviews.get(running.id()).errorCode()).isEqualTo("REVIEW_TIMEOUT");verifyNoInteractions(model);
    }
    @Test void boundedQueueRejectsAndCancellationDoesNotLetLateProviderOverwrite() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(model.call(any())).thenAnswer(inv->{entered.countDown();release.await(10,TimeUnit.SECONDS);return response();});
        var first=create(task());worker.submit(first.id());assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        var second=create(task());worker.submit(second.id());var third=create(task());worker.submit(third.id());
        var fourth=create(task());worker.submit(fourth.id());assertThat(reviews.get(fourth.id()).errorCode()).isEqualTo("REVIEW_QUEUE_FULL");
        var current=reviews.get(first.id());reviews.cancel(current.id(),current.revision());
        for(var pending:List.of(second,third)) {current=reviews.get(pending.id());reviews.cancel(current.id(),current.revision());}
        release.countDown();await(first.id(),Status.CANCELLED);assertThat(reviews.get(first.id()).result()).isNull();
    }
    @Test void httpValidationAndRoundTrip() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web).build();var task=task();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/research-tasks/"+task.id()+"/reviews")
                .contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/research-reviews/"+UUID.randomUUID()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/research-tasks/"+task.id()+"/reviews?page=0"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        var review=create(task);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/research-reviews/"+review.id()+"/cancel")
                .contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/research-tasks/"+task.id()+"/reviews")
                .contentType("application/json").content("{\"clientRequestId\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/research-reviews/"+review.id()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.snapshot.answer").value("FIFO scheduling [E1]."));
    }
    private View create(TaskRequests.View task) {return reviews.create(task.id(),new Create(UUID.randomUUID(),task.revision()));}
    private ReviewModelGateway.Result response() {return new ReviewModelGateway.Result(write(ReviewValidatorTests.result(Verdict.SUPPORTED)),new TaskState.Call("REVIEW","SUCCEEDED","fixture",10L,5L,15L,10));}
    private View await(UUID id,Status status) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) {var view=reviews.get(id);if(view.status()==status)return view;if(view.status()==Status.FAILED && status!=Status.FAILED) fail(view.errorCode());Thread.sleep(30);}
        throw new AssertionError("Review did not reach "+status);
    }
    private TaskRequests.View task() {
        UUID kb=bases.create(new KnowledgeBaseCreateRequest("review-"+UUID.randomUUID(),null)).id();UUID doc=UUID.randomUUID();var p=profiles.currentProfile();
        jdbc.update("""
            INSERT INTO source_document(id,knowledge_base_id,original_filename,file_type,size_bytes,storage_key,sha256,status,
              embedding_profile_id,embedding_provider,embedding_model,embedding_dimensions,embedding_profile_version,indexed_at)
            VALUES (?,?,'public.md','MARKDOWN',1,?,?,'SUCCEEDED',?,?,?,?,?,CURRENT_TIMESTAMP)
            """,doc,kb,doc.toString(),"b".repeat(64),p.id(),p.provider(),p.model(),p.dimensions(),p.version());
        jdbc.update("INSERT INTO vector_store(id,content,metadata,embedding) VALUES (?,'public synthetic',CAST(? AS json),CAST(? AS vector))",
                UUID.randomUUID(),write(Map.of("document_id",doc.toString(),"knowledge_base_id",kb.toString(),"embedding_profile_id",p.id())),
                "["+String.join(",",Collections.nCopies(1024,"0.1"))+"]");
        var task=tasks.create(new TaskRequests.Create(UUID.randomUUID(),kb,"Explain scheduling",List.of(doc)));
        var token=tasks.internal(task.id()).getExecutionId();
        tasks.checkpoint(task.id(),token,"PUBLIC_FIXTURE",s->{s.answer="FIFO scheduling [E1].";
            s.evidence=List.of(new TaskState.Evidence("E1",doc,"public.md","chunk",null,"FIFO scheduling; no priority."));},TaskStatus.COMPLETED);
        return tasks.get(task.id());
    }
}
