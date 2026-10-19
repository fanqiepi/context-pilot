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
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import io.github.fanqiepi.contextpilot.identity.LocalSingleUserSubjectProvider;
import io.github.fanqiepi.contextpilot.identity.UserSubject;
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
class UserMemoryPersistenceTests {

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
    private CurrentSubjectProvider currentSubjectProvider;
    @Autowired
    private UserMemoryService userMemoryService;
    @Autowired
    private UserMemoryMapper userMemoryMapper;
    @Autowired
    private UserMemoryRevisionMapper revisionMapper;
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private ConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsTheSeedOwnerAggregateAndImmutableInitialRevision() {
        UserSubject subject = currentSubjectProvider.currentSubject();
        Source source = createSourceMessage();

        UserMemoryCreationResult result = userMemoryService.createConfirmedResponsePreference(
                "  代码示例优先使用   Java ",
                null,
                source.conversationId(),
                source.messageId());

        assertThat(subject.key()).isEqualTo(LocalSingleUserSubjectProvider.LOCAL_OWNER_KEY);
        assertThat(subject.id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertThat(result.created()).isTrue();
        assertThat(result.revision()).isOne();
        assertThat(result.content()).isEqualTo("代码示例优先使用 Java");

        UserMemoryEntity memory = userMemoryMapper.selectOwnedById(subject.id(), result.memoryId());
        assertThat(memory).isNotNull();
        assertThat(memory.getOwnerUserId()).isEqualTo(subject.id());
        assertThat(memory.getMemoryType()).isEqualTo(UserMemoryType.RESPONSE_PREFERENCE);
        assertThat(memory.getScopeType()).isEqualTo(UserMemoryScope.GLOBAL);
        assertThat(memory.getStatus()).isEqualTo(UserMemoryStatus.ACTIVE);
        assertThat(userMemoryMapper.selectOwnedById(UUID.randomUUID(), result.memoryId())).isNull();

        UserMemoryRevisionEntity revision = revisionMapper.selectOwnedRevision(
                subject.id(), result.memoryId(), 1);
        assertThat(revision.getContent()).isEqualTo("代码示例优先使用 Java");
        assertThat(revision.getSourceType()).isEqualTo(UserMemorySourceType.CHAT_CONFIRMED);
        assertThat(revision.getSourceConversationId()).isEqualTo(source.conversationId());
        assertThat(revision.getSourceMessageId()).isEqualTo(source.messageId());
        assertThat(revisionMapper.selectOwnedRevision(UUID.randomUUID(), result.memoryId(), 1)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_memory_revision WHERE memory_id = ?",
                Integer.class,
                result.memoryId())).isOne();
    }

    @Test
    void exactNormalizedDuplicateReusesTheOwnedActiveAggregate() {
        Source firstSource = createSourceMessage();
        Source secondSource = createSourceMessage();
        UserMemoryCreationResult first = userMemoryService.createConfirmedResponsePreference(
                "回答尽量简洁", null, firstSource.conversationId(), firstSource.messageId());

        UserMemoryCreationResult duplicate = userMemoryService.createConfirmedResponsePreference(
                "  回答尽量简洁  ", null, secondSource.conversationId(), secondSource.messageId());

        assertThat(duplicate.memoryId()).isEqualTo(first.memoryId());
        assertThat(duplicate.created()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_memory WHERE content_hash = ? AND deleted = 0",
                Integer.class,
                UserMemoryContent.hash("回答尽量简洁"))).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_memory_revision WHERE memory_id = ?",
                Integer.class,
                first.memoryId())).isOne();
    }

    private Source createSourceMessage() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseResponse workspace = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Memory workspace " + UUID.randomUUID(), null));
        UUID conversationId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setKnowledgeBaseId(workspace.id());
        conversation.setTitle("Explicit memory");
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        assertThat(conversationMapper.insert(conversation)).isOne();

        UUID messageId = UUID.randomUUID();
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(messageId);
        message.setConversationId(conversationId);
        message.setRole(ChatMessageRole.USER);
        message.setContent("请记住我的偏好：回答尽量简洁");
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setTraceId("trace-memory-persistence");
        message.setCapabilityId(CapabilityId.BUSINESS_ACTION);
        message.setCapabilityVersion("v1");
        message.setCapabilityMatchReason(CapabilityMatchReason.EXPLICIT_CREATE_KNOWLEDGE_BASE);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        assertThat(chatMessageMapper.insert(message)).isOne();
        return new Source(conversationId, messageId);
    }

    private record Source(UUID conversationId, UUID messageId) {
    }
}
