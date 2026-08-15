package io.github.fanqiepi.contextpilot.health;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresKnowledgeBaseHealthDataPort implements KnowledgeBaseHealthDataPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(PostgresKnowledgeBaseHealthDataPort.class);

    private static final String DOCUMENT_COUNTS_SQL = """
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE status = 'PENDING') AS pending,
                   COUNT(*) FILTER (WHERE status = 'PROCESSING') AS processing,
                   COUNT(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
                   COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                   COUNT(*) FILTER (WHERE status = 'DELETING') AS deleting
            FROM source_document
            WHERE knowledge_base_id = ? AND deleted = 0
            """;

    private static final String VECTOR_CANDIDATE_CTE = """
            WITH vector_counts AS (
                SELECT metadata ->> 'document_id' AS document_id, COUNT(*) AS vector_count
                FROM vector_store
                WHERE metadata ->> 'knowledge_base_id' = ?
                  AND metadata ->> 'embedding_profile_id' = ?
                GROUP BY metadata ->> 'document_id'
            ), issue_candidates AS (
                SELECT document.id AS document_id,
                       document.original_filename,
                       document.status,
                       document.processing_attempts,
                       document.error_summary,
                       document.embedding_profile_id,
                       COALESCE(vector_counts.vector_count, 0) AS current_profile_vector_count,
                       document.updated_at
                FROM source_document document
                LEFT JOIN vector_counts ON vector_counts.document_id = document.id::text
                WHERE document.knowledge_base_id = ?
                  AND document.deleted = 0
                  AND (
                      document.status = 'FAILED'
                      OR (
                          document.status = 'SUCCEEDED'
                          AND (
                              document.embedding_profile_id IS NULL
                              OR document.embedding_profile_id <> ?
                              OR (document.embedding_profile_id = ? AND COALESCE(vector_counts.vector_count, 0) = 0)
                          )
                      )
                  )
            )
            """;

    private static final String FALLBACK_CANDIDATE_CTE = """
            WITH issue_candidates AS (
                SELECT document.id AS document_id,
                       document.original_filename,
                       document.status,
                       document.processing_attempts,
                       document.error_summary,
                       document.embedding_profile_id,
                       NULL::BIGINT AS current_profile_vector_count,
                       document.updated_at
                FROM source_document document
                WHERE document.knowledge_base_id = ?
                  AND document.deleted = 0
                  AND (
                      document.status = 'FAILED'
                      OR (
                          document.status = 'SUCCEEDED'
                          AND (
                              document.embedding_profile_id IS NULL
                              OR document.embedding_profile_id <> ?
                          )
                      )
                  )
            )
            """;

    private static final String CANDIDATE_COUNT_SUFFIX = "SELECT COUNT(*) FROM issue_candidates";

    private static final String CANDIDATE_LIST_SUFFIX = """
            SELECT document_id, original_filename, status, processing_attempts, error_summary,
                   embedding_profile_id, current_profile_vector_count, updated_at
            FROM issue_candidates
            ORDER BY CASE WHEN status = 'FAILED' THEN 0
                          WHEN embedding_profile_id IS NULL THEN 1
                          WHEN embedding_profile_id <> ? THEN 2
                          ELSE 3 END,
                     updated_at,
                     document_id
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public PostgresKnowledgeBaseHealthDataPort(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    @Transactional(readOnly = true, timeout = 5, isolation = Isolation.REPEATABLE_READ)
    public KnowledgeBaseHealthFacts inspect(
            UUID knowledgeBaseId,
            String currentEmbeddingProfileId,
            int issueLimit) {
        Objects.requireNonNull(knowledgeBaseId, "Knowledge base id must not be null");
        if (currentEmbeddingProfileId == null || currentEmbeddingProfileId.isBlank()) {
            throw new IllegalArgumentException("Current embedding profile id must not be blank");
        }
        if (issueLimit <= 0 || issueLimit > KnowledgeBaseHealthProperties.HARD_MAXIMUM_ISSUES) {
            throw new IllegalArgumentException(
                    "Health issue limit must be between 1 and "
                            + KnowledgeBaseHealthProperties.HARD_MAXIMUM_ISSUES);
        }

        OffsetDateTime dataAsOf = jdbcTemplate.queryForObject(
                "SELECT CURRENT_TIMESTAMP", OffsetDateTime.class);
        DocumentStatusCounts counts = jdbcTemplate.queryForObject(
                DOCUMENT_COUNTS_SQL,
                (resultSet, rowNumber) -> mapCounts(resultSet),
                knowledgeBaseId);

        VectorInspection vectorInspection = inspectWithVectorCounts(
                knowledgeBaseId, currentEmbeddingProfileId, issueLimit);
        return new KnowledgeBaseHealthFacts(
                Objects.requireNonNull(dataAsOf),
                Objects.requireNonNull(counts),
                vectorInspection.issueCount(),
                vectorInspection.complete(),
                vectorInspection.issueCandidates());
    }

    private VectorInspection inspectWithVectorCounts(
            UUID knowledgeBaseId,
            String currentEmbeddingProfileId,
            int issueLimit) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        Savepoint savepoint;
        try {
            savepoint = connection.setSavepoint();
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException("Could not create health inspection savepoint", exception);
        }

        try {
            long issueCount = requiredCount(jdbcTemplate.queryForObject(
                    VECTOR_CANDIDATE_CTE + CANDIDATE_COUNT_SUFFIX,
                    Long.class,
                    knowledgeBaseId.toString(),
                    currentEmbeddingProfileId,
                    knowledgeBaseId,
                    currentEmbeddingProfileId,
                    currentEmbeddingProfileId));
            List<KnowledgeBaseHealthDocumentFact> candidates = jdbcTemplate.query(
                    VECTOR_CANDIDATE_CTE + CANDIDATE_LIST_SUFFIX,
                    this::mapDocumentFact,
                    knowledgeBaseId.toString(),
                    currentEmbeddingProfileId,
                    knowledgeBaseId,
                    currentEmbeddingProfileId,
                    currentEmbeddingProfileId,
                    currentEmbeddingProfileId,
                    issueLimit);
            releaseSavepoint(connection, savepoint);
            return new VectorInspection(issueCount, true, candidates);
        } catch (DataAccessException exception) {
            rollbackToSavepoint(connection, savepoint);
            LOGGER.warn(
                    "Vector existence inspection is unavailable for knowledge base {}; returning partial health facts",
                    knowledgeBaseId);
            return inspectWithoutVectorCounts(knowledgeBaseId, currentEmbeddingProfileId, issueLimit);
        }
    }

    private VectorInspection inspectWithoutVectorCounts(
            UUID knowledgeBaseId,
            String currentEmbeddingProfileId,
            int issueLimit) {
        long issueCount = requiredCount(jdbcTemplate.queryForObject(
                FALLBACK_CANDIDATE_CTE + CANDIDATE_COUNT_SUFFIX,
                Long.class,
                knowledgeBaseId,
                currentEmbeddingProfileId));
        List<KnowledgeBaseHealthDocumentFact> candidates = jdbcTemplate.query(
                FALLBACK_CANDIDATE_CTE + CANDIDATE_LIST_SUFFIX,
                this::mapDocumentFact,
                knowledgeBaseId,
                currentEmbeddingProfileId,
                currentEmbeddingProfileId,
                issueLimit);
        return new VectorInspection(issueCount, false, candidates);
    }

    private DocumentStatusCounts mapCounts(ResultSet resultSet) throws SQLException {
        return new DocumentStatusCounts(
                resultSet.getLong("total"),
                resultSet.getLong("pending"),
                resultSet.getLong("processing"),
                resultSet.getLong("succeeded"),
                resultSet.getLong("failed"),
                resultSet.getLong("deleting"));
    }

    private KnowledgeBaseHealthDocumentFact mapDocumentFact(ResultSet resultSet, int rowNumber) throws SQLException {
        return new KnowledgeBaseHealthDocumentFact(
                resultSet.getObject("document_id", UUID.class),
                resultSet.getString("original_filename"),
                DocumentStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("processing_attempts"),
                resultSet.getString("error_summary"),
                resultSet.getString("embedding_profile_id"),
                resultSet.getObject("current_profile_vector_count", Long.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    private long requiredCount(Long count) {
        return Objects.requireNonNull(count, "Health issue count must not be null");
    }

    private void rollbackToSavepoint(Connection connection, Savepoint savepoint) {
        try {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException("Could not recover partial health inspection", exception);
        }
    }

    private void releaseSavepoint(Connection connection, Savepoint savepoint) {
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException("Could not release health inspection savepoint", exception);
        }
    }

    private record VectorInspection(
            long issueCount,
            boolean complete,
            List<KnowledgeBaseHealthDocumentFact> issueCandidates) {
    }
}
