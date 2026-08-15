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
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseResponse;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
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
class ActionRequestPersistenceTests {

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
    void repeatedAndConcurrentConfirmationsCreateKnowledgeBaseOnlyOnce() throws Exception {
        String name = "Concurrent action " + UUID.randomUUID();
        ActionRequestResponse proposal = proposal(name);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ActionRequestResponse> first = executor.submit(() -> confirmAfterSignal(
                    proposal.id(), ready, start));
            Future<ActionRequestResponse> second = executor.submit(() -> confirmAfterSignal(
                    proposal.id(), ready, start));
            ready.await();
            start.countDown();

            assertThat(first.get().status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
            assertThat(second.get().status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
        }

        assertThat(actionRequestService.confirm(proposal.id()).status())
                .isEqualTo(ActionRequestStatus.SUCCEEDED);
        assertThat(activeKnowledgeBaseCount(name)).isOne();
    }

    @Test
    void rejectedAndExpiredProposalsNeverExecute() {
        String rejectedName = "Rejected action " + UUID.randomUUID();
        ActionRequestResponse rejected = actionRequestService.reject(proposal(rejectedName).id());
        assertThat(rejected.status()).isEqualTo(ActionRequestStatus.REJECTED);
        assertThat(actionRequestService.confirm(rejected.id()).status())
                .isEqualTo(ActionRequestStatus.REJECTED);
        assertThat(activeKnowledgeBaseCount(rejectedName)).isZero();

        String expiredName = "Expired action " + UUID.randomUUID();
        ActionRequestResponse expired = proposal(expiredName);
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
        assertThat(activeKnowledgeBaseCount(expiredName)).isZero();
    }

    @Test
    void nameConflictRollsBackExecutionAndPersistsSafeFailure() {
        String name = "Conflict action " + UUID.randomUUID();
        KnowledgeBaseResponse existing = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest(name, null));

        ActionRequestResponse failed = actionRequestService.confirm(proposal(name).id());

        assertThat(failed.status()).isEqualTo(ActionRequestStatus.FAILED);
        assertThat(failed.errorSummary()).contains(name).doesNotContain("org.postgresql");
        assertThat(activeKnowledgeBaseCount(name)).isOne();
        assertThat(knowledgeBaseService.get(existing.id()).name()).isEqualTo(name);
    }

    @Test
    void preservesV2ParametersWhileUsingTheGeneralizedActionSchema() {
        String name = "Compatible action " + UUID.randomUUID();

        ActionRequestResponse proposal = proposal(name);
        ActionRequestResponse restored = actionRequestService.get(proposal.id());

        assertThat(restored.actionType()).isEqualTo(ActionType.CREATE_KNOWLEDGE_BASE);
        assertThat(restored.parameters())
                .isInstanceOfSatisfying(CreateKnowledgeBaseActionParameters.class, parameters -> {
                    assertThat(parameters.name()).isEqualTo(name);
                    assertThat(parameters.description()).isNull();
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT parameters::text FROM action_request WHERE id = ?",
                String.class,
                proposal.id()))
                .contains("\"name\": \"" + name + "\"")
                .doesNotContain("description", "actionType");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT target_document_id IS NULL AND health_issue_id IS NULL
                FROM action_request
                WHERE id = ?
                """,
                Boolean.class,
                proposal.id())).isTrue();
    }

    private ActionRequestResponse confirmAfterSignal(
            UUID id,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return actionRequestService.confirm(id);
    }

    private ActionRequestResponse proposal(String name) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseResponse workspace = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Workspace " + UUID.randomUUID(), null));
        UUID conversationId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setKnowledgeBaseId(workspace.id());
        conversation.setTitle("Create " + name);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        assertThat(conversationMapper.insert(conversation)).isOne();

        UUID userMessageId = insertMessage(
                conversationId, ChatMessageRole.USER, "创建知识库：" + name, now);
        UUID assistantMessageId = insertMessage(
                conversationId, ChatMessageRole.ASSISTANT, "已生成创建知识库提案。", now);
        return actionRequestService.proposeCreateKnowledgeBase(
                conversationId,
                userMessageId,
                assistantMessageId,
                CapabilityId.BUSINESS_ACTION,
                "v1",
                "trace-" + UUID.randomUUID(),
                new CreateKnowledgeBaseActionParameters(name, null));
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
        message.setTraceId("trace-persistence");
        message.setCapabilityId(CapabilityId.BUSINESS_ACTION);
        message.setCapabilityVersion("v1");
        message.setCapabilityMatchReason(CapabilityMatchReason.EXPLICIT_CREATE_KNOWLEDGE_BASE);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        assertThat(chatMessageMapper.insert(message)).isOne();
        return message.getId();
    }

    private int activeKnowledgeBaseCount(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE lower(name) = lower(?) AND deleted = 0",
                Integer.class,
                name);
        return count == null ? 0 : count;
    }
}
