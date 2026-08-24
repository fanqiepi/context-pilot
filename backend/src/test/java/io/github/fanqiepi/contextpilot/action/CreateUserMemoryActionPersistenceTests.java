package io.github.fanqiepi.contextpilot.action;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import io.github.fanqiepi.contextpilot.memory.UserMemoryContent;
import io.github.fanqiepi.contextpilot.memory.UserMemoryScope;
import io.github.fanqiepi.contextpilot.memory.UserMemoryType;
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

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.vectorstore.type=none"
})
@Testcontainers(disabledWithoutDocker = true)
class CreateUserMemoryActionPersistenceTests {

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
    private ActionRequestService actionRequestService;
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private ConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void unconfirmedRejectedAndExpiredProposalsHaveZeroMemorySideEffects() {
        String unconfirmedContent = uniqueContent("未确认");
        ActionRequestResponse unconfirmed = proposal(unconfirmedContent);
        assertThat(memoryCount(unconfirmedContent)).isZero();
        assertThat(revisionCount(unconfirmedContent)).isZero();
        assertThat(actionRequestService.get(unconfirmed.id()).resultResourceId()).isNull();

        String rejectedContent = uniqueContent("已拒绝");
        ActionRequestResponse rejected = actionRequestService.reject(proposal(rejectedContent).id());
        assertThat(actionRequestService.confirm(rejected.id()).status())
                .isEqualTo(ActionRequestStatus.REJECTED);
        assertThat(memoryCount(rejectedContent)).isZero();

        String expiredContent = uniqueContent("已过期");
        ActionRequestResponse expired = proposal(expiredContent);
        jdbcTemplate.update(
                """
                UPDATE action_request
                SET created_at = created_at - INTERVAL '1 hour',
                    expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                expired.id());
        assertThat(actionRequestService.confirm(expired.id()).status())
                .isEqualTo(ActionRequestStatus.EXPIRED);
        assertThat(memoryCount(expiredContent)).isZero();
    }

    @Test
    void repeatedAndConcurrentConfirmationCreatesOneMemoryAndPinsResultResource() throws Exception {
        String content = uniqueContent("并发确认");
        ActionRequestResponse proposal = proposal(content);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ActionRequestResponse first;
        ActionRequestResponse second;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ActionRequestResponse> firstFuture = executor.submit(() -> confirmAfterSignal(
                    proposal.id(), ready, start));
            Future<ActionRequestResponse> secondFuture = executor.submit(() -> confirmAfterSignal(
                    proposal.id(), ready, start));
            ready.await();
            start.countDown();
            first = firstFuture.get();
            second = secondFuture.get();
        }

        assertThat(first.status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(second.status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(first.resultResourceId()).isNotNull().isEqualTo(second.resultResourceId());
        ActionRequestResponse repeated = actionRequestService.confirm(proposal.id());
        assertThat(repeated.resultResourceId()).isEqualTo(first.resultResourceId());
        assertThat(memoryCount(content)).isOne();
        assertThat(revisionCount(content)).isOne();
    }

    @Test
    void distinctConfirmedProposalsReuseTheSameNormalizedActivePreference() {
        String content = uniqueContent("精确去重");
        ActionRequestResponse first = actionRequestService.confirm(proposal(content).id());
        ActionRequestResponse duplicate = actionRequestService.confirm(
                proposal("  " + content + "  ").id());

        assertThat(first.status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(duplicate.status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(duplicate.resultResourceId()).isEqualTo(first.resultResourceId());
        assertThat(duplicate.resultSummary()).contains("已存在");
        assertThat(memoryCount(content)).isOne();
        assertThat(revisionCount(content)).isOne();
    }

    private ActionRequestResponse confirmAfterSignal(
            UUID id,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return actionRequestService.confirm(id);
    }

    private ActionRequestResponse proposal(String content) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseResponse workspace = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Memory action workspace " + UUID.randomUUID(), null));
        UUID conversationId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setKnowledgeBaseId(workspace.id());
        conversation.setTitle("Create explicit memory");
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        assertThat(conversationMapper.insert(conversation)).isOne();

        UUID userMessageId = insertMessage(
                conversationId, ChatMessageRole.USER, "请记住我的偏好：" + content, now);
        UUID assistantMessageId = insertMessage(
                conversationId, ChatMessageRole.ASSISTANT, "已生成回答偏好提案。", now);
        return actionRequestService.proposeCreateUserMemory(
                conversationId,
                userMessageId,
                assistantMessageId,
                "v2",
                "trace-memory-action-" + UUID.randomUUID(),
                new CreateUserMemoryActionParameters(
                        UserMemoryType.RESPONSE_PREFERENCE,
                        UserMemoryScope.GLOBAL,
                        content,
                        null));
    }

    private UUID insertMessage(
            UUID conversationId,
            ChatMessageRole role,
            String content,
            OffsetDateTime now) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setTraceId("trace-memory-action-message");
        message.setCapabilityId(CapabilityId.BUSINESS_ACTION);
        message.setCapabilityVersion("v2");
        message.setCapabilityMatchReason(CapabilityMatchReason.EXPLICIT_CREATE_USER_MEMORY);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        assertThat(chatMessageMapper.insert(message)).isOne();
        return message.getId();
    }

    private int memoryCount(String content) {
        return count("""
                SELECT COUNT(*) FROM user_memory
                WHERE content_hash = ? AND deleted = 0
                """, UserMemoryContent.hash(UserMemoryContent.normalize(content)));
    }

    private int revisionCount(String content) {
        return count("""
                SELECT COUNT(*) FROM user_memory_revision
                WHERE content_hash = ? AND deleted = 0
                """, UserMemoryContent.hash(UserMemoryContent.normalize(content)));
    }

    private int count(String sql, String contentHash) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, contentHash);
        return count == null ? 0 : count;
    }

    private String uniqueContent(String prefix) {
        return prefix + "偏好 " + UUID.randomUUID();
    }
}
