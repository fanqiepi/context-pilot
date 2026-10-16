package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.CapabilityMatchReason;
import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageRole;
import io.github.fanqiepi.contextpilot.chat.ChatMessageStatus;
import io.github.fanqiepi.contextpilot.chat.ConversationEntity;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
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
@Transactional
class UserMemoryManagementPersistenceTests {

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
    private UserMemoryService userMemoryService;
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private ConversationMapper conversationMapper;
    @Autowired
    private ChatMessageMapper chatMessageMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void listsDetailsAndKeepsImmutableManagementRevisions() {
        UserMemoryCreationResult created = createMemory("回答尽量简洁");

        UserMemoryResponse listed = userMemoryService.list(UserMemoryStatus.ACTIVE, false).stream()
                .filter(memory -> memory.id().equals(created.memoryId()))
                .findFirst()
                .orElseThrow();
        assertThat(listed.content()).isEqualTo("回答尽量简洁");
        assertThat(listed.sourceType()).isEqualTo(UserMemorySourceType.CHAT_CONFIRMED);

        UserMemoryUpdateRequest request = updateRequest(1);
        request.setContent("  回答使用简洁的中文  ");
        UserMemoryDetailResponse updated = userMemoryService.update(created.memoryId(), request);

        assertThat(updated.content()).isEqualTo("回答使用简洁的中文");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.currentRevision()).isEqualTo(2);
        assertThat(updated.sourceType()).isEqualTo(UserMemorySourceType.MANAGEMENT);
        assertThat(updated.revisions()).extracting(UserMemoryRevisionSummaryResponse::revision)
                .containsExactly(2, 1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT content FROM user_memory_revision WHERE memory_id = ? ORDER BY revision_number",
                String.class,
                created.memoryId())).containsExactly("回答尽量简洁", "回答使用简洁的中文");
    }

    @Test
    void rejectsStaleVersionsAndDuplicateReactivation() {
        UserMemoryCreationResult first = createMemory("优先提供 Java 示例");
        UserMemoryCreationResult second = createMemory("优先提供 TypeScript 示例");

        UserMemoryUpdateRequest disableFirst = updateRequest(1);
        disableFirst.setStatus(UserMemoryStatus.DISABLED);
        UserMemoryDetailResponse disabled = userMemoryService.update(first.memoryId(), disableFirst);

        UserMemoryUpdateRequest stale = updateRequest(1);
        stale.setContent("过期客户端修改");
        assertThatThrownBy(() -> userMemoryService.update(first.memoryId(), stale))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).getCode())
                .isEqualTo("USER_MEMORY_VERSION_CONFLICT");

        UserMemoryUpdateRequest duplicateSecond = updateRequest(1);
        duplicateSecond.setContent("优先提供 Java 示例");
        userMemoryService.update(second.memoryId(), duplicateSecond);

        UserMemoryUpdateRequest enableFirst = updateRequest(disabled.version());
        enableFirst.setStatus(UserMemoryStatus.ACTIVE);
        assertThatThrownBy(() -> userMemoryService.update(first.memoryId(), enableFirst))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).getCode())
                .isEqualTo("USER_MEMORY_DUPLICATE");
    }

    @Test
    void adjustsExpiryFiltersAndDeletesIdempotently() {
        UserMemoryCreationResult created = createMemory("默认使用要点列表");
        UserMemoryUpdateRequest expiry = updateRequest(1);
        expiry.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7));
        UserMemoryDetailResponse updated = userMemoryService.update(created.memoryId(), expiry);
        assertThat(updated.expiresAt()).isNotNull();

        jdbcTemplate.update(
                "UPDATE user_memory SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                created.memoryId());
        assertThat(userMemoryService.list(null, true)).extracting(UserMemoryResponse::id)
                .contains(created.memoryId());
        assertThat(userMemoryService.list(null, false)).extracting(UserMemoryResponse::id)
                .doesNotContain(created.memoryId());

        userMemoryService.delete(created.memoryId());
        userMemoryService.delete(created.memoryId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted FROM user_memory WHERE id = ?", Integer.class, created.memoryId())).isOne();
        assertThatThrownBy(() -> userMemoryService.get(created.memoryId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCreationCannotExceedActiveOrStoredLimit(boolean storedLimit) throws Exception {
        List<UUID> createdIds = new ArrayList<>();
        int capacity = storedLimit ? 100 : 20;
        try {
            for (int index = 0; index < capacity - 1; index++) {
                UUID id = createMemory("容量验证 " + UUID.randomUUID()).memoryId();
                createdIds.add(id);
                if (storedLimit) {
                    UserMemoryUpdateRequest disable = updateRequest(1);
                    disable.setStatus(UserMemoryStatus.DISABLED);
                    userMemoryService.update(id, disable);
                }
            }
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> createAtCapacity(ready, start));
                var second = executor.submit(() -> createAtCapacity(ready, start));
                try {
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                } finally {
                    start.countDown();
                }
                UUID firstId = first.get(20, TimeUnit.SECONDS);
                UUID secondId = second.get(20, TimeUnit.SECONDS);
                if (firstId != null) createdIds.add(firstId);
                if (secondId != null) createdIds.add(secondId);
                assertThat((firstId == null) != (secondId == null)).isTrue();
            }
            assertThat(userMemoryService.list(storedLimit ? null : UserMemoryStatus.ACTIVE, false))
                    .hasSize(capacity);
        } finally {
            // Only this test's confirmed synthetic records are logically deleted.
            createdIds.forEach(userMemoryService::delete);
        }
    }

    private UUID createAtCapacity(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start signal timed out");
        try {
            return createMemory("并发容量验证 " + UUID.randomUUID()).memoryId();
        } catch (ConflictException exception) {
            assertThat(exception.getCode()).isEqualTo("USER_MEMORY_LIMIT_REACHED");
            return null;
        }
    }

    private UserMemoryUpdateRequest updateRequest(long version) {
        UserMemoryUpdateRequest request = new UserMemoryUpdateRequest();
        request.setVersion(version);
        return request;
    }

    private UserMemoryCreationResult createMemory(String content) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID knowledgeBaseId = knowledgeBaseService.create(new KnowledgeBaseCreateRequest(
                "Memory management " + UUID.randomUUID(), null)).id();
        UUID conversationId = UUID.randomUUID();
        ConversationEntity conversation = new ConversationEntity();
        conversation.setId(conversationId);
        conversation.setKnowledgeBaseId(knowledgeBaseId);
        conversation.setTitle("Manage memory");
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        assertThat(conversationMapper.insert(conversation)).isOne();

        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversationId);
        message.setRole(ChatMessageRole.USER);
        message.setContent("请记住我的偏好：" + content);
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setTraceId("trace-memory-management");
        message.setCapabilityId(CapabilityId.BUSINESS_ACTION);
        message.setCapabilityVersion("v2");
        message.setCapabilityMatchReason(CapabilityMatchReason.EXPLICIT_CREATE_USER_MEMORY);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        assertThat(chatMessageMapper.insert(message)).isOne();
        return userMemoryService.createConfirmedResponsePreference(
                content, null, conversationId, message.getId());
    }
}
