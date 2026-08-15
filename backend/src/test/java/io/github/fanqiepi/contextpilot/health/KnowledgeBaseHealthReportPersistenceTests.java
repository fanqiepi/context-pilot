package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.CapabilityMatchReason;
import io.github.fanqiepi.contextpilot.chat.ChatAnswerResponse;
import io.github.fanqiepi.contextpilot.chat.ChatApplicationService;
import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageRole;
import io.github.fanqiepi.contextpilot.chat.ChatMessageStatus;
import io.github.fanqiepi.contextpilot.chat.ChatRequest;
import io.github.fanqiepi.contextpilot.chat.ConversationEntity;
import io.github.fanqiepi.contextpilot.chat.ConversationHistoryService;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
import io.github.fanqiepi.contextpilot.chat.ConversationMessageResponse;
import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProfile;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseResponse;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.vectorstore.type=none"
})
@Testcontainers(disabledWithoutDocker = true)
class KnowledgeBaseHealthReportPersistenceTests {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17-bookworm")
            .asCompatibleSubstituteFor("postgres");
    private static final EmbeddingIndexProfile CURRENT_PROFILE = new EmbeddingIndexProfile(
            "dashscope_qwen3_7_1024_v1", "DASHSCOPE", "qwen3.7-text-embedding", 1024, "v1");

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
    private KnowledgeBaseHealthReportService reportService;
    @Autowired
    private ChatApplicationService chatApplicationService;
    @Autowired
    private ConversationHistoryService historyService;
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private ConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsHealthReportThroughDeterministicChatAndRestoresIt() {
        KnowledgeBaseResponse knowledgeBase = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Health chat " + UUID.randomUUID(), null));

        ChatAnswerResponse response = chatApplicationService.answer(
                new ChatRequest(null, knowledgeBase.id(), "检查这个知识库有没有异常"),
                "trace-health-chat");

        assertThat(response.capabilityId()).isEqualTo(CapabilityId.KNOWLEDGE_QA);
        assertThat(response.capabilityVersion()).isEqualTo("v2");
        assertThat(response.capabilityMatchReason())
                .isEqualTo(CapabilityMatchReason.EXPLICIT_KNOWLEDGE_BASE_HEALTH);
        assertThat(response.model()).isNull();
        assertThat(response.usage()).isNull();
        assertThat(response.citations()).isEmpty();
        assertThat(response.healthReport()).isNotNull();
        assertThat(response.healthReport().healthStatus()).isEqualTo(KnowledgeBaseHealthStatus.EMPTY);
        assertThat(response.answer()).isEqualTo(response.healthReport().summary());

        KnowledgeBaseHealthReportResponse queried = reportService.get(response.healthReport().id());
        assertThat(queried.traceId()).isEqualTo("trace-health-chat");
        assertThat(queried.assistantMessageId()).isEqualTo(response.assistantMessageId());
        List<ConversationMessageResponse> history = historyService.messages(response.conversationId());
        assertThat(history.getLast().healthReport()).isNotNull();
        assertThat(history.getLast().healthReport().id()).isEqualTo(queried.id());
    }

    @Test
    void persistsImmutableSnapshotAndRestoresItWithConversationHistory() {
        OffsetDateTime dataAsOf = OffsetDateTime.parse("2026-08-15T03:00:00Z");
        MessageContext context = messageContext();
        UUID failedDocumentId = insertDocument(
                context.knowledgeBaseId(), "failed.txt", DocumentStatus.FAILED, 3, "解析失败", null, dataAsOf);
        UUID missingVectorDocumentId = insertDocument(
                context.knowledgeBaseId(), "missing.md", DocumentStatus.SUCCEEDED, 1, null,
                CURRENT_PROFILE.id(), dataAsOf.plusSeconds(1));
        KnowledgeBaseHealthAssessment assessment = assessment(
                context.knowledgeBaseId(), failedDocumentId, missingVectorDocumentId, dataAsOf);

        KnowledgeBaseHealthReportResponse created = reportService.create(
                context.conversationId(), context.userMessageId(), context.assistantMessageId(), assessment);

        assertThat(created.capabilityId()).isEqualTo(CapabilityId.KNOWLEDGE_QA);
        assertThat(created.healthStatus()).isEqualTo(KnowledgeBaseHealthStatus.ATTENTION_REQUIRED);
        assertThat(created.issueCount()).isEqualTo(2);
        assertThat(created.issues())
                .extracting(KnowledgeBaseHealthIssueResponse::issueType)
                .containsExactly(
                        KnowledgeBaseHealthIssueType.DOCUMENT_PROCESSING_FAILED,
                        KnowledgeBaseHealthIssueType.VECTOR_INDEX_MISSING);
        ChatMessageEntity completedMessage = chatMessageMapper.selectById(context.assistantMessageId());
        assertThat(completedMessage.getStatus()).isEqualTo(ChatMessageStatus.COMPLETED);
        assertThat(completedMessage.getContent()).isEqualTo(assessment.summary());

        jdbcTemplate.update(
                "UPDATE source_document SET original_filename = 'renamed.txt', error_summary = NULL WHERE id = ?",
                failedDocumentId);

        KnowledgeBaseHealthReportResponse restored = reportService.get(created.id());
        assertThat(restored.dataAsOf()).isEqualTo(dataAsOf);
        assertThat(restored.issues().getFirst().originalFilename()).isEqualTo("failed.txt");
        assertThat(restored.issues().getFirst().observedErrorSummary()).isEqualTo("解析失败");
        Map<UUID, KnowledgeBaseHealthReportResponse> byMessage = reportService.findByAssistantMessageIds(
                List.of(context.userMessageId(), context.assistantMessageId()));
        assertThat(byMessage).containsOnlyKeys(context.assistantMessageId());
        assertThat(byMessage.get(context.assistantMessageId()).id()).isEqualTo(created.id());

        List<ConversationMessageResponse> history = historyService.messages(context.conversationId());
        assertThat(history).hasSize(2);
        assertThat(history.getFirst().healthReport()).isNull();
        assertThat(history.getLast().healthReport()).isNotNull();
        assertThat(history.getLast().healthReport().id()).isEqualTo(created.id());
        assertThat(history.getLast().healthReport().issues().getFirst().originalFilename()).isEqualTo("failed.txt");

        assertThatThrownBy(() -> reportService.create(
                context.conversationId(), context.userMessageId(), context.assistantMessageId(), assessment))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("KNOWLEDGE_BASE_HEALTH_REPORT_ALREADY_EXISTS");
    }

    @Test
    void rejectsMessagesOutsideTheAssessmentKnowledgeBase() {
        MessageContext context = messageContext();
        UUID otherKnowledgeBaseId = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Other " + UUID.randomUUID(), null)).id();
        KnowledgeBaseHealthAssessment assessment = new KnowledgeBaseHealthAssessment(
                otherKnowledgeBaseId,
                KnowledgeBaseHealthStatus.EMPTY,
                HealthCheckCompleteness.COMPLETE,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                CURRENT_PROFILE,
                new DocumentStatusCounts(0, 0, 0, 0, 0, 0),
                0,
                0,
                "知识库中没有活动文档。",
                List.of());

        assertThatThrownBy(() -> reportService.create(
                context.conversationId(), context.userMessageId(), context.assistantMessageId(), assessment))
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .isEqualTo("HEALTH_REPORT_CONTEXT_MISMATCH");
        assertThat(chatMessageMapper.selectById(context.assistantMessageId()).getStatus())
                .isEqualTo(ChatMessageStatus.PENDING);
    }

    private MessageContext messageContext() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseResponse knowledgeBase = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Health report " + UUID.randomUUID(), null));
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(UUID.randomUUID());
        conversation.setKnowledgeBaseId(knowledgeBase.id());
        conversation.setTitle("检查知识库健康状态");
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        assertThat(conversationMapper.insert(conversation)).isOne();

        String traceId = "trace-" + UUID.randomUUID();
        UUID userMessageId = insertMessage(
                conversation.getId(), ChatMessageRole.USER, ChatMessageStatus.COMPLETED,
                "检查知识库健康状态", traceId, now);
        UUID assistantMessageId = insertMessage(
                conversation.getId(), ChatMessageRole.ASSISTANT, ChatMessageStatus.PENDING,
                "", traceId, now.plusNanos(1));
        return new MessageContext(knowledgeBase.id(), conversation.getId(), userMessageId, assistantMessageId);
    }

    private UUID insertMessage(
            UUID conversationId,
            ChatMessageRole role,
            ChatMessageStatus status,
            String content,
            String traceId,
            OffsetDateTime createdAt) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setStatus(status);
        message.setTraceId(traceId);
        message.setCapabilityId(CapabilityId.KNOWLEDGE_QA);
        message.setCapabilityVersion("v1");
        message.setCapabilityMatchReason(CapabilityMatchReason.DEFAULT_KNOWLEDGE_QA);
        message.setCreatedAt(createdAt);
        message.setUpdatedAt(createdAt);
        assertThat(chatMessageMapper.insert(message)).isOne();
        return message.getId();
    }

    private UUID insertDocument(
            UUID knowledgeBaseId,
            String filename,
            DocumentStatus status,
            int processingAttempts,
            String errorSummary,
            String embeddingProfileId,
            OffsetDateTime updatedAt) {
        UUID documentId = UUID.randomUUID();
        boolean indexed = embeddingProfileId != null;
        assertThat(jdbcTemplate.update(
                """
                INSERT INTO source_document (
                    id, knowledge_base_id, original_filename, file_type, media_type, size_bytes,
                    storage_key, sha256, status, error_summary, processing_attempts,
                    embedding_profile_id, embedding_provider, embedding_model, embedding_dimensions,
                    embedding_profile_version, indexed_at, created_at, updated_at
                ) VALUES (?, ?, ?, 'TXT', 'text/plain', 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                documentId,
                knowledgeBaseId,
                filename,
                "health/" + documentId,
                "0".repeat(64),
                status.name(),
                errorSummary,
                processingAttempts,
                embeddingProfileId,
                indexed ? CURRENT_PROFILE.provider() : null,
                indexed ? CURRENT_PROFILE.model() : null,
                indexed ? CURRENT_PROFILE.dimensions() : null,
                indexed ? CURRENT_PROFILE.version() : null,
                indexed ? updatedAt : null,
                updatedAt.minusMinutes(1),
                updatedAt)).isOne();
        return documentId;
    }

    private KnowledgeBaseHealthAssessment assessment(
            UUID knowledgeBaseId,
            UUID failedDocumentId,
            UUID missingVectorDocumentId,
            OffsetDateTime dataAsOf) {
        return new KnowledgeBaseHealthAssessment(
                knowledgeBaseId,
                KnowledgeBaseHealthStatus.ATTENTION_REQUIRED,
                HealthCheckCompleteness.COMPLETE,
                null,
                dataAsOf,
                CURRENT_PROFILE,
                new DocumentStatusCounts(2, 0, 0, 1, 1, 0),
                2,
                2,
                "知识库发现 2 个需要关注的问题，已返回 2 条明细。",
                List.of(
                        new KnowledgeBaseHealthIssue(
                                failedDocumentId,
                                "failed.txt",
                                KnowledgeBaseHealthIssueType.DOCUMENT_PROCESSING_FAILED,
                                HealthIssueSeverity.ERROR,
                                DocumentStatus.FAILED,
                                3,
                                "解析失败",
                                null,
                                null,
                                dataAsOf,
                                HealthRecommendedActionType.RETRY_DOCUMENT_PROCESSING,
                                false,
                                HealthIneligibilityReasonCode.DOCUMENT_RETRY_LIMIT_REACHED,
                                "文档已达到最大处理次数。"),
                        new KnowledgeBaseHealthIssue(
                                missingVectorDocumentId,
                                "missing.md",
                                KnowledgeBaseHealthIssueType.VECTOR_INDEX_MISSING,
                                HealthIssueSeverity.WARNING,
                                DocumentStatus.SUCCEEDED,
                                1,
                                null,
                                CURRENT_PROFILE.id(),
                                0L,
                                dataAsOf.plusSeconds(1),
                                HealthRecommendedActionType.REINDEX_DOCUMENT,
                                true,
                                null,
                                null)));
    }

    private record MessageContext(
            UUID knowledgeBaseId,
            UUID conversationId,
            UUID userMessageId,
            UUID assistantMessageId) {
    }
}
