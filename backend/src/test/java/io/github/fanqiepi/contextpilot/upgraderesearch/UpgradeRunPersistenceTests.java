package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none",
        "spring.ai.vectorstore.type=none","contextpilot.upgrade-research.enabled=false"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class UpgradeRunPersistenceTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17-bookworm").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("context_pilot").withUsername("context_pilot").withPassword("context_pilot_test");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired UpgradeRunStateService state;
    @Autowired UpgradeRunMapper mapper;
    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired EmbeddingIndexProperties profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UpgradeExecutionState executionState;
    @Autowired UpgradeExecutionMapper executionMapper;
    @Autowired UpgradeQueryService query;
    @Autowired UpgradeResearchController controller;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired io.github.fanqiepi.contextpilot.researchreport.ResearchReportProposalService proposals;
    @Autowired io.github.fanqiepi.contextpilot.researchreport.ResearchReportMapper reports;
    @Autowired io.github.fanqiepi.contextpilot.action.ActionRequestService actions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    io.github.fanqiepi.contextpilot.action.ActionRequestMapper actionMapper;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"V6-020","V6-021","V6-022","V6-023","V6-024"})
    void frozenAdversarialToolRequestsNeverEscapeScopeOrSave(String caseId) throws Exception {
        var run=state.register(request(),caseId).run();
        var model=org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var retrieval=org.mockito.Mockito.mock(io.github.fanqiepi.contextpilot.retrieval.RetrievalService.class);
        var releases=org.mockito.Mockito.mock(ReleaseNotesClient.class);
        var round=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenAnswer(invocation->{
                int ordinal=round.incrementAndGet();String name;String arguments;
                switch(caseId){
                    case "V6-020"->{name="search_selected_documents";arguments=UpgradeJson.write(Map.of("query","escape","documentIds",List.of(UUID.randomUUID())));}
                    case "V6-021"->{name="read_evidence";arguments="{\"evidenceId\":\"L99\"}";}
                    case "V6-022"->{name="get_release_notes";arguments="{\"tag\":\"../keys\"}";}
                    case "V6-023"->{name="confirm_report";arguments="{}";}
                    default->{name="get_release_notes";arguments="{\"tag\":\"v2.0.0\"}";}
                }
                var calls=new ArrayList<org.springframework.ai.chat.messages.AssistantMessage.ToolCall>();
                for(int i=0;i<(caseId.equals("V6-024")?3:1);i++)calls.add(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(ordinal+"-"+i,"function",name,arguments));
                var message=org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(calls).build();
                return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(message)));
            });
        if(caseId.equals("V6-024"))org.mockito.Mockito.when(releases.read(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
            .thenThrow(new UpgradeToolException("RELEASE_NOT_FOUND"));
        var worker=new UpgradeResearchExecutor(state,executionState,new UpgradeResearchModelGateway(model),retrieval,releases,
            new org.springframework.core.io.ClassPathResource("prompts/upgrade-research-v1.txt"),0.5);
        try{worker.execute(run.getId());}finally{worker.shutdown();}
        var finalRun=state.get(run.getId());
        assertThat(finalRun.getStatus()).isEqualTo(UpgradeRunStatus.FAILED);
        assertThat(finalRun.getDraft()).isNull();assertThat(reports.byRun(run.getId(),run.getOwnerId())).isNull();
        org.mockito.Mockito.verifyNoInteractions(retrieval);
        if(caseId.equals("V6-024")){
            assertThat(finalRun.getErrorCode()).isEqualTo("TOOL_BUDGET_EXCEEDED");
            assertThat(finalRun.getToolRequests()).isEqualTo(6);assertThat(finalRun.getHttpCalls()).isOne();
            org.mockito.Mockito.verify(releases,org.mockito.Mockito.times(1)).read(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        }else{assertThat(finalRun.getHttpCalls()).isZero();org.mockito.Mockito.verifyNoInteractions(releases);}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void nativeToolLoopPersistsEvidenceAndAuditsEveryCall(boolean recoverEmptySearch) throws Exception {
        var request=request();var run=state.register(request,"runtime-trace").run();UUID doc=request.documentIds().getFirst();
        var chatModel=org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var retrieval=org.mockito.Mockito.mock(io.github.fanqiepi.contextpilot.retrieval.RetrievalService.class);
        var releases=org.mockito.Mockito.mock(ReleaseNotesClient.class);
        var toolMessage=org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
            new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("c1","function","search_selected_documents",
                    UpgradeJson.write(Map.of("query","upgrade","documentIds",List.of(doc.toString())))),
            new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("c2","function","get_release_notes","{\"tag\":\"v2.0.0\"}"))).build();
        var finalMessage=org.springframework.ai.chat.messages.AssistantMessage.builder()
                .content("{\"answer\":\"旧方案采用自动工具执行 [L1]。新版应由应用控制工具 [R1]。迁移后验证调用 ID；此步骤待验证。\",\"missingEvidence\":[]}").build();
        var readMessage=org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
            new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("c3","function",
                recoverEmptySearch?"search_selected_documents":"read_evidence",recoverEmptySearch?
                UpgradeJson.write(Map.of("query","tool execution","documentIds",List.of(doc.toString()))):"{\"evidenceId\":\"L1\"}"))).build();
        org.mockito.Mockito.when(chatModel.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(toolMessage))),
                    new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(readMessage))),
                    new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(finalMessage))));
        var hits=List.of(new io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse("chunk-1",doc,"legacy.md",0,null,"旧方案采用自动工具执行",0.9));
        org.mockito.Mockito.when(retrieval.searchDocument(org.mockito.ArgumentMatchers.eq(request.knowledgeBaseId()),org.mockito.ArgumentMatchers.eq(doc),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(3),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(recoverEmptySearch?List.of():hits,hits);
        org.mockito.Mockito.when(releases.read(org.mockito.ArgumentMatchers.eq("v2.0.0"),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ReleaseNotesClient.Release(42,"v2.0.0","synthetic","新版应由应用控制工具",false,false,
                    "https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0","2026-01-01T00:00:00Z","2026-01-02T00:00:00Z","b".repeat(64)));
        var worker=new UpgradeResearchExecutor(state,executionState,new UpgradeResearchModelGateway(chatModel),retrieval,releases,
                new org.springframework.core.io.ClassPathResource("prompts/upgrade-research-v1.txt"),0.5);
        try {worker.execute(run.getId());}finally{worker.shutdown();}
        var completed=state.get(run.getId());
        assertThat(completed.getStatus()).isEqualTo(UpgradeRunStatus.SUCCEEDED);
        assertThat(completed.getModelCalls()).isEqualTo(3);assertThat(completed.getToolRequests()).isEqualTo(3);
        assertThat(completed.getRetrievalCalls()).isEqualTo(recoverEmptySearch?2:1);assertThat(completed.getHttpCalls()).isOne();
        assertThat(executionMapper.sources(run.getId())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upgrade_research_model_call WHERE run_id=? AND total_tokens IS NULL",Integer.class,run.getId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upgrade_research_tool_call WHERE run_id=? AND status='SUCCEEDED'",Integer.class,run.getId())).isEqualTo(recoverEmptySearch?2:3);
        var requestId=UUID.randomUUID();
        var saveRequest=new io.github.fanqiepi.contextpilot.researchreport.ReportSaveRequest(requestId,"升级研究报告",null);
        var proposal=proposals.propose(run.getId(),saveRequest);
        assertThat(proposal.actionType()).isEqualTo(io.github.fanqiepi.contextpilot.action.ActionType.SAVE_RESEARCH_REPORT);
        assertThat(proposal.status()).isEqualTo(io.github.fanqiepi.contextpilot.action.ActionRequestStatus.PENDING_CONFIRMATION);
        assertThat(reports.byRun(run.getId(),run.getOwnerId())).isNull();
        int messageCount=jdbc.queryForObject("SELECT count(*) FROM chat_message",Integer.class);
        assertThat(proposals.propose(run.getId(),saveRequest).id()).isEqualTo(proposal.id());
        assertThat(proposals.propose(run.getId(),new io.github.fanqiepi.contextpilot.researchreport.ReportSaveRequest(
                UUID.randomUUID(),saveRequest.title(),null)).id()).isEqualTo(proposal.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message",Integer.class)).isEqualTo(messageCount);
        assertThat(query.get(run.getId()).saveActionId()).isEqualTo(proposal.id());
        var confirmers=Executors.newFixedThreadPool(4);
        try {
            List<Callable<UUID>> confirmations=new ArrayList<>();
            for(int i=0;i<4;i++)confirmations.add(()->actions.confirm(proposal.id()).resultResourceId());
            Set<UUID> resultIds=new HashSet<>();for(var result:confirmers.invokeAll(confirmations))resultIds.add(result.get());
            assertThat(resultIds).hasSize(1).doesNotContainNull();
        }finally{confirmers.shutdownNow();}
        var saved=reports.byRun(run.getId(),run.getOwnerId());assertThat(saved).isNotNull();
        assertThat(saved.body()).isEqualTo(completed.getDraft());
        reports.delete(saved.id(),run.getOwnerId());
        assertThat(actions.confirm(proposal.id()).resultResourceId()).isEqualTo(saved.id());
        assertThat(reports.byRun(run.getId(),run.getOwnerId()).deleted()).isOne();
        assertThatThrownBy(()->proposals.propose(run.getId(),new io.github.fanqiepi.contextpilot.researchreport.ReportSaveRequest(UUID.randomUUID(),"new",null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test void reportAndExecutionClaimRollbackWhenSuccessMarkFails() {
        var run=readyRun();
        var proposal=proposals.propose(run.getId(),new io.github.fanqiepi.contextpilot.researchreport.ReportSaveRequest(UUID.randomUUID(),"Rollback test",null));
        org.mockito.Mockito.doThrow(new IllegalStateException("injected after report INSERT"))
            .when(actionMapper).completeSuccess(org.mockito.ArgumentMatchers.eq(proposal.id()),org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        try {
            assertThatThrownBy(()->actions.confirm(proposal.id())).isInstanceOf(IllegalStateException.class);
            assertThat(reports.byRun(run.getId(),run.getOwnerId())).isNull();
            assertThat(actions.get(proposal.id()).status()).isEqualTo(io.github.fanqiepi.contextpilot.action.ActionRequestStatus.PENDING_CONFIRMATION);
        }finally{org.mockito.Mockito.reset(actionMapper);}
        assertThat(actions.confirm(proposal.id()).status()).isEqualTo(io.github.fanqiepi.contextpilot.action.ActionRequestStatus.SUCCEEDED);
    }

    @Test void rejectionExpiryTitleConflictAndSourceChangeNeverSaveReports() {
        var run=readyRun();
        var proposal=proposals.propose(run.getId(),new io.github.fanqiepi.contextpilot.researchreport.ReportSaveRequest(UUID.randomUUID(),"first",null));
        assertThatThrownBy(()->proposals.propose(run.getId(),new io.github.fanqiepi.contextpilot.researchreport.ReportSaveRequest(UUID.randomUUID(),"different",null)))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("REPORT_PROPOSAL_CONFLICT");
        actions.reject(proposal.id());actions.confirm(proposal.id());assertThat(reports.byRun(run.getId(),run.getOwnerId())).isNull();
        var expiring=proposals.propose(run.getId(),new io.github.fanqiepi.contextpilot.researchreport.ReportSaveRequest(UUID.randomUUID(),"second",null));
        jdbc.update("UPDATE action_request SET created_at=CURRENT_TIMESTAMP-INTERVAL '2 hours',expires_at=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE id=?",expiring.id());
        assertThat(actions.confirm(expiring.id()).status()).isEqualTo(io.github.fanqiepi.contextpilot.action.ActionRequestStatus.EXPIRED);
        var stale=proposals.propose(run.getId(),new io.github.fanqiepi.contextpilot.researchreport.ReportSaveRequest(UUID.randomUUID(),"third",null));
        jdbc.update("UPDATE source_document SET indexed_at=indexed_at+INTERVAL '1 second' WHERE knowledge_base_id=?",run.getKnowledgeBaseId());
        assertThat(actions.confirm(stale.id()).status()).isEqualTo(io.github.fanqiepi.contextpilot.action.ActionRequestStatus.FAILED);
        assertThat(reports.byRun(run.getId(),run.getOwnerId())).isNull();
    }

    private UpgradeRunEntity readyRun(){
        var run=state.register(request(),"report-test").run();state.claim(run.getId());
        // Test fixture for the report transaction boundary, not semantic quality evidence.
        executionState.finish(run.getId(),UpgradeRunStatus.SUCCEEDED,"Public synthetic draft",List.of());
        return state.get(run.getId());
    }

    @Test void simultaneousStartRequestsDispatchOnlyOnce() throws Exception {
        var subjects=org.mockito.Mockito.mock(io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider.class);
        org.mockito.Mockito.when(subjects.currentSubject()).thenReturn(new io.github.fanqiepi.contextpilot.identity.UserSubject(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),"LOCAL_OWNER"));
        var dispatched=new CountDownLatch(1);var count=new java.util.concurrent.atomic.AtomicInteger();
        var start=new UpgradeResearchStartService(mapper,state,subjects,id->{count.incrementAndGet();dispatched.countDown();},transactions);
        var pool=Executors.newFixedThreadPool(4);var barrier=new CyclicBarrier(4);var request=request();
        try {
            List<Callable<UUID>> calls=new ArrayList<>();
            for(int i=0;i<4;i++)calls.add(()->{barrier.await(5,TimeUnit.SECONDS);return start.start(request,"start-race").run().getId();});
            Set<UUID> ids=new HashSet<>();for(var result:pool.invokeAll(calls))ids.add(result.get(10,TimeUnit.SECONDS));
            assertThat(dispatched.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(ids).hasSize(1);assertThat(count).hasValue(1);
        }finally{pool.shutdownNow();start.shutdown();}
    }

    @Test void cancellationRacesWithRegistrationWithoutLeavingActiveCalls() throws Exception {
        var run=state.register(request(),"registration-race").run();state.claim(run.getId());
        var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
        try {
            var registration=pool.submit(()->{
                barrier.await(5,TimeUnit.SECONDS);
                try{return executionState.beginModel(run.getId());}
                catch(UpgradeToolException error){assertThat(error.code()).isEqualTo("RUN_NOT_ACTIVE");return null;}
            });
            var cancellation=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return state.cancel(run.getId());});
            cancellation.get(10,TimeUnit.SECONDS);var call=registration.get(10,TimeUnit.SECONDS);
            assertThat(state.get(run.getId()).getStatus()).isEqualTo(UpgradeRunStatus.CANCELLED);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM upgrade_research_model_call WHERE run_id=? AND status<>'CANCELLED'",Integer.class,run.getId())).isZero();
            assertThatThrownBy(()->executionState.beginModel(run.getId())).extracting("code").isEqualTo("RUN_NOT_ACTIVE");
            assertThat(state.get(run.getId()).getModelCalls()).isEqualTo(call==null?0:1);
        }finally{pool.shutdownNow();}
    }

    @Test @org.junit.jupiter.api.Timeout(20)
    void activeStreamCanDisconnectAndReconnectToSameRunWithoutRevisionRollback() throws Exception {
        var run=state.register(request(),"reconnect-trace").run();state.claim(run.getId());
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                "http://127.0.0.1:"+port+"/api/upgrade-research/runs/"+run.getId()+"/events"))
                .timeout(java.time.Duration.ofSeconds(10)).GET().build();
        try(var client=java.net.http.HttpClient.newHttpClient()){
            var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            long revision=-1;
            try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),java.nio.charset.StandardCharsets.UTF_8))){
                String line;
                while((line=reader.readLine())!=null){
                    if(line.startsWith("data:")){
                        var snapshot=UpgradeJson.MAPPER.readTree(line.substring(5)).path("run");
                        assertThat(snapshot.path("id").asText()).isEqualTo(run.getId().toString());
                        assertThat(snapshot.path("status").asText()).isEqualTo("RUNNING");
                        revision=snapshot.path("revision").asLong();break;
                    }
                }
            }
            assertThat(revision).isGreaterThanOrEqualTo(1);
            assertThat(state.get(run.getId()).getStatus()).isEqualTo(UpgradeRunStatus.RUNNING);
            executionState.finish(run.getId(),UpgradeRunStatus.SUCCEEDED,"public synthetic reconnect result",List.of());
            var replay=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(replay.body()).contains("event:snapshot","event:result","event:done","reconnect-trace");
            for(String line:replay.body().split("\\n"))if(line.startsWith("data:")){
                var payload=UpgradeJson.MAPPER.readTree(line.substring(5));
                var snapshot=payload.has("run")?payload.path("run"):payload;
                assertThat(snapshot.path("revision").asLong()).isGreaterThanOrEqualTo(revision);
            }
            assertThat(state.get(run.getId()).getStatus()).isEqualTo(UpgradeRunStatus.SUCCEEDED);
        }finally{controller.shutdown();}
    }

    @Test void deletingSourceDuringRunRejectsCompletionAndReportProposal() {
        var request=request();var run=state.register(request,"source-deletion").run();state.claim(run.getId());
        jdbc.update("UPDATE source_document SET deleted=1 WHERE id=?",request.documentIds().getFirst());
        assertThatThrownBy(()->executionState.finish(run.getId(),UpgradeRunStatus.SUCCEEDED,"stale",List.of()))
                .extracting("code").isEqualTo("SOURCE_CHANGED");
        state.fail(run.getId(),"SOURCE_CHANGED");
        assertThat(state.get(run.getId()).getDraft()).isNull();
        assertThatThrownBy(()->proposals.propose(run.getId(),new io.github.fanqiepi.contextpilot.researchreport.ReportSaveRequest(
                UUID.randomUUID(),"stale",null))).extracting("code").isEqualTo("REPORT_DRAFT_UNAVAILABLE");
        assertThat(reports.byRun(run.getId(),run.getOwnerId())).isNull();
    }

    @Test void committedRunFailsWhenDispatchIsRejected() {
        var subjects=org.mockito.Mockito.mock(io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider.class);
        org.mockito.Mockito.when(subjects.currentSubject()).thenReturn(new io.github.fanqiepi.contextpilot.identity.UserSubject(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),"LOCAL_OWNER"));
        var worker=org.mockito.Mockito.mock(UpgradeRunWorker.class);
        var start=new UpgradeResearchStartService(mapper,state,subjects,worker,transactions);
        start.shutdown();
        var request=request();
        var result=start.start(request,"dispatch-rejected");
        assertThat(result.run().getStatus()).isEqualTo(UpgradeRunStatus.FAILED);
        assertThat(result.run().getErrorCode()).isEqualTo("DISPATCH_FAILED");
        assertThat(start.start(request,"replay").run().getId()).isEqualTo(result.run().getId());
        org.mockito.Mockito.verifyNoInteractions(worker);
    }

    @Test void concurrentCompletionAndCancellationHaveOneIrreversibleTerminal() throws Exception {
        var run=state.register(request(),"completion-race").run();state.claim(run.getId());
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            var completion=pool.submit(()->{
                barrier.await(5,TimeUnit.SECONDS);
                try {executionState.finish(run.getId(),UpgradeRunStatus.SUCCEEDED,"synthetic",List.of());return true;}
                catch(UpgradeToolException error){assertThat(error.code()).isEqualTo("RUN_NOT_ACTIVE");return false;}
            });
            var cancellation=pool.submit(()->{
                barrier.await(5,TimeUnit.SECONDS);
                try {state.cancel(run.getId());return true;}
                catch(ConflictException error){assertThat(error.getCode()).isEqualTo("UPGRADE_RUN_NOT_CANCELLABLE");return false;}
            });
            assertThat(completion.get(10,TimeUnit.SECONDS) ^ cancellation.get(10,TimeUnit.SECONDS)).isTrue();
            var terminal=state.get(run.getId());
            assertThat(terminal.getStatus()).isIn(UpgradeRunStatus.SUCCEEDED,UpgradeRunStatus.CANCELLED);
            state.fail(run.getId(),"LATE_FAILURE");
            assertThat(state.get(run.getId()).getRevision()).isEqualTo(terminal.getRevision());
            assertThat(state.claim(run.getId())).isFalse();
        } finally {pool.shutdownNow();}
    }

    @Test void evidenceCharacterLimitRejectsInsertionAtomically() {
        var request=request();var run=state.register(request,"ledger-budget").run();state.claim(run.getId());
        var evidence=new UpgradeEvidence(UUID.randomUUID(),run.getId(),"L1","LOCAL",request.documentIds().getFirst(),
                "chunk-budget",profiles.currentProfile().id(),"a".repeat(64),null,null,null,null,
                UpgradeRunStateService.now(),"x".repeat(UpgradeBudget.EVIDENCE_CHARACTERS+1),false,false);
        assertThatThrownBy(()->executionState.addEvidence(evidence)).extracting("code").isEqualTo("EVIDENCE_BUDGET_EXCEEDED");
        assertThat(executionMapper.sources(run.getId())).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @org.junit.jupiter.api.Timeout(80)
    void expiredRunRejectsLateUninterruptibleModelResult(boolean totalDeadline) throws Exception {
        var run=state.register(request(),"late-provider").run();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var returned=new CountDownLatch(1);
        var model=org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenAnswer(call->{
                entered.countDown();
                while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){/* Simulated remote ignores cancellation. */}}
                returned.countDown();
                return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("{}"))));
            });
        var retrieval=org.mockito.Mockito.mock(io.github.fanqiepi.contextpilot.retrieval.RetrievalService.class);
        var releases=org.mockito.Mockito.mock(ReleaseNotesClient.class);
        var worker=new UpgradeResearchExecutor(state,executionState,new UpgradeResearchModelGateway(model),retrieval,releases,
                new org.springframework.core.io.ClassPathResource("prompts/upgrade-research-v1.txt"),0.5);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->worker.execute(run.getId()));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            if(totalDeadline)jdbc.update("UPDATE upgrade_research_run SET created_at=CURRENT_TIMESTAMP-INTERVAL '181 seconds',deadline=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=?",run.getId());
            future.get(totalDeadline?5:70,TimeUnit.SECONDS);
            var expired=state.get(run.getId());
            assertThat(expired.getStatus()).isEqualTo(UpgradeRunStatus.FAILED);
            assertThat(expired.getErrorCode()).isEqualTo(totalDeadline?"DEADLINE_EXCEEDED":"CALL_TIMEOUT");
            if(!totalDeadline)assertThat(expired.getDeadline()).isAfter(UpgradeRunStateService.now());
            release.countDown();assertThat(returned.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(state.get(run.getId()).getRevision()).isEqualTo(expired.getRevision());
            assertThat(state.get(run.getId()).getDraft()).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM upgrade_research_model_call WHERE run_id=?",String.class,run.getId())).isEqualTo("FAILED");
            org.mockito.Mockito.verifyNoInteractions(retrieval,releases);
        }finally{release.countDown();worker.shutdown();pool.shutdownNow();}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void duplicateCallIdAndFourthRoundToolsStopWithoutExtraReads(boolean fourthRound) throws Exception {
        var run=state.register(request(),"protocol-boundary").run();
        var model=org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class);
        var retrieval=org.mockito.Mockito.mock(io.github.fanqiepi.contextpilot.retrieval.RetrievalService.class);
        var releases=org.mockito.Mockito.mock(ReleaseNotesClient.class);
        var rounds=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
            .thenAnswer(call->{
                int round=rounds.incrementAndGet();
                var message=org.springframework.ai.chat.messages.AssistantMessage.builder().content("").toolCalls(List.of(
                    new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(fourthRound?"call-"+round:"duplicate",
                        "function","get_release_notes","{\"tag\":\"v2.0.0\"}"))).build();
                return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(message)));
            });
        org.mockito.Mockito.when(releases.read(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()))
            .thenThrow(new UpgradeToolException("RELEASE_NOT_FOUND"));
        var worker=new UpgradeResearchExecutor(state,executionState,new UpgradeResearchModelGateway(model),retrieval,releases,
                new org.springframework.core.io.ClassPathResource("prompts/upgrade-research-v1.txt"),0.5);
        try {worker.execute(run.getId());}finally{worker.shutdown();}
        assertThat(state.get(run.getId()).getErrorCode()).isEqualTo("TOOL_PROTOCOL_INVALID");
        assertThat(rounds).hasValue(fourthRound?4:2);
        org.mockito.Mockito.verify(releases,org.mockito.Mockito.times(1)).read(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(retrieval);
    }

    @Test void cancellationPreventsNewCallRegistrationAndLateResultPersistence() {
        var run=state.register(request(),"audit-trace").run();state.claim(run.getId());
        UUID call=executionState.beginModel(run.getId());state.cancel(run.getId());
        assertThatThrownBy(()->executionState.beginModel(run.getId())).isInstanceOf(UpgradeToolException.class);
        assertThatThrownBy(()->executionState.finishModel(run.getId(),call,"model",1,1,2,10)).isInstanceOf(UpgradeToolException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM upgrade_research_model_call WHERE id=?",String.class,call)).isEqualTo("CANCELLED");
        assertThat(state.get(run.getId()).getModelCalls()).isOne();
    }

    @Test void queueAdmissionRejectsFourthNewRunButAllowsExistingReplay() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var subjects=org.mockito.Mockito.mock(io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider.class);
        org.mockito.Mockito.when(subjects.currentSubject()).thenReturn(new io.github.fanqiepi.contextpilot.identity.UserSubject(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),"LOCAL_OWNER"));
        var start=new UpgradeResearchStartService(mapper,state,subjects,id->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}},transactions);
        try {
            var first=request();start.start(first,"trace");assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            start.start(request(),"trace");start.start(request(),"trace");
            assertThat(start.start(first,"replay").replay()).isTrue();
            var rejected=request();assertThatThrownBy(()->start.start(rejected,"trace")).isInstanceOf(UpgradeQueueFullException.class);
            assertThat(mapper.byRequest(subjects.currentSubject().id(),rejected.clientRequestId())).isNull();
        }finally{release.countDown();start.shutdown();}
    }

    @Test void concurrentRequestReplayCreatesExactlyOneRun() throws Exception {
        var request=request();
        var executor=Executors.newFixedThreadPool(4);
        try {
            List<Callable<UpgradeRunStateService.Registration>> tasks=new ArrayList<>();
            for(int i=0;i<4;i++) tasks.add(()->state.register(request,UUID.randomUUID().toString()));
            var results=executor.invokeAll(tasks);
            Set<UUID> ids=new HashSet<>();int newRuns=0;
            for(var result:results){var registration=result.get();ids.add(registration.run().getId());if(!registration.replay())newRuns++;}
            assertThat(ids).hasSize(1);assertThat(newRuns).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM upgrade_research_run WHERE client_request_id=?",Integer.class,request.clientRequestId())).isOne();
        } finally {executor.shutdownNow();}
    }

    @Test void rejectsKeyReuseAndDeletedReplayWithoutRevivingRun() {
        var request=request();var run=state.register(request,"trace").run();
        var changed=new UpgradeStartRequest(request.clientRequestId(),request.knowledgeBaseId(),request.documentIds(),
                request.repositoryKey(),request.releaseTags(),"different question",null);
        assertThatThrownBy(()->state.register(changed,"trace")).isInstanceOf(ConflictException.class);
        state.cancel(run.getId());
        jdbc.update("UPDATE upgrade_research_run SET deleted=1 WHERE id=?",run.getId());
        assertThatThrownBy(()->state.register(request,"trace")).isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("UPGRADE_RUN_DELETED");
    }

    @Test void detectsIndexChangesAndFiltersOtherOwners() {
        var request=request();var run=state.register(request,"trace").run();
        assertThat(state.sourcesUnchanged(run)).isTrue();
        assertThat(mapper.owned(UUID.randomUUID(),run.getId())).isNull();
        jdbc.update("UPDATE source_document SET indexed_at=indexed_at+INTERVAL '1 second' WHERE id=?",request.documentIds().getFirst());
        assertThat(state.sourcesUnchanged(run)).isFalse();
    }

    @Test void cancellationIsIdempotentAndLateFailureDoesNotReviveIt() {
        var run=state.register(request(),"trace").run();assertThat(state.claim(run.getId())).isTrue();
        assertThat(state.claim(run.getId())).isFalse();
        assertThat(state.cancel(run.getId()).getStatus()).isEqualTo(UpgradeRunStatus.CANCELLED);
        long revision=state.get(run.getId()).getRevision();
        state.cancel(run.getId());state.fail(run.getId(),"LATE_FAILURE");
        assertThat(state.get(run.getId()).getRevision()).isEqualTo(revision);
        assertThat(state.claim(run.getId())).isFalse();
    }

    @Test void deadlineAndRestartConvergeWithoutReexecution() {
        var timed=state.register(request(),"timeout-trace").run();
        jdbc.update("UPDATE upgrade_research_run SET created_at=CURRENT_TIMESTAMP-INTERVAL '200 seconds',deadline=CURRENT_TIMESTAMP-INTERVAL '20 seconds' WHERE id=?",timed.getId());
        state.expire(timed.getId());assertThat(state.get(timed.getId()).getErrorCode()).isEqualTo("DEADLINE_EXCEEDED");
        var abandoned=state.register(request(),"restart-trace").run();state.claim(abandoned.getId());
        new UpgradeRecoveryService(mapper,state).recover();
        assertThat(state.get(abandoned.getId()).getErrorCode()).isEqualTo("PROCESS_RESTARTED");
    }

    @Test void databaseRollsBackRegistrationWithOuterTransaction() {
        var request=request();
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            state.register(request,"trace");throw new IllegalStateException("injected failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(mapper.byRequest(UUID.fromString("00000000-0000-0000-0000-000000000001"),request.clientRequestId())).isNull();
    }

    @Test void rejectsUnknownRepositoryPathTagsAndCrossKnowledgeBaseSelection() {
        var request=request();
        assertThatThrownBy(()->state.register(new UpgradeStartRequest(UUID.randomUUID(),request.knowledgeBaseId(),
                request.documentIds(),"arbitrary",List.of("v2"),"question",null),"trace")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->state.register(new UpgradeStartRequest(UUID.randomUUID(),request.knowledgeBaseId(),
                request.documentIds(),"spring-ai",List.of("../secret"),"question",null),"trace")).isInstanceOf(BadRequestException.class);
        UUID other=knowledgeBases.create(new KnowledgeBaseCreateRequest("other-"+UUID.randomUUID(),null)).id();
        assertThatThrownBy(()->state.register(new UpgradeStartRequest(UUID.randomUUID(),other,request.documentIds(),
                "spring-ai",List.of("v2"),"question",null),"trace")).isInstanceOf(io.github.fanqiepi.contextpilot.common.ResourceNotFoundException.class);
    }

    @Test void canonicalFingerprintIgnoresTagOrderAndDuplicateTagsButPreservesCase() {
        var request=request();
        var a=new UpgradeStartRequest(request.clientRequestId(),request.knowledgeBaseId(),request.documentIds(),"spring-ai",List.of("v2","v1")," question ",null);
        var b=new UpgradeStartRequest(request.clientRequestId(),request.knowledgeBaseId(),request.documentIds(),"spring-ai",List.of("v1","v2","v1"),"question",null);
        assertThat(UpgradeRunStateService.fingerprint(a)).isEqualTo(UpgradeRunStateService.fingerprint(b));
    }

    private UpgradeStartRequest request() {
        UUID kb=knowledgeBases.create(new KnowledgeBaseCreateRequest("upgrade-"+UUID.randomUUID(),null)).id();
        UUID doc=UUID.randomUUID();var p=profiles.currentProfile();
        jdbc.update("""
          INSERT INTO source_document(id,knowledge_base_id,original_filename,file_type,size_bytes,storage_key,sha256,status,
            embedding_profile_id,embedding_provider,embedding_model,embedding_dimensions,embedding_profile_version,indexed_at)
          VALUES (?,?,'legacy.md','MARKDOWN',1,?,?,'SUCCEEDED',?,?,?,?,?,CURRENT_TIMESTAMP)
          """,doc,kb,doc.toString(),"a".repeat(64),p.id(),p.provider(),p.model(),p.dimensions(),p.version());
        jdbc.update("INSERT INTO vector_store(id,content,metadata,embedding) VALUES (?, 'public synthetic',CAST(? AS json),CAST(? AS vector))",
                UUID.randomUUID(),UpgradeJson.write(Map.of("document_id",doc.toString(),"knowledge_base_id",kb.toString(),"embedding_profile_id",p.id())),
                "["+String.join(",",Collections.nCopies(1024,"0.1"))+"]");
        return new UpgradeStartRequest(UUID.randomUUID(),kb,List.of(doc),"spring-ai",List.of("v2.0.0"),"Analyze upgrade",null);
    }
}
