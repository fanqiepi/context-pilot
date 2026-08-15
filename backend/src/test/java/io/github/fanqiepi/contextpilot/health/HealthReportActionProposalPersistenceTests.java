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
import io.github.fanqiepi.contextpilot.action.RetryDocumentProcessingActionParameters;
import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.CapabilityMatchReason;
import io.github.fanqiepi.contextpilot.chat.ChatAnswerResponse;
import io.github.fanqiepi.contextpilot.chat.ChatApplicationService;
import io.github.fanqiepi.contextpilot.chat.ChatRequest;
import io.github.fanqiepi.contextpilot.chat.ConversationHistoryService;
import io.github.fanqiepi.contextpilot.chat.ConversationMessageResponse;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.document.DocumentProcessingCoordinator;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
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

    @MockitoBean
    private DocumentProcessingCoordinator processingCoordinator;

    @BeforeEach
    void configureProcessingCoordinator() {
        reset(processingCoordinator);
        when(processingCoordinator.isEnabled()).thenReturn(true);
        when(processingCoordinator.canRetry(anyInt())).thenAnswer(invocation ->
                invocation.<Integer>getArgument(0) < 3);
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
        document.setFileType(io.github.fanqiepi.contextpilot.document.DocumentFileType.MARKDOWN);
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
}
