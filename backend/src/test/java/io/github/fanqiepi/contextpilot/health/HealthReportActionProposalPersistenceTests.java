package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.action.ActionRequestService;
import io.github.fanqiepi.contextpilot.action.ActionRequestStatus;
import io.github.fanqiepi.contextpilot.action.ActionType;
import io.github.fanqiepi.contextpilot.action.ReindexDocumentActionParameters;
import io.github.fanqiepi.contextpilot.action.RetryDocumentProcessingActionParameters;
import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.CapabilityMatchReason;
import io.github.fanqiepi.contextpilot.chat.ChatAnswerResponse;
import io.github.fanqiepi.contextpilot.chat.ChatApplicationService;
import io.github.fanqiepi.contextpilot.chat.ChatRequest;
import io.github.fanqiepi.contextpilot.chat.ConversationHistoryService;
import io.github.fanqiepi.contextpilot.chat.ConversationMessageResponse;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.document.DocumentFileType;
import io.github.fanqiepi.contextpilot.document.DocumentProcessingCoordinator;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import io.github.fanqiepi.contextpilot.document.DocumentVectorIndex;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import io.github.fanqiepi.contextpilot.document.SourceDocumentEntity;
import io.github.fanqiepi.contextpilot.document.SourceDocumentMapper;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseResponse;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.vectorstore.type=none",
        "contextpilot.document.processing.enabled=true",
        "contextpilot.document.processing.max-attempts=3"
})
@Testcontainers(disabledWithoutDocker = true)
class HealthReportActionProposalPersistenceTests {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17-bookworm")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PGVECTOR_IMAGE)
            .withDatabaseName("context_pilot")
            .withUsername("context_pilot")
            .withPassword("context_pilot_test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private HealthReportActionProposalService proposalService;
    @Autowired
    private ActionRequestService actionRequestService;
    @Autowired
    private ChatApplicationService chatApplicationService;
    @Autowired
    private ConversationHistoryService historyService;
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private SourceDocumentMapper sourceDocumentMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EmbeddingIndexProperties embeddingIndexProperties;

    @MockitoBean
    private DocumentProcessingCoordinator processingCoordinator;
    @MockitoBean
    private DocumentVectorIndex documentVectorIndex;

    @BeforeEach
    void configureProcessingCoordinator() {
        reset(processingCoordinator, documentVectorIndex);
        when(processingCoordinator.isEnabled()).thenReturn(true);
        when(processingCoordinator.canRetry(anyInt())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(0) < 3);
        when(documentVectorIndex.isAvailable()).thenReturn(true);
    }

    @Test
    void createsOneAuditableProposalAndConfirmsOneDocumentRetry() {
        HealthContext context = healthContext(1);

        HealthReportActionProposalResponse created = proposalService.propose(
                context.reportId(), context.issueId(), "trace-retry-proposal");

        assertThat(created.reusedExistingProposal()).isFalse();
        assertThat(created.userMessage().capabilityId()).isEqualTo(CapabilityId.BUSINESS_ACTION);
        assertThat(created.userMessage().capabilityVersion()).isEqualTo("v2");
        assertThat(created.userMessage().capabilityMatchReason())
                .isEqualTo(CapabilityMatchReason.HEALTH_REPORT_ISSUE_SELECTED);
        assertThat(created.assistantMessage().actionRequest()).isEqualTo(created.actionRequest());
        assertThat(created.actionRequest().actionType()).isEqualTo(ActionType.RETRY_DOCUMENT_PROCESSING);
        assertThat(created.actionRequest().status()).isEqualTo(ActionRequestStatus.PENDING_CONFIRMATION);
        assertThat(created.actionRequest().parameters())
                .isInstanceOfSatisfying(RetryDocumentProcessingActionParameters.class, parameters -> {
                    assertThat(parameters.documentId()).isEqualTo(context.documentId());
                    assertThat(parameters.healthReportId()).isEqualTo(context.reportId());
                    assertThat(parameters.healthIssueId()).isEqualTo(context.issueId());
                });
        assertThat(sourceDocumentMapper.selectById(context.documentId()).getStatus())
                .isEqualTo(DocumentStatus.FAILED);

        HealthReportActionProposalResponse repeated = proposalService.propose(
                context.reportId(), context.issueId(), "trace-ignored-duplicate");
        assertThat(repeated.reusedExistingProposal()).isTrue();
        assertThat(repeated.actionRequest().id()).isEqualTo(created.actionRequest().id());
        assertThat(repeated.userMessage().id()).isEqualTo(created.userMessage().id());
        assertThat(repeated.assistantMessage().id()).isEqualTo(created.assistantMessage().id());
        assertThat(activeActionCount(context.issueId())).isOne();
        assertThat(proposalMessageCount(context.conversationId())).isEqualTo(2);

        List<ConversationMessageResponse> history = historyService.messages(context.conversationId());
        assertThat(history).hasSize(4);
        assertThat(history.getLast().actionRequest().id()).isEqualTo(created.actionRequest().id());

        ActionRequestResponse confirmed = actionRequestService.confirm(created.actionRequest().id());
        assertThat(confirmed.status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(confirmed.resultSummary()).contains("已提交").contains("最终结果");
        assertThat(sourceDocumentMapper.selectById(context.documentId()).getStatus())
                .isEqualTo(DocumentStatus.PENDING);
        assertThat(actionRequestService.confirm(created.actionRequest().id()).status())
                .isEqualTo(ActionRequestStatus.SUCCEEDED);
        verify(processingCoordinator, times(1)).submit(context.documentId());
    }

    @Test
    void rejectsStaleTargetBothBeforeProposalAndBeforeConfirmation() {
        HealthContext staleBeforeProposal = healthContext(1);
        jdbcTemplate.update(
                "UPDATE source_document SET status = 'PROCESSING' WHERE id = ?",
                staleBeforeProposal.documentId());

        assertThatThrownBy(() -> proposalService.propose(
                staleBeforeProposal.reportId(), staleBeforeProposal.issueId(), "trace-stale-proposal"))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("ACTION_TARGET_STATE_CHANGED");
        assertThat(activeActionCount(staleBeforeProposal.issueId())).isZero();

        HealthContext staleBeforeConfirmation = healthContext(1);
        HealthReportActionProposalResponse proposal = proposalService.propose(
                staleBeforeConfirmation.reportId(),
                staleBeforeConfirmation.issueId(),
                "trace-stale-confirmation");
        jdbcTemplate.update(
                "UPDATE source_document SET status = 'PROCESSING' WHERE id = ?",
                staleBeforeConfirmation.documentId());

        ActionRequestResponse failed = actionRequestService.confirm(proposal.actionRequest().id());
        assertThat(failed.status()).isEqualTo(ActionRequestStatus.FAILED);
        assertThat(failed.errorSummary()).contains("状态已变化").doesNotContain("SQLException", "UPDATE");
        assertThat(sourceDocumentMapper.selectById(staleBeforeConfirmation.documentId()).getStatus())
                .isEqualTo(DocumentStatus.PROCESSING);
        verify(processingCoordinator, never()).submit(staleBeforeConfirmation.documentId());
    }

    @Test
    void rejectsIssueThatReachedRetryLimitAfterTheHealthSnapshot() {
        HealthContext context = healthContext(1);
        jdbcTemplate.update(
                "UPDATE source_document SET processing_attempts = 3 WHERE id = ?",
                context.documentId());

        assertThatThrownBy(() -> proposalService.propose(
                context.reportId(), context.issueId(), "trace-limit"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("最大处理次数")
                .extracting("code")
                .isEqualTo("DOCUMENT_RETRY_LIMIT_REACHED");
        assertThat(activeActionCount(context.issueId())).isZero();
    }

    @Test
    void rejectsHealthSnapshotThatWasAlreadyIneligible() {
        HealthContext context = healthContext(3);

        assertThatThrownBy(() -> proposalService.propose(
                context.reportId(), context.issueId(), "trace-ineligible"))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("HEALTH_REPORT_ISSUE_NOT_ACTIONABLE");
        assertThat(activeActionCount(context.issueId())).isZero();
        assertThat(proposalMessageCount(context.conversationId())).isZero();
    }

    @Test
    void serializesConcurrentProposalAndConfirmationRequests() throws Exception {
        HealthContext context = healthContext(1);
        CountDownLatch proposalReady = new CountDownLatch(2);
        CountDownLatch proposalStart = new CountDownLatch(1);
        HealthReportActionProposalResponse firstProposal;
        HealthReportActionProposalResponse secondProposal;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<HealthReportActionProposalResponse> first = executor.submit(() ->
                    proposeAfterSignal(context, "trace-concurrent-1", proposalReady, proposalStart));
            Future<HealthReportActionProposalResponse> second = executor.submit(() ->
                    proposeAfterSignal(context, "trace-concurrent-2", proposalReady, proposalStart));
            proposalReady.await();
            proposalStart.countDown();
            firstProposal = first.get();
            secondProposal = second.get();
        }
        assertThat(secondProposal.actionRequest().id()).isEqualTo(firstProposal.actionRequest().id());
        assertThat(List.of(
                firstProposal.reusedExistingProposal(),
                secondProposal.reusedExistingProposal()))
                .containsExactlyInAnyOrder(false, true);
        assertThat(activeActionCount(context.issueId())).isOne();
        assertThat(proposalMessageCount(context.conversationId())).isEqualTo(2);

        CountDownLatch confirmationReady = new CountDownLatch(2);
        CountDownLatch confirmationStart = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ActionRequestResponse> first = executor.submit(() -> confirmAfterSignal(
                    firstProposal.actionRequest().id(), confirmationReady, confirmationStart));
            Future<ActionRequestResponse> second = executor.submit(() -> confirmAfterSignal(
                    firstProposal.actionRequest().id(), confirmationReady, confirmationStart));
            confirmationReady.await();
            confirmationStart.countDown();
            first.get();
            second.get();
        }
        assertThat(actionRequestService.get(firstProposal.actionRequest().id()).status())
                .isEqualTo(ActionRequestStatus.SUCCEEDED);
        verify(processingCoordinator, times(1)).submit(context.documentId());
    }

    @Test
    void rechecksProcessingAvailabilityAtConfirmation() {
        HealthContext context = healthContext(1);
        HealthReportActionProposalResponse proposal = proposalService.propose(
                context.reportId(), context.issueId(), "trace-disabled-confirmation");
        when(processingCoordinator.isEnabled()).thenReturn(false);

        ActionRequestResponse failed = actionRequestService.confirm(proposal.actionRequest().id());

        assertThat(failed.status()).isEqualTo(ActionRequestStatus.FAILED);
        assertThat(failed.errorSummary()).contains("未启用").doesNotContain("Exception", "DocumentService");
        assertThat(sourceDocumentMapper.selectById(context.documentId()).getStatus())
                .isEqualTo(DocumentStatus.FAILED);
        verify(processingCoordinator, never()).submit(context.documentId());
    }

    @Test
    void createsAndConfirmsReindexProposalsForUnknownOutdatedAndMissingIndexes() {
        ReindexHealthContext unknown = reindexHealthContext(null);
        ReindexHealthContext outdated = reindexHealthContext("outdated-profile-v0");
        ReindexHealthContext missing = reindexHealthContext(embeddingIndexProperties.currentProfile().id());

        assertReindexProposalAndConfirmation(unknown, KnowledgeBaseHealthIssueType.EMBEDDING_PROFILE_UNKNOWN);
        assertReindexProposalAndConfirmation(outdated, KnowledgeBaseHealthIssueType.EMBEDDING_PROFILE_OUTDATED);
        assertReindexProposalAndConfirmation(missing, KnowledgeBaseHealthIssueType.VECTOR_INDEX_MISSING);

        verify(processingCoordinator, times(1)).submit(unknown.documentId());
        verify(processingCoordinator, times(1)).submit(outdated.documentId());
        verify(processingCoordinator, times(1)).submit(missing.documentId());
    }

    @Test
    void rejectsReindexWhenTheMissingVectorWasRestoredBeforeProposalOrConfirmation() {
        ReindexHealthContext restoredBeforeProposal = reindexHealthContext(
                embeddingIndexProperties.currentProfile().id());
        insertCurrentProfileVector(restoredBeforeProposal);

        assertThatThrownBy(() -> proposalService.propose(
                restoredBeforeProposal.reportId(),
                restoredBeforeProposal.issueId(),
                "trace-reindex-restored-before-proposal"))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("DOCUMENT_REINDEX_NOT_REQUIRED");
        assertThat(activeActionCount(restoredBeforeProposal.issueId())).isZero();

        ReindexHealthContext restoredBeforeConfirmation = reindexHealthContext(
                embeddingIndexProperties.currentProfile().id());
        HealthReportActionProposalResponse proposal = proposalService.propose(
                restoredBeforeConfirmation.reportId(),
                restoredBeforeConfirmation.issueId(),
                "trace-reindex-restored-before-confirmation");
        insertCurrentProfileVector(restoredBeforeConfirmation);

        ActionRequestResponse failed = actionRequestService.confirm(proposal.actionRequest().id());

        assertThat(failed.status()).isEqualTo(ActionRequestStatus.FAILED);
        assertThat(failed.errorSummary()).contains("无需提交").doesNotContain("SELECT", "SQLException");
        assertThat(sourceDocumentMapper.selectById(restoredBeforeConfirmation.documentId()).getStatus())
                .isEqualTo(DocumentStatus.SUCCEEDED);
        verify(processingCoordinator, never()).submit(restoredBeforeConfirmation.documentId());
    }

    @Test
    void rechecksVectorStoreAvailabilityAtProposalAndConfirmation() {
        ReindexHealthContext unavailableBeforeProposal = reindexHealthContext("outdated-profile-v0");
        when(documentVectorIndex.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> proposalService.propose(
                unavailableBeforeProposal.reportId(),
                unavailableBeforeProposal.issueId(),
                "trace-reindex-unavailable-before-proposal"))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("VECTOR_STORE_UNAVAILABLE");
        assertThat(activeActionCount(unavailableBeforeProposal.issueId())).isZero();

        when(documentVectorIndex.isAvailable()).thenReturn(true);
        ReindexHealthContext unavailableBeforeConfirmation = reindexHealthContext("outdated-profile-v0");
        HealthReportActionProposalResponse proposal = proposalService.propose(
                unavailableBeforeConfirmation.reportId(),
                unavailableBeforeConfirmation.issueId(),
                "trace-reindex-unavailable-before-confirmation");
        when(documentVectorIndex.isAvailable()).thenReturn(false);

        ActionRequestResponse failed = actionRequestService.confirm(proposal.actionRequest().id());

        assertThat(failed.status()).isEqualTo(ActionRequestStatus.FAILED);
        assertThat(failed.errorSummary()).contains("向量存储当前不可用");
        assertThat(sourceDocumentMapper.selectById(unavailableBeforeConfirmation.documentId()).getStatus())
                .isEqualTo(DocumentStatus.SUCCEEDED);
        verify(processingCoordinator, never()).submit(unavailableBeforeConfirmation.documentId());
    }

    @Test
    void rechecksProcessingAvailabilityForReindexAtProposalAndConfirmation() {
        ReindexHealthContext disabledBeforeProposal = reindexHealthContext("outdated-profile-v0");
        when(processingCoordinator.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> proposalService.propose(
                disabledBeforeProposal.reportId(),
                disabledBeforeProposal.issueId(),
                "trace-reindex-disabled-before-proposal"))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("DOCUMENT_PROCESSING_DISABLED");
        assertThat(activeActionCount(disabledBeforeProposal.issueId())).isZero();

        when(processingCoordinator.isEnabled()).thenReturn(true);
        ReindexHealthContext disabledBeforeConfirmation = reindexHealthContext("outdated-profile-v0");
        HealthReportActionProposalResponse proposal = proposalService.propose(
                disabledBeforeConfirmation.reportId(),
                disabledBeforeConfirmation.issueId(),
                "trace-reindex-disabled-before-confirmation");
        when(processingCoordinator.isEnabled()).thenReturn(false);

        ActionRequestResponse failed = actionRequestService.confirm(proposal.actionRequest().id());

        assertThat(failed.status()).isEqualTo(ActionRequestStatus.FAILED);
        assertThat(failed.errorSummary()).contains("文档处理当前未启用");
        assertThat(sourceDocumentMapper.selectById(disabledBeforeConfirmation.documentId()).getStatus())
                .isEqualTo(DocumentStatus.SUCCEEDED);
        verify(processingCoordinator, never()).submit(disabledBeforeConfirmation.documentId());
    }

    private HealthContext healthContext(int processingAttempts) {
        KnowledgeBaseResponse knowledgeBase = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Retry proposal " + UUID.randomUUID(), null));
        UUID documentId = insertFailedDocument(knowledgeBase.id(), processingAttempts);
        ChatAnswerResponse health = chatApplicationService.answer(
                new ChatRequest(null, knowledgeBase.id(), "检查这个知识库有没有异常"),
                "trace-health-" + UUID.randomUUID());
        KnowledgeBaseHealthIssueResponse issue = health.healthReport().issues().stream()
                .filter(candidate -> candidate.documentId().equals(documentId))
                .findFirst()
                .orElseThrow();
        return new HealthContext(
                health.conversationId(), health.healthReport().id(), issue.id(), documentId);
    }

    private UUID insertFailedDocument(UUID knowledgeBaseId, int processingAttempts) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SourceDocumentEntity document = new SourceDocumentEntity();
        document.setId(UUID.randomUUID());
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOriginalFilename("failed-" + document.getId() + ".md");
        document.setFileType(DocumentFileType.MARKDOWN);
        document.setMediaType("text/markdown");
        document.setSizeBytes(1);
        document.setStorageKey("health-retry/" + document.getId());
        document.setSha256("0".repeat(64));
        document.setStatus(DocumentStatus.FAILED);
        document.setErrorSummary("文档解析失败");
        document.setProcessingAttempts(processingAttempts);
        document.setCreatedAt(now.minusMinutes(1));
        document.setUpdatedAt(now);
        assertThat(sourceDocumentMapper.insert(document)).isOne();
        return document.getId();
    }

    private ReindexHealthContext reindexHealthContext(String embeddingProfileId) {
        KnowledgeBaseResponse knowledgeBase = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Reindex proposal " + UUID.randomUUID(), null));
        UUID documentId = insertSucceededDocument(knowledgeBase.id(), embeddingProfileId);
        ChatAnswerResponse health = chatApplicationService.answer(
                new ChatRequest(null, knowledgeBase.id(), "检查这个知识库有没有异常"),
                "trace-reindex-health-" + UUID.randomUUID());
        KnowledgeBaseHealthIssueResponse issue = health.healthReport().issues().stream()
                .filter(candidate -> candidate.documentId().equals(documentId))
                .findFirst()
                .orElseThrow();
        return new ReindexHealthContext(
                health.conversationId(),
                knowledgeBase.id(),
                health.healthReport().id(),
                issue.id(),
                documentId,
                embeddingProfileId,
                issue.issueType());
    }

    private UUID insertSucceededDocument(UUID knowledgeBaseId, String embeddingProfileId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SourceDocumentEntity document = new SourceDocumentEntity();
        document.setId(UUID.randomUUID());
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setOriginalFilename("indexed-" + document.getId() + ".md");
        document.setFileType(DocumentFileType.MARKDOWN);
        document.setMediaType("text/markdown");
        document.setSizeBytes(1);
        document.setStorageKey("health-reindex/" + document.getId());
        document.setSha256("0".repeat(64));
        document.setStatus(DocumentStatus.SUCCEEDED);
        document.setProcessingAttempts(1);
        if (embeddingProfileId != null) {
            document.setEmbeddingProfileId(embeddingProfileId);
            document.setEmbeddingProvider("TEST");
            document.setEmbeddingModel("test-embedding");
            document.setEmbeddingDimensions(1024);
            document.setEmbeddingProfileVersion("v1");
            document.setIndexedAt(now.minusMinutes(1));
        }
        document.setCreatedAt(now.minusMinutes(1));
        document.setUpdatedAt(now);
        assertThat(sourceDocumentMapper.insert(document)).isOne();
        return document.getId();
    }

    private void assertReindexProposalAndConfirmation(
            ReindexHealthContext context,
            KnowledgeBaseHealthIssueType expectedIssueType) {
        assertThat(context.issueType()).isEqualTo(expectedIssueType);
        HealthReportActionProposalResponse created = proposalService.propose(
                context.reportId(), context.issueId(), "trace-reindex-proposal-" + UUID.randomUUID());

        assertThat(created.actionRequest().actionType()).isEqualTo(ActionType.REINDEX_DOCUMENT);
        assertThat(created.actionRequest().parameters())
                .isInstanceOfSatisfying(ReindexDocumentActionParameters.class, parameters -> {
                    assertThat(parameters.documentId()).isEqualTo(context.documentId());
                    assertThat(parameters.observedEmbeddingProfileId()).isEqualTo(context.observedProfileId());
                    assertThat(parameters.healthReportId()).isEqualTo(context.reportId());
                    assertThat(parameters.healthIssueId()).isEqualTo(context.issueId());
                });
        assertThat(sourceDocumentMapper.selectById(context.documentId()).getStatus())
                .isEqualTo(DocumentStatus.SUCCEEDED);

        HealthReportActionProposalResponse repeated = proposalService.propose(
                context.reportId(), context.issueId(), "trace-reindex-repeated");
        assertThat(repeated.reusedExistingProposal()).isTrue();
        assertThat(repeated.actionRequest().id()).isEqualTo(created.actionRequest().id());

        ActionRequestResponse confirmed = actionRequestService.confirm(created.actionRequest().id());
        assertThat(confirmed.status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(confirmed.resultSummary()).contains("已提交").contains("最终结果");
        assertThat(sourceDocumentMapper.selectById(context.documentId()).getStatus())
                .isEqualTo(DocumentStatus.PENDING);
        assertThat(actionRequestService.confirm(created.actionRequest().id()).status())
                .isEqualTo(ActionRequestStatus.SUCCEEDED);
    }

    private void insertCurrentProfileVector(ReindexHealthContext context) {
        String metadata = """
                {"knowledge_base_id":"%s","document_id":"%s","embedding_profile_id":"%s"}
                """.formatted(
                context.knowledgeBaseId(),
                context.documentId(),
                embeddingIndexProperties.currentProfile().id()).strip();
        jdbcTemplate.update(
                """
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (?, 'restored vector', CAST(? AS json), array_fill(0.0::real, ARRAY[1024])::vector)
                """,
                UUID.randomUUID(),
                metadata);
    }

    private int activeActionCount(UUID issueId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM action_request WHERE health_issue_id = ? AND deleted = 0",
                Integer.class,
                issueId);
        return count == null ? 0 : count;
    }

    private int proposalMessageCount(UUID conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM chat_message
                WHERE conversation_id = ?
                  AND capability_match_reason = 'HEALTH_REPORT_ISSUE_SELECTED'
                  AND deleted = 0
                """,
                Integer.class,
                conversationId);
        return count == null ? 0 : count;
    }

    private HealthReportActionProposalResponse proposeAfterSignal(
            HealthContext context,
            String traceId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return proposalService.propose(context.reportId(), context.issueId(), traceId);
    }

    private ActionRequestResponse confirmAfterSignal(
            UUID actionRequestId,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return actionRequestService.confirm(actionRequestId);
    }

    private record HealthContext(
            UUID conversationId,
            UUID reportId,
            UUID issueId,
            UUID documentId) {
    }

    private record ReindexHealthContext(
            UUID conversationId,
            UUID knowledgeBaseId,
            UUID reportId,
            UUID issueId,
            UUID documentId,
            String observedProfileId,
            KnowledgeBaseHealthIssueType issueType) {
    }
}
