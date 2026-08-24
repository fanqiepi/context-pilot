package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.CapabilityMatchReason;
import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageRole;
import io.github.fanqiepi.contextpilot.chat.ChatMessageStatus;
import io.github.fanqiepi.contextpilot.chat.ConversationEntity;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
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
    @Autowired private JdbcTemplate jdbcTemplate;

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
