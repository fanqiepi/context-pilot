package io.github.fanqiepi.contextpilot.action;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.CapabilityMatchReason;
import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageRole;
import io.github.fanqiepi.contextpilot.chat.ChatMessageStatus;
import io.github.fanqiepi.contextpilot.chat.ConversationEntity;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
import io.github.fanqiepi.contextpilot.evaluation.V2EvaluationAssets;
import io.github.fanqiepi.contextpilot.evaluation.V2EvaluationConfig;
import io.github.fanqiepi.contextpilot.evaluation.V2EvaluationDataset;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseResponse;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
class V2ActionSafetyEvaluationTests {

    private static final V2EvaluationDataset DATASET = V2EvaluationAssets.dataset();
    private static final V2EvaluationConfig CONFIG = V2EvaluationAssets.config();
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
    private ActionRequestMapper actionRequestMapper;
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private ConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    static Stream<V2EvaluationDataset.LifecycleCase> lifecycleCases() {
        return DATASET.lifecycleCases().stream()
                .filter(testCase -> !"INTERNAL_FAILURE".equals(testCase.scenario()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lifecycleCases")
    void evaluatesPersistedActionSafety(V2EvaluationDataset.LifecycleCase testCase)
            throws Exception {
        String name = "Eval " + testCase.id() + " " + UUID.randomUUID();
        if ("EXECUTION_FAILURE".equals(testCase.scenario())) {
            knowledgeBaseService.create(new KnowledgeBaseCreateRequest(name, null));
        }
        String traceId = "eval-trace-" + testCase.id() + "-" + UUID.randomUUID();
        ActionRequestResponse proposal = proposal(name, traceId);

        ActionRequestResponse result = switch (testCase.scenario()) {
            case "UNCONFIRMED" -> actionRequestService.get(proposal.id());
            case "REJECTED" -> {
                ActionRequestResponse rejected = actionRequestService.reject(proposal.id());
                yield actionRequestService.confirm(rejected.id());
            }
            case "EXPIRED" -> {
                jdbcTemplate.update(
                        """
                        UPDATE action_request
                        SET created_at = created_at - INTERVAL '1 hour',
                            expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = ?
                        """,
                        proposal.id());
                yield actionRequestService.confirm(proposal.id());
            }
            case "REPEATED_CONFIRMATION" -> {
                ActionRequestResponse first = actionRequestService.confirm(proposal.id());
                ActionRequestResponse second = actionRequestService.confirm(proposal.id());
                assertThat(first.status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
                yield second;
            }
            case "CONCURRENT_CONFIRMATION" -> concurrentConfirm(proposal.id());
            case "EXECUTION_FAILURE" -> actionRequestService.confirm(proposal.id());
            case "HISTORY_RECOVERY" -> {
                actionRequestService.confirm(proposal.id());
                Map<UUID, ActionRequestResponse> restored =
                        actionRequestService.findByAssistantMessageIds(
                                java.util.List.of(proposal.assistantMessageId()));
                assertThat(restored).containsKey(proposal.assistantMessageId());
                yield restored.get(proposal.assistantMessageId());
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported lifecycle scenario " + testCase.scenario());
        };

        assertThat(result.status().name()).isEqualTo(testCase.expectedStatus());
        assertThat(activeKnowledgeBaseCount(name))
                .isEqualTo(testCase.expectedActiveKnowledgeBases());
        assertAudit(proposal.id(), traceId);
        if (testCase.expectSafeErrorSummary()) {
            assertThat(result.errorSummary())
                    .isNotBlank()
                    .doesNotContain("org.postgresql", "SQLException", "INSERT INTO", "jdbc:");
        }
    }

    private ActionRequestResponse concurrentConfirm(UUID id) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ActionRequestResponse> first = executor.submit(() ->
                    confirmAfterSignal(id, ready, start));
            Future<ActionRequestResponse> second = executor.submit(() ->
                    confirmAfterSignal(id, ready, start));
            ready.await();
            start.countDown();
            assertThat(first.get().status()).isEqualTo(ActionRequestStatus.SUCCEEDED);
            return second.get();
        }
    }

    private ActionRequestResponse confirmAfterSignal(
            UUID id,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return actionRequestService.confirm(id);
    }

    private ActionRequestResponse proposal(String name, String traceId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseResponse workspace = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Eval workspace " + UUID.randomUUID(), null));
        UUID conversationId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setKnowledgeBaseId(workspace.id());
        conversation.setTitle("Create " + name);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        assertThat(conversationMapper.insert(conversation)).isOne();

        UUID userMessageId = insertMessage(
                conversationId, ChatMessageRole.USER, "创建知识库：" + name, traceId, now);
        UUID assistantMessageId = insertMessage(
                conversationId, ChatMessageRole.ASSISTANT, "已生成创建知识库提案。", traceId, now);
        return actionRequestService.proposeCreateKnowledgeBase(
                conversationId,
                userMessageId,
                assistantMessageId,
                CapabilityId.BUSINESS_ACTION,
                CONFIG.capabilityVersion(),
                traceId,
                new CreateKnowledgeBaseActionParameters(name, null));
    }

    private UUID insertMessage(
            UUID conversationId,
            ChatMessageRole role,
            String content,
            String traceId,
            OffsetDateTime now) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setTraceId(traceId);
        message.setCapabilityId(CapabilityId.BUSINESS_ACTION);
        message.setCapabilityVersion(CONFIG.capabilityVersion());
        message.setCapabilityMatchReason(CapabilityMatchReason.EXPLICIT_CREATE_KNOWLEDGE_BASE);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        assertThat(chatMessageMapper.insert(message)).isOne();
        return message.getId();
    }

    private void assertAudit(UUID id, String traceId) {
        ActionRequestEntity persisted = actionRequestMapper.selectById(id);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getCapabilityId()).isEqualTo(CapabilityId.BUSINESS_ACTION);
        assertThat(persisted.getCapabilityVersion()).isEqualTo(CONFIG.capabilityVersion());
        assertThat(persisted.getActionType().name()).isEqualTo(CONFIG.actionType());
        assertThat(persisted.getTraceId()).isEqualTo(traceId);
    }

    private int activeKnowledgeBaseCount(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE lower(name) = lower(?) AND deleted = 0",
                Integer.class,
                name);
        return count == null ? 0 : count;
    }
}
