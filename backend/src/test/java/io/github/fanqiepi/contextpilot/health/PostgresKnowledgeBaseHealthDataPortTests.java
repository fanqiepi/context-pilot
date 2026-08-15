package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@ActiveProfiles("offline")
@Testcontainers(disabledWithoutDocker = true)
class PostgresKnowledgeBaseHealthDataPortTests {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17-bookworm")
            .asCompatibleSubstituteFor("postgres");

    private static final String CURRENT_PROFILE = "offline_deterministic_1024_v1";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PGVECTOR_IMAGE)
            .withDatabaseName("context_pilot")
            .withUsername("context_pilot")
            .withPassword("context_pilot_test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private KnowledgeBaseHealthDataPort dataPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearBusinessData() {
        jdbcTemplate.update("DELETE FROM vector_store");
        jdbcTemplate.update("DELETE FROM source_document");
        jdbcTemplate.update("DELETE FROM knowledge_base");
    }

    @Test
    void readsAllCountsAndOnlyIssuesFromTheRequestedKnowledgeBase() {
        UUID knowledgeBaseId = insertKnowledgeBase("Health primary");
        UUID otherKnowledgeBaseId = insertKnowledgeBase("Health other");
        insertDocument(knowledgeBaseId, DocumentStatus.PENDING, 0, null, false);
        UUID failedId = insertDocument(knowledgeBaseId, DocumentStatus.FAILED, 1, null, false);
        UUID unknownId = insertDocument(knowledgeBaseId, DocumentStatus.SUCCEEDED, 1, null, false);
        UUID outdatedId = insertDocument(
                knowledgeBaseId, DocumentStatus.SUCCEEDED, 1, "offline_deterministic_1024_v0", false);
        UUID missingId = insertDocument(
                knowledgeBaseId, DocumentStatus.SUCCEEDED, 1, CURRENT_PROFILE, false);
        UUID healthyId = insertDocument(
                knowledgeBaseId, DocumentStatus.SUCCEEDED, 1, CURRENT_PROFILE, false);
        insertVector(knowledgeBaseId, healthyId, CURRENT_PROFILE);
        insertDocument(knowledgeBaseId, DocumentStatus.FAILED, 1, null, true);
        insertDocument(otherKnowledgeBaseId, DocumentStatus.FAILED, 1, null, false);

        KnowledgeBaseHealthFacts facts = dataPort.inspect(knowledgeBaseId, CURRENT_PROFILE, 10);

        assertThat(facts.vectorCheckComplete()).isTrue();
        assertThat(facts.documentCounts()).isEqualTo(new DocumentStatusCounts(6, 1, 0, 4, 1, 0));
        assertThat(facts.issueCount()).isEqualTo(4);
        assertThat(facts.issueCandidates())
                .extracting(KnowledgeBaseHealthDocumentFact::documentId)
                .containsExactly(failedId, unknownId, outdatedId, missingId)
                .doesNotContain(healthyId);
        assertThat(facts.issueCandidates().getLast().currentProfileVectorCount()).isZero();
        assertThat(facts.dataAsOf()).isBeforeOrEqualTo(OffsetDateTime.now().plusSeconds(1));
    }

    @Test
    void keepsTheExactIssueCountWhenDetailsAreLimited() {
        UUID knowledgeBaseId = insertKnowledgeBase("Health limited");
        insertDocument(knowledgeBaseId, DocumentStatus.FAILED, 1, null, false);
        insertDocument(knowledgeBaseId, DocumentStatus.FAILED, 1, null, false);
        insertDocument(knowledgeBaseId, DocumentStatus.FAILED, 1, null, false);

        KnowledgeBaseHealthFacts facts = dataPort.inspect(knowledgeBaseId, CURRENT_PROFILE, 2);

        assertThat(facts.issueCount()).isEqualTo(3);
        assertThat(facts.issueCandidates()).hasSize(2);
    }

    private UUID insertKnowledgeBase(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO knowledge_base (id, name, status, deleted) VALUES (?, ?, 'ACTIVE', 0)",
                id,
                name);
        return id;
    }

    private UUID insertDocument(
            UUID knowledgeBaseId,
            DocumentStatus status,
            int processingAttempts,
            String embeddingProfileId,
            boolean deleted) {
        UUID id = UUID.randomUUID();
        String filename = id + ".txt";
        String storageKey = "health-tests/" + id;
        OffsetDateTime updatedAt = OffsetDateTime.now().minusMinutes(1);
        if (embeddingProfileId == null) {
            jdbcTemplate.update(
                    """
                    INSERT INTO source_document (
                        id, knowledge_base_id, original_filename, file_type, media_type,
                        size_bytes, storage_key, sha256, status, error_summary,
                        processing_attempts, created_at, updated_at, deleted
                    ) VALUES (?, ?, ?, 'TXT', 'text/plain', 1, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    knowledgeBaseId,
                    filename,
                    storageKey,
                    "0".repeat(64),
                    status.name(),
                    status == DocumentStatus.FAILED ? "文档处理失败。" : null,
                    processingAttempts,
                    updatedAt,
                    updatedAt,
                    deleted ? 1 : 0);
        } else {
            jdbcTemplate.update(
                    """
                    INSERT INTO source_document (
                        id, knowledge_base_id, original_filename, file_type, media_type,
                        size_bytes, storage_key, sha256, status, processing_attempts,
                        embedding_profile_id, embedding_provider, embedding_model,
                        embedding_dimensions, embedding_profile_version, indexed_at,
                        created_at, updated_at, deleted
                    ) VALUES (?, ?, ?, 'TXT', 'text/plain', 1, ?, ?, ?, ?, ?,
                              'OFFLINE', 'deterministic', 1024, 'v1', ?, ?, ?, ?)
                    """,
                    id,
                    knowledgeBaseId,
                    filename,
                    storageKey,
                    "0".repeat(64),
                    status.name(),
                    processingAttempts,
                    embeddingProfileId,
                    updatedAt,
                    updatedAt,
                    updatedAt,
                    deleted ? 1 : 0);
        }
        return id;
    }

    private void insertVector(UUID knowledgeBaseId, UUID documentId, String embeddingProfileId) {
        String metadata = """
                {"knowledge_base_id":"%s","document_id":"%s","embedding_profile_id":"%s"}
                """.formatted(knowledgeBaseId, documentId, embeddingProfileId).strip();
        jdbcTemplate.update(
                """
                INSERT INTO vector_store (id, content, metadata, embedding)
                VALUES (?, 'health test chunk', CAST(? AS json), array_fill(0.0::real, ARRAY[1024])::vector)
                """,
                UUID.randomUUID(),
                metadata);
    }
}
