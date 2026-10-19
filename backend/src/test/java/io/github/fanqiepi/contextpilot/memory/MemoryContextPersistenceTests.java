package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.CapabilityMatchReason;
import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageRole;
import io.github.fanqiepi.contextpilot.chat.ChatMessageStatus;
import io.github.fanqiepi.contextpilot.chat.ConversationEntity;
import io.github.fanqiepi.contextpilot.chat.ConversationHistoryService;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
import io.github.fanqiepi.contextpilot.chat.ConversationMessageResponse;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseResponse;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.vectorstore.type=none"
})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class MemoryContextPersistenceTests {

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

    @Autowired private MemoryContextPersistenceService contextPersistenceService;
    @Autowired private UserMemoryService userMemoryService;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private ConversationMapper conversationMapper;
    @Autowired private ChatMessageMapper chatMessageMapper;
    @Autowired private MessageMemoryContextMapper messageMemoryContextMapper;
    @Autowired private ConversationHistoryService conversationHistoryService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private io.github.fanqiepi.contextpilot.chat.ChatPersistenceService chatPersistenceService;

    @Test
    void preservesUnknownHistoryAndPersistsExplicitRefusalDisposition() {
        Exchange historical = createExchange();
        assertThat(chatMessageMapper.selectById(historical.assistantMessageId()).getRefused()).isNull();
        for (boolean refused : new boolean[]{false, true}) {
            Exchange exchange = createExchange();
            UUID call = chatPersistenceService.beginModelCall(exchange.assistantMessageId(),
                    "TEST", "fixture", "v3", "trace-refusal");
            chatPersistenceService.completeSuccess(exchange.assistantMessageId(), call, "Solstice fixture",
                    List.of(), new io.github.fanqiepi.contextpilot.model.ChatModelResult(
                            "Solstice fixture", "fixture", 1, 1, 2, refused), 1);
            assertThat(chatMessageMapper.selectById(exchange.assistantMessageId()).getRefused()).isEqualTo(refused);
            assertThat(conversationHistoryService.messages(exchange.conversationId()).stream()
                    .filter(message -> message.id().equals(exchange.assistantMessageId())).findFirst().orElseThrow().refused())
                    .isEqualTo(refused);
        }
        Exchange deterministic = createExchange();
        chatPersistenceService.completeRefusalWithoutModel(deterministic.assistantMessageId(), "Insufficient evidence");
        assertThat(chatMessageMapper.selectById(deterministic.assistantMessageId()).getRefused()).isTrue();
        assertThat(chatMessageMapper.selectById(historical.assistantMessageId()).getRefused()).isNull();
    }

    @Test
    void recordsOnlyTheStableActiveContextWithinBothBudgets() {
        Exchange exchange = createExchange();
        for (int index = 0; index < 12; index++) {
            userMemoryService.createConfirmedResponsePreference(
                    "偏好-%02d-%s".formatted(index, "字".repeat(210)),
                    null,
                    exchange.conversationId(),
                    exchange.userMessageId());
        }

        MemoryContext context = contextPersistenceService.loadAndRecord(exchange.assistantMessageId());

        assertThat(context.preferences()).hasSize(9);
        assertThat(context.characterCount()).isLessThanOrEqualTo(2000);
        assertThat(context.truncated()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message_memory_context WHERE assistant_message_id = ? AND deleted = 0",
                Integer.class,
                exchange.assistantMessageId())).isEqualTo(context.preferences().size());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT MIN(context_character_count) FROM message_memory_context WHERE assistant_message_id = ?",
                Integer.class,
                exchange.assistantMessageId())).isEqualTo(context.characterCount());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT BOOL_AND(context_truncated) FROM message_memory_context WHERE assistant_message_id = ?",
                Boolean.class,
                exchange.assistantMessageId())).isTrue();
        List<MessageMemoryContextHistoryRow> rows = messageMemoryContextMapper
                .selectHistoryByAssistantMessageIds(List.of(exchange.assistantMessageId()));
        assertThat(rows).hasSize(9);
        assertThat(rows).extracting(MessageMemoryContextHistoryRow::getRevisionNumber)
                .containsOnly(1);
        ConversationMessageResponse historyMessage = conversationHistoryService
                .messages(exchange.conversationId()).stream()
                .filter(message -> message.id().equals(exchange.assistantMessageId()))
                .findFirst()
                .orElseThrow();
        assertThat(historyMessage.memoryContext()).isNotNull();
        assertThat(historyMessage.memoryContext().count()).isEqualTo(9);
        assertThat(historyMessage.memoryContext().characterCount()).isEqualTo(context.characterCount());
        assertThat(historyMessage.memoryContext().truncated()).isTrue();

        UUID deletedMemoryId = context.preferences().getFirst().memoryId();
        userMemoryService.delete(deletedMemoryId);
        List<MessageMemoryContextHistoryRow> afterDeletion = messageMemoryContextMapper
                .selectHistoryByAssistantMessageIds(List.of(exchange.assistantMessageId()));
        assertThat(afterDeletion).filteredOn(row -> row.getMemoryId().equals(deletedMemoryId))
                .singleElement()
                .satisfies(row -> assertThat(row.getContent()).isNull());
        ConversationMessageResponse deletedHistoryMessage = conversationHistoryService
                .messages(exchange.conversationId()).stream()
                .filter(message -> message.id().equals(exchange.assistantMessageId()))
                .findFirst()
                .orElseThrow();
        assertThat(deletedHistoryMessage.memoryContext().items())
                .filteredOn(item -> item.memoryId().equals(deletedMemoryId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.contentHidden()).isTrue();
                    assertThat(item.summary()).isEqualTo("该偏好已删除");
                });
    }

    @Test
    void newSessionsUseCurrentRevisionsWhileHistoryKeepsTheActualVersion() {
        Exchange first = createExchange();
        var created = userMemoryService.createConfirmedResponsePreference(
                "优先使用英文回答", null, first.conversationId(), first.userMessageId());
        MemoryContext original = contextPersistenceService.loadAndRecord(first.assistantMessageId());
        assertThat(original.preferences()).singleElement()
                .satisfies(item -> assertThat(item.revision()).isEqualTo(1));

        UserMemoryUpdateRequest correction = new UserMemoryUpdateRequest();
        correction.setVersion(1L);
        correction.setContent("优先使用中文回答");
        userMemoryService.update(created.memoryId(), correction);
        MemoryContext corrected = contextPersistenceService.loadAndRecord(createExchange().assistantMessageId());
        assertThat(corrected.preferences()).singleElement().satisfies(item -> {
            assertThat(item.revision()).isEqualTo(2);
            assertThat(item.content()).isEqualTo("优先使用中文回答");
        });
        assertThat(messageMemoryContextMapper.selectHistoryByAssistantMessageIds(
                List.of(first.assistantMessageId()))).singleElement().satisfies(row -> {
                    assertThat(row.getRevisionNumber()).isEqualTo(1);
                    assertThat(row.getContent()).isEqualTo("优先使用英文回答");
                });

        UserMemoryUpdateRequest disable = new UserMemoryUpdateRequest();
        disable.setVersion(2L);
        disable.setStatus(UserMemoryStatus.DISABLED);
        userMemoryService.update(created.memoryId(), disable);
        assertThat(contextPersistenceService.loadAndRecord(createExchange().assistantMessageId()).isEmpty()).isTrue();

        UserMemoryUpdateRequest enable = new UserMemoryUpdateRequest();
        enable.setVersion(3L);
        enable.setStatus(UserMemoryStatus.ACTIVE);
        userMemoryService.update(created.memoryId(), enable);
        jdbcTemplate.update("UPDATE user_memory SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                created.memoryId());
        assertThat(contextPersistenceService.loadAndRecord(createExchange().assistantMessageId()).isEmpty()).isTrue();
        assertThat(userMemoryService.get(created.memoryId()).currentRevision()).isEqualTo(4);
        userMemoryService.delete(created.memoryId());
        assertThat(contextPersistenceService.loadAndRecord(createExchange().assistantMessageId()).isEmpty()).isTrue();
        assertThat(messageMemoryContextMapper.selectHistoryByAssistantMessageIds(
                List.of(first.assistantMessageId()))).singleElement().satisfies(row -> {
                    assertThat(row.getRevisionNumber()).isEqualTo(1);
                    assertThat(row.getContent()).isNull();
                });
    }

    private Exchange createExchange() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseResponse workspace = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Memory context " + UUID.randomUUID(), null));
        UUID conversationId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setKnowledgeBaseId(workspace.id());
        conversation.setTitle("Memory context");
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        assertThat(conversationMapper.insert(conversation)).isOne();

        UUID userMessageId = insertMessage(
                conversationId, ChatMessageRole.USER, ChatMessageStatus.COMPLETED, now);
        UUID assistantMessageId = insertMessage(
                conversationId, ChatMessageRole.ASSISTANT, ChatMessageStatus.PENDING, now);
        return new Exchange(conversationId, userMessageId, assistantMessageId);
    }

    private UUID insertMessage(
            UUID conversationId,
            ChatMessageRole role,
            ChatMessageStatus status,
            OffsetDateTime now) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(role == ChatMessageRole.USER ? "问题" : "");
        message.setStatus(status);
        message.setTraceId("trace-memory-context");
        message.setCapabilityId(CapabilityId.KNOWLEDGE_QA);
        message.setCapabilityVersion("v1");
        message.setCapabilityMatchReason(CapabilityMatchReason.DEFAULT_KNOWLEDGE_QA);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        assertThat(chatMessageMapper.insert(message)).isOne();
        return message.getId();
    }

    private record Exchange(UUID conversationId, UUID userMessageId, UUID assistantMessageId) {
    }
}
