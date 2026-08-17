package io.github.fanqiepi.contextpilot.research;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.ChatRequest;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "spring.ai.vectorstore.type=none",
        "contextpilot.document.processing.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ResearchStartPersistenceTests {
    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17-bookworm")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PGVECTOR_IMAGE)
            .withDatabaseName("context_pilot")
            .withUsername("context_pilot")
            .withPassword("context_pilot_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ResearchStartService startService;
    @Autowired private ResearchRunCommandService commandService;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private WebApplicationContext applicationContext;

    @BeforeEach
    void cleanResearchData() {
        jdbcTemplate.update("DELETE FROM research_step_evidence");
        jdbcTemplate.update("DELETE FROM message_citation");
        jdbcTemplate.update("DELETE FROM research_evidence");
        jdbcTemplate.update("DELETE FROM research_step");
        jdbcTemplate.update("DELETE FROM research_run");
        jdbcTemplate.update("DELETE FROM model_call");
        jdbcTemplate.update("DELETE FROM chat_message");
        jdbcTemplate.update("DELETE FROM conversation");
        jdbcTemplate.update("DELETE FROM vector_store");
        jdbcTemplate.update("DELETE FROM source_document");
        jdbcTemplate.update("DELETE FROM knowledge_base");
    }

    @Test
    void atomicallyCreatesAndIdempotentlyReusesAValidatedFixedPlan() {
        UUID knowledgeBaseId = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("research", "test")).id();
        List<UUID> documents = List.of(document(knowledgeBaseId, "a.md"), document(knowledgeBaseId, "b.md"));
        UUID clientRequestId = UUID.randomUUID();
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, "比较部署方式和安全机制",
                new ResearchRequest(clientRequestId, ResearchTaskType.DOCUMENT_COMPARISON, documents));

        ResearchStart first = startService.start(request, "research-trace");
        ResearchStart duplicate = startService.start(request, "research-trace-duplicate");

        assertThat(first.reusedExisting()).isFalse();
        assertThat(duplicate.reusedExisting()).isTrue();
        assertThat(duplicate.run().id()).isEqualTo(first.run().id());
        assertThat(first.run().steps()).hasSize(2)
                .allSatisfy(step -> assertThat(step.documentIds()).isEqualTo(documents));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM research_run", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM research_step", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM chat_message", Integer.class)).isEqualTo(2);

        ChatRequest conflict = new ChatRequest(null, knowledgeBaseId, "不同问题",
                new ResearchRequest(clientRequestId, ResearchTaskType.DOCUMENT_COMPARISON, documents));
        assertThatThrownBy(() -> startService.start(conflict, "trace"))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("RESEARCH_REQUEST_ID_CONFLICT");
    }

    @Test
    void cancellationIsAtomicAndIdempotent() {
        UUID knowledgeBaseId = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("cancel", null)).id();
        List<UUID> documents = List.of(document(knowledgeBaseId, "a.md"), document(knowledgeBaseId, "b.md"));
        ResearchStart start = startService.start(
                new ChatRequest(null, knowledgeBaseId, "比较安全机制",
                        new ResearchRequest(UUID.randomUUID(), ResearchTaskType.DOCUMENT_COMPARISON, documents)),
                "cancel-trace");

        ResearchRunResponse first = commandService.cancel(start.run().id());
        ResearchRunResponse duplicate = commandService.cancel(start.run().id());

        assertThat(first.executionStatus()).isEqualTo(ResearchExecutionStatus.CANCELLED);
        assertThat(duplicate.executionStatus()).isEqualTo(ResearchExecutionStatus.CANCELLED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM chat_message WHERE id = ?",
                String.class, start.exchange().assistantMessageId())).isEqualTo("CANCELLED");
    }

    @Test
    void publishesFormalResearchRunApiInOpenApi() throws Exception {
        var mockMvc = webAppContextSetup(applicationContext).build();

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/research-runs/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/research-runs/{id}/cancel']").exists())
                .andExpect(jsonPath("$.components.schemas.ResearchRequest").exists())
                .andExpect(jsonPath("$.components.schemas.ResearchRunResponse").exists());
    }

    private UUID document(UUID knowledgeBaseId, String filename) {
        UUID documentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO source_document (
                    id, knowledge_base_id, original_filename, file_type, media_type,
                    size_bytes, storage_key, sha256, status, processing_attempts,
                    embedding_profile_id, embedding_provider, embedding_model,
                    embedding_dimensions, embedding_profile_version, indexed_at,
                    created_at, updated_at, deleted
                ) VALUES (?, ?, ?, 'MARKDOWN', 'text/markdown', 10, ?, ?, 'SUCCEEDED', 1,
                    'dashscope_qwen3_7_1024_v1', 'DASHSCOPE', 'qwen3.7-text-embedding',
                    1024, 'v1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, documentId, knowledgeBaseId, filename, "research/" + documentId,
                "a".repeat(64));
        String metadata = """
                {"document_id":"%s","knowledge_base_id":"%s",\
                "embedding_profile_id":"dashscope_qwen3_7_1024_v1","original_filename":"%s",\
                "chunk_index":0}
                """.formatted(documentId, knowledgeBaseId, filename).replace("\\\n", "");
        jdbcTemplate.update(
                "INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?, ?, CAST(? AS json), CAST(? AS vector))",
                UUID.randomUUID(), filename + " evidence", metadata, zeroVector());
        return documentId;
    }

    private String zeroVector() {
        return "[" + String.join(",", java.util.Collections.nCopies(1024, "0")) + "]";
    }
}
