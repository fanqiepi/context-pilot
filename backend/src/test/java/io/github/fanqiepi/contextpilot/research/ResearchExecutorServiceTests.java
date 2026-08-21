package io.github.fanqiepi.contextpilot.research;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatPersistenceService;
import io.github.fanqiepi.contextpilot.chat.MessageCitationMapper;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import io.github.fanqiepi.contextpilot.model.ChatModelGateway;
import io.github.fanqiepi.contextpilot.model.ChatModelResult;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.support.TaskExecutorAdapter;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResearchExecutorServiceTests {
    @Mock private ResearchRunMapper runMapper;
    @Mock private ResearchStepMapper stepMapper;
    @Mock private ResearchEvidenceMapper evidenceMapper;
    @Mock private ResearchStepEvidenceMapper stepEvidenceMapper;
    @Mock private ResearchQueryService queryService;
    @Mock private ResearchDocumentMapper researchDocumentMapper;
    @Mock private RetrievalService retrievalService;
    @Mock private ChatModelGateway chatModelGateway;
    @Mock private ChatPersistenceService chatPersistenceService;
    @Mock private ChatMessageMapper chatMessageMapper;
    @Mock private MessageCitationMapper messageCitationMapper;
    private ResearchJsonCodec jsonCodec;
    private ResearchExecutorService service;

    @BeforeEach
    void setUp() {
        jsonCodec = new ResearchJsonCodec(JsonMapper.builder().build());
        service = new ResearchExecutorService(
                runMapper, stepMapper, evidenceMapper, stepEvidenceMapper, jsonCodec, queryService,
                researchDocumentMapper, retrievalService, new EmbeddingIndexProperties(), chatModelGateway,
                chatPersistenceService, chatMessageMapper, messageCitationMapper,
                new TaskExecutorAdapter(Runnable::run));
    }

    @Test
    void executesEveryStepPerDocumentAndPersistsBoundedEvidenceBeforeSynthesis() {
        UUID runId = UUID.randomUUID();
        UUID firstDocument = UUID.randomUUID();
        UUID secondDocument = UUID.randomUUID();
        ResearchRunEntity run = run(runId, List.of(firstDocument, secondDocument));
        List<ResearchStepEntity> steps = List.of(
                step(runId, 1, "deployment", List.of(firstDocument, secondDocument)),
                step(runId, 2, "security", List.of(firstDocument, secondDocument)));
        when(runMapper.selectById(runId)).thenReturn(run);
        when(runMapper.claimExecution(eq(runId), any())).thenReturn(1);
        when(stepMapper.selectByRunId(runId)).thenReturn(steps);
        when(stepMapper.markRunning(any(), any())).thenReturn(1);
        when(runMapper.updateCurrentStep(eq(runId), anyInt(), any())).thenReturn(1);
        when(runMapper.beginSynthesis(eq(runId), eq(4), eq(4), eq(4), anyInt(), any())).thenReturn(1);
        when(runMapper.complete(eq(runId), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(evidenceMapper.insert(any())).thenReturn(1);
        when(stepEvidenceMapper.insert(any())).thenReturn(1);
        when(retrievalService.searchDocument(any(), any(), anyString(), eq(3)))
                .thenAnswer(invocation -> {
                    UUID documentId = invocation.getArgument(1);
                    String query = invocation.getArgument(2);
                    return List.of(new RetrievalResultResponse(
                            UUID.randomUUID().toString(), documentId, documentId + ".md", 0,
                            null, query + " evidence", 0.9));
                });
        ChatMessageEntity question = new ChatMessageEntity();
        question.setContent("比较部署和安全");
        when(chatMessageMapper.selectById(run.getUserMessageId())).thenReturn(question);
        when(chatPersistenceService.beginModelCall(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(UUID.randomUUID());
        when(chatModelGateway.provider()).thenReturn("DEEPSEEK");
        when(chatModelGateway.configuredModel()).thenReturn("deepseek-v4-flash");
        when(chatModelGateway.generate(anyString(), anyString()))
                .thenReturn(new ChatModelResult("比较结论 [1]", "deepseek-v4-flash", 10, 5, 15));
        ResearchRunResponse completed = response(runId, ResearchExecutionStatus.SUCCEEDED);
        when(queryService.get(runId)).thenReturn(completed);

        ResearchExecutionResult result = service.execute(runId);

        assertThat(result.answer()).isEqualTo("比较结论 [1]");
        assertThat(result.citations()).hasSize(4);
        verify(retrievalService, times(4)).searchDocument(any(), any(), anyString(), eq(3));
        verify(evidenceMapper, times(4)).insert(any());
        verify(stepMapper, times(2)).complete(any(), eq(ResearchStepStatus.SUCCEEDED),
                eq(2), eq(2), anyLong(), isNull(), any());
        verify(chatPersistenceService).completeResearchSuccess(
                eq(run.getAssistantMessageId()), any(), eq("比较结论 [1]"), any(), any(), anyLong());
        verify(chatModelGateway).generate(
                org.mockito.ArgumentMatchers.contains("1800"), anyString());
    }

    @Test
    void keepsSupportedContentAndMarksRunPartialWhenOneDocumentRetrievalFails() {
        UUID runId = UUID.randomUUID();
        UUID firstDocument = UUID.randomUUID();
        UUID secondDocument = UUID.randomUUID();
        ResearchRunEntity run = run(runId, List.of(firstDocument, secondDocument));
        ResearchStepEntity step = step(runId, 1, "security", List.of(firstDocument, secondDocument));
        when(runMapper.selectById(runId)).thenReturn(run);
        when(runMapper.claimExecution(eq(runId), any())).thenReturn(1);
        when(stepMapper.selectByRunId(runId)).thenReturn(List.of(step));
        when(stepMapper.markRunning(any(), any())).thenReturn(1);
        when(runMapper.updateCurrentStep(eq(runId), eq(1), any())).thenReturn(1);
        when(runMapper.beginSynthesis(eq(runId), eq(2), eq(1), eq(1), anyInt(), any())).thenReturn(1);
        when(runMapper.complete(eq(runId), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(evidenceMapper.insert(any())).thenReturn(1);
        when(stepEvidenceMapper.insert(any())).thenReturn(1);
        when(retrievalService.searchDocument(any(), any(), anyString(), eq(3)))
                .thenThrow(new InternalServiceException(
                        "RESEARCH_DOCUMENT_RETRIEVAL_FAILED", "one document failed",
                        new IllegalStateException("test")))
                .thenReturn(List.of(new RetrievalResultResponse(
                        UUID.randomUUID().toString(), secondDocument, "second.md", 0,
                        null, "security evidence", 0.9)));
        ChatMessageEntity question = new ChatMessageEntity();
        question.setContent("比较安全性");
        when(chatMessageMapper.selectById(run.getUserMessageId())).thenReturn(question);
        when(chatPersistenceService.beginModelCall(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(UUID.randomUUID());
        when(chatModelGateway.provider()).thenReturn("DEEPSEEK");
        when(chatModelGateway.configuredModel()).thenReturn("deepseek-v4-flash");
        when(chatModelGateway.generate(anyString(), anyString()))
                .thenReturn(new ChatModelResult("可信结论 [1]\n这行没有引用", "deepseek-v4-flash", 10, 5, 15));
        when(queryService.get(runId)).thenReturn(response(runId, ResearchExecutionStatus.PARTIAL));

        ResearchExecutionResult result = service.execute(runId);

        assertThat(result.answer()).contains("可信结论 [1]").doesNotContain("这行没有引用");
        verify(stepMapper).complete(eq(step.getId()), eq(ResearchStepStatus.PARTIAL),
                eq(1), eq(1), anyLong(), anyString(), any());
        verify(runMapper).complete(eq(runId), eq(ResearchExecutionStatus.PARTIAL),
                eq(ResearchAnswerStatus.ANSWERED), eq(10), eq(5), eq(15),
                eq("RESEARCH_PARTIAL_RETRIEVAL"), anyString(), any());
    }

    @Test
    void convergesToTimeoutWithoutWaitingForABlockedRetrievalCall() {
        UUID runId = UUID.randomUUID();
        UUID firstDocument = UUID.randomUUID();
        UUID secondDocument = UUID.randomUUID();
        ResearchRunEntity run = run(runId, List.of(firstDocument, secondDocument));
        ResearchStepEntity step = step(runId, 1, "security", List.of(firstDocument, secondDocument));
        when(runMapper.selectById(runId)).thenReturn(run);
        when(runMapper.claimExecution(eq(runId), any())).thenReturn(1);
        when(stepMapper.selectByRunId(runId)).thenReturn(List.of(step));
        when(stepMapper.markRunning(any(), any())).thenReturn(1);
        when(runMapper.updateCurrentStep(eq(runId), eq(1), any())).thenReturn(1);
        when(runMapper.beginSynthesis(eq(runId), eq(1), eq(0), eq(0), eq(0), any())).thenReturn(1);
        when(runMapper.fail(eq(runId), eq("RESEARCH_TIMEOUT"), anyString(), any())).thenReturn(1);
        when(retrievalService.searchDocument(any(), any(), anyString(), eq(3)))
                .thenAnswer(invocation -> {
                    Thread.sleep(1000);
                    return List.of();
                });
        when(messageCitationMapper.selectList(any())).thenReturn(List.of());
        when(queryService.get(runId)).thenReturn(response(runId, ResearchExecutionStatus.FAILED));
        ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
        ResearchExecutorService timeoutService = new ResearchExecutorService(
                runMapper, stepMapper, evidenceMapper, stepEvidenceMapper, jsonCodec, queryService,
                researchDocumentMapper, retrievalService, new EmbeddingIndexProperties(), chatModelGateway,
                chatPersistenceService, chatMessageMapper, messageCitationMapper,
                new TaskExecutorAdapter(ioExecutor), 25);

        long startedAt = System.nanoTime();
        try {
            ResearchExecutionResult result = timeoutService.execute(runId);

            assertThat(result.run().executionStatus()).isEqualTo(ResearchExecutionStatus.FAILED);
            assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
                    .isLessThan(500);
            verify(runMapper).fail(eq(runId), eq("RESEARCH_TIMEOUT"), anyString(), any());
        } finally {
            ioExecutor.shutdownNow();
        }
    }

    @Test
    void keepsFixedNoEvidenceStatementWithoutDowngradingTheRun() {
        UUID runId = UUID.randomUUID();
        UUID firstDocument = UUID.randomUUID();
        UUID secondDocument = UUID.randomUUID();
        ResearchRunEntity run = run(runId, List.of(firstDocument, secondDocument));
        ResearchStepEntity step = step(runId, 1, "security", List.of(firstDocument, secondDocument));
        when(runMapper.selectById(runId)).thenReturn(run);
        when(runMapper.claimExecution(eq(runId), any())).thenReturn(1);
        when(stepMapper.selectByRunId(runId)).thenReturn(List.of(step));
        when(researchDocumentMapper.selectNames(any()))
                .thenReturn(List.of(fact(firstDocument, "first.md"), fact(secondDocument, "second.md")));
        when(stepMapper.markRunning(any(), any())).thenReturn(1);
        when(runMapper.updateCurrentStep(eq(runId), eq(1), any())).thenReturn(1);
        when(runMapper.beginSynthesis(eq(runId), eq(2), eq(1), eq(1), anyInt(), any())).thenReturn(1);
        when(runMapper.complete(eq(runId), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(evidenceMapper.insert(any())).thenReturn(1);
        when(stepEvidenceMapper.insert(any())).thenReturn(1);
        when(retrievalService.searchDocument(any(), any(), anyString(), eq(3)))
                .thenReturn(List.of())
                .thenReturn(List.of(new RetrievalResultResponse(
                        UUID.randomUUID().toString(), secondDocument, "second.md", 0,
                        null, "security evidence", 0.9)));
        ChatMessageEntity question = new ChatMessageEntity();
        question.setContent("比较安全性");
        when(chatMessageMapper.selectById(run.getUserMessageId())).thenReturn(question);
        when(chatPersistenceService.beginModelCall(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(UUID.randomUUID());
        when(chatModelGateway.provider()).thenReturn("DEEPSEEK");
        when(chatModelGateway.configuredModel()).thenReturn("deepseek-v4-flash");
        when(chatModelGateway.generate(anyString(), anyString()))
                .thenReturn(new ChatModelResult(
                        "## 安全性\n`first.md`：本次检索未找到相关内容。\n`second.md` 提供安全说明 [1]",
                        "deepseek-v4-flash", 10, 5, 15));
        when(queryService.get(runId)).thenReturn(response(runId, ResearchExecutionStatus.SUCCEEDED));

        ResearchExecutionResult result = service.execute(runId);

        assertThat(result.answer())
                .contains("`first.md`：本次检索未找到相关内容。")
                .doesNotContain("部分完成");
        verify(runMapper).complete(eq(runId), eq(ResearchExecutionStatus.SUCCEEDED),
                eq(ResearchAnswerStatus.ANSWERED), eq(10), eq(5), eq(15),
                isNull(), isNull(), any());
    }

    @Test
    void reportsUnsupportedContentRemovalWithAnAccuratePartialReason() {
        UUID runId = UUID.randomUUID();
        UUID firstDocument = UUID.randomUUID();
        UUID secondDocument = UUID.randomUUID();
        ResearchRunEntity run = run(runId, List.of(firstDocument, secondDocument));
        ResearchStepEntity step = step(runId, 1, "security", List.of(firstDocument, secondDocument));
        when(runMapper.selectById(runId)).thenReturn(run);
        when(runMapper.claimExecution(eq(runId), any())).thenReturn(1);
        when(stepMapper.selectByRunId(runId)).thenReturn(List.of(step));
        when(researchDocumentMapper.selectNames(any()))
                .thenReturn(List.of(fact(firstDocument, "first.md"), fact(secondDocument, "second.md")));
        when(stepMapper.markRunning(any(), any())).thenReturn(1);
        when(runMapper.updateCurrentStep(eq(runId), eq(1), any())).thenReturn(1);
        when(runMapper.beginSynthesis(eq(runId), eq(2), eq(2), eq(2), anyInt(), any())).thenReturn(1);
        when(runMapper.complete(eq(runId), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(evidenceMapper.insert(any())).thenReturn(1);
        when(stepEvidenceMapper.insert(any())).thenReturn(1);
        when(retrievalService.searchDocument(any(), any(), anyString(), eq(3)))
                .thenAnswer(invocation -> {
                    UUID documentId = invocation.getArgument(1);
                    return List.of(new RetrievalResultResponse(
                            UUID.randomUUID().toString(), documentId, documentId + ".md", 0,
                            null, "security evidence", 0.9));
                });
        ChatMessageEntity question = new ChatMessageEntity();
        question.setContent("比较安全性");
        when(chatMessageMapper.selectById(run.getUserMessageId())).thenReturn(question);
        when(chatPersistenceService.beginModelCall(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(UUID.randomUUID());
        when(chatModelGateway.provider()).thenReturn("DEEPSEEK");
        when(chatModelGateway.configuredModel()).thenReturn("deepseek-v4-flash");
        when(chatModelGateway.generate(anyString(), anyString()))
                .thenReturn(new ChatModelResult(
                        "## 安全性\n可信结论 [1]\n没有引用的推断",
                        "deepseek-v4-flash", 10, 5, 15));
        when(queryService.get(runId)).thenReturn(response(runId, ResearchExecutionStatus.PARTIAL));

        ResearchExecutionResult result = service.execute(runId);

        assertThat(result.answer())
                .startsWith("综合结果中缺少有效引用的内容已被移除")
                .contains("可信结论 [1]")
                .doesNotContain("没有引用的推断");
        verify(runMapper).complete(eq(runId), eq(ResearchExecutionStatus.PARTIAL),
                eq(ResearchAnswerStatus.ANSWERED), eq(10), eq(5), eq(15),
                eq("RESEARCH_UNSUPPORTED_CONTENT_REMOVED"),
                eq("综合结果中缺少有效引用的内容已被移除，以下仅展示通过引用校验的部分。"), any());
    }

    private ResearchRunEntity run(UUID runId, List<UUID> documentIds) {
        ResearchRunEntity run = new ResearchRunEntity();
        run.setId(runId);
        run.setKnowledgeBaseId(UUID.randomUUID());
        run.setConversationId(UUID.randomUUID());
        run.setUserMessageId(UUID.randomUUID());
        run.setAssistantMessageId(UUID.randomUUID());
        run.setExecutionStatus(ResearchExecutionStatus.PLANNING);
        run.setSelectedDocumentIdsJson(jsonCodec.writeDocumentIds(documentIds));
        run.setTraceId("trace");
        return run;
    }

    private ResearchStepEntity step(UUID runId, int ordinal, String query, List<UUID> documentIds) {
        ResearchStepEntity step = new ResearchStepEntity();
        step.setId(UUID.randomUUID());
        step.setRunId(runId);
        step.setOrdinal(ordinal);
        step.setGoal(query);
        step.setQuery(query);
        step.setDocumentIdsJson(jsonCodec.writeDocumentIds(documentIds));
        step.setStatus(ResearchStepStatus.PENDING);
        return step;
    }

    private ResearchDocumentFact fact(UUID id, String originalFilename) {
        ResearchDocumentFact fact = new ResearchDocumentFact();
        fact.setId(id);
        fact.setOriginalFilename(originalFilename);
        return fact;
    }

    private ResearchRunResponse response(UUID runId, ResearchExecutionStatus status) {
        return new ResearchRunResponse(
                runId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ResearchTaskType.DOCUMENT_COMPARISON,
                DeterministicResearchPlanner.PLAN_VERSION, List.of(UUID.randomUUID(), UUID.randomUUID()),
                status, ResearchAnswerStatus.ANSWERED, List.of(), ResearchBudget.V1,
                new ResearchUsageResponse(4, 4, 4, 100, 10, 5, 15),
                null, null, "trace", null, OffsetDateTime.now(), null,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
    }
}
