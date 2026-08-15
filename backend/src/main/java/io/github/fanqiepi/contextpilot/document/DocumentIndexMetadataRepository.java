package io.github.fanqiepi.contextpilot.document;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentIndexMetadataRepository {

    private static final String CURRENT_PROFILE_VECTOR_COUNT_SQL = """
            SELECT COUNT(*)
            FROM vector_store
            WHERE metadata ->> 'knowledge_base_id' = ?
              AND metadata ->> 'document_id' = ?
              AND metadata ->> 'embedding_profile_id' = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public DocumentIndexMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countCurrentProfileVectors(
            UUID knowledgeBaseId,
            UUID documentId,
            String currentEmbeddingProfileId) {
        Objects.requireNonNull(knowledgeBaseId, "Knowledge base id must not be null");
        Objects.requireNonNull(documentId, "Document id must not be null");
        if (currentEmbeddingProfileId == null || currentEmbeddingProfileId.isBlank()) {
            throw new IllegalArgumentException("Current embedding profile id must not be blank");
        }
        Long count = jdbcTemplate.queryForObject(
                CURRENT_PROFILE_VECTOR_COUNT_SQL,
                Long.class,
                knowledgeBaseId.toString(),
                documentId.toString(),
                currentEmbeddingProfileId);
        return Objects.requireNonNull(count, "Document vector count must not be null");
    }
}
