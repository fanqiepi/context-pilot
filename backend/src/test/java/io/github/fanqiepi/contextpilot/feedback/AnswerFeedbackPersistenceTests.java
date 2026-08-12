package io.github.fanqiepi.contextpilot.feedback;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageRole;
import io.github.fanqiepi.contextpilot.chat.ChatMessageStatus;
import io.github.fanqiepi.contextpilot.chat.ConversationEntity;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseEntity;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseMapper;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseStatus;
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
class AnswerFeedbackPersistenceTests {

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
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Autowired
    private ConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private AnswerFeedbackMapper answerFeedbackMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void marksIdempotentlyAndReactivatesLogicallyDeletedFeedback() {
        UUID messageId = createCompletedAssistantMessage();

        assertThat(answerFeedbackMapper.markHelpful(feedback(messageId))).isOne();
        AnswerFeedbackEntity initial = answerFeedbackMapper.selectOne(
                Wrappers.<AnswerFeedbackEntity>lambdaQuery()
                        .eq(AnswerFeedbackEntity::getMessageId, messageId));
        assertThat(initial).isNotNull();

        assertThat(answerFeedbackMapper.markHelpful(feedback(messageId))).isOne();
        assertThat(rowCount(messageId)).isOne();
        assertThat(answerFeedbackMapper.deleteById(initial.getId())).isOne();
        assertThat(answerFeedbackMapper.selectById(initial.getId())).isNull();

        assertThat(answerFeedbackMapper.markHelpful(feedback(messageId))).isOne();

        AnswerFeedbackEntity restored = answerFeedbackMapper.selectById(initial.getId());
        assertThat(restored).isNotNull();
        assertThat(restored.getMessageId()).isEqualTo(messageId);
        assertThat(rowCount(messageId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM answer_feedback WHERE message_id = ?",
                Integer.class,
                messageId)).isZero();
    }

    private UUID createCompletedAssistantMessage() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID knowledgeBaseId = UUID.randomUUID();
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
        knowledgeBase.setId(knowledgeBaseId);
        knowledgeBase.setName("Feedback persistence " + knowledgeBaseId);
        knowledgeBase.setStatus(KnowledgeBaseStatus.ACTIVE);
        knowledgeBase.setCreatedAt(now);
        knowledgeBase.setUpdatedAt(now);
        assertThat(knowledgeBaseMapper.insert(knowledgeBase)).isOne();

        UUID conversationId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setKnowledgeBaseId(knowledgeBaseId);
        conversation.setTitle("Feedback persistence");
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        assertThat(conversationMapper.insert(conversation)).isOne();

        UUID messageId = UUID.randomUUID();
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(messageId);
        message.setConversationId(conversationId);
        message.setRole(ChatMessageRole.ASSISTANT);
        message.setContent("Helpful answer");
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setTraceId("trace-persistence");
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        assertThat(chatMessageMapper.insert(message)).isOne();
        return messageId;
    }

    private AnswerFeedbackEntity feedback(UUID messageId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AnswerFeedbackEntity entity = new AnswerFeedbackEntity();
        entity.setId(UUID.randomUUID());
        entity.setMessageId(messageId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private int rowCount(UUID messageId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM answer_feedback WHERE message_id = ?",
                Integer.class,
                messageId);
        return count == null ? 0 : count;
    }
}
