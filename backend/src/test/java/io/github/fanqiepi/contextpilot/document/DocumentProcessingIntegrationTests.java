package io.github.fanqiepi.contextpilot.document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseCreateRequest;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseResponse;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalSearchRequest;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@ActiveProfiles("offline")
@Testcontainers(disabledWithoutDocker = true)
class DocumentProcessingIntegrationTests {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.2-pg17-bookworm")
            .asCompatibleSubstituteFor("postgres");

    private static final Path STORAGE_ROOT = Path.of(
            "target", "test-uploads", UUID.randomUUID().toString()).toAbsolutePath();

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
        registry.add("contextpilot.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentProcessingService processingService;

    @Autowired
    private PendingDocumentRecoveryService pendingDocumentRecoveryService;

    @Autowired
    private DocumentProcessingProperties processingProperties;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void asynchronouslyIndexesRetrievesIsolatesAndDeletesDocument() {
        KnowledgeBaseResponse primary = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Processing primary", null));
        KnowledgeBaseResponse other = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Processing other", null));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "spring-notes.txt",
                "text/plain",
                "Spring Boot uses dependency injection and an application context."
                        .getBytes(StandardCharsets.UTF_8));

        DocumentResponse uploaded = documentService.upload(primary.id(), file);
        DocumentResponse succeeded = awaitStatus(uploaded.id(), DocumentStatus.SUCCEEDED);

        assertThat(succeeded.errorSummary()).isNull();
        assertThat(succeeded.embeddingIndexCompatibility()).isEqualTo(EmbeddingIndexCompatibility.CURRENT);
        assertThat(succeeded.embeddingIndex()).isNotNull();
        assertThat(succeeded.embeddingIndex().profileId()).isEqualTo("offline_deterministic_1024_v1");
        assertThat(vectorCount(uploaded.id())).isPositive();
        assertThat(vectorProfileCount(uploaded.id(), succeeded.embeddingIndex().profileId()))
                .isEqualTo(vectorCount(uploaded.id()));
        List<RetrievalResultResponse> results = retrievalService.search(
                primary.id(), new RetrievalSearchRequest("Spring dependency injection", 5));
        assertThat(results)
                .isNotEmpty()
                .allMatch(result -> result.documentId().equals(uploaded.id()))
                .anyMatch(result -> result.content().contains("dependency injection"));
        assertThat(retrievalService.search(
                other.id(), new RetrievalSearchRequest("Spring dependency injection", 5))).isEmpty();

        int indexedChunks = vectorCount(uploaded.id());
        processingService.process(uploaded.id());
        assertThat(vectorCount(uploaded.id())).isEqualTo(indexedChunks);

        assertThatThrownBy(() -> documentService.reindex(uploaded.id()))
                .isInstanceOfSatisfying(ConflictException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("DOCUMENT_REINDEX_NOT_REQUIRED"));
        jdbcTemplate.update(
                "UPDATE source_document SET embedding_profile_id = ? WHERE id = ?",
                "offline_deterministic_1024_v0",
                uploaded.id());
        assertThat(documentService.get(uploaded.id()).embeddingIndexCompatibility())
                .isEqualTo(EmbeddingIndexCompatibility.OUTDATED);

        DocumentResponse reindexing = documentService.reindex(uploaded.id());
        assertThat(reindexing.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(reindexing.embeddingIndexCompatibility()).isEqualTo(EmbeddingIndexCompatibility.NOT_INDEXED);
        DocumentResponse reindexed = awaitStatus(uploaded.id(), DocumentStatus.SUCCEEDED);
        assertThat(reindexed.embeddingIndexCompatibility()).isEqualTo(EmbeddingIndexCompatibility.CURRENT);
        assertThat(reindexed.processingAttempts()).isEqualTo(1);
        assertThat(vectorCount(uploaded.id())).isEqualTo(indexedChunks);

        documentService.delete(uploaded.id());

        assertThat(vectorCount(uploaded.id())).isZero();
        assertThatThrownBy(() -> documentService.get(uploaded.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(retrievalService.search(
                primary.id(), new RetrievalSearchRequest("Spring dependency injection", 5))).isEmpty();
    }

    @Test
    void recordsFailureAndAllowsBoundedRetry() {
        KnowledgeBaseResponse knowledgeBase = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Processing failure", null));
        MockMultipartFile invalidPdf = new MockMultipartFile(
                "file",
                "broken.pdf",
                "application/pdf",
                "%PDF-not-a-real-pdf".getBytes(StandardCharsets.US_ASCII));

        DocumentResponse uploaded = documentService.upload(knowledgeBase.id(), invalidPdf);
        DocumentResponse failed = awaitStatus(uploaded.id(), DocumentStatus.FAILED);

        assertThat(failed.errorSummary()).isNotBlank();
        assertThat(vectorCount(uploaded.id())).isZero();

        DocumentResponse retrying = documentService.retry(uploaded.id());
        assertThat(retrying.status()).isEqualTo(DocumentStatus.PENDING);
        DocumentResponse failedAgain = awaitStatus(uploaded.id(), DocumentStatus.FAILED);
        assertThat(failedAgain.errorSummary()).isNotBlank();
        assertThat(vectorCount(uploaded.id())).isZero();
    }

    @Test
    void keepsRejectedWorkPendingAndRecoversItFromPersistedState() {
        KnowledgeBaseResponse knowledgeBase = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("Processing recovery", null));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "recovery-notes.txt",
                "text/plain",
                "Persisted pending work can be dispatched again."
                        .getBytes(StandardCharsets.UTF_8));
        DocumentResponse uploaded = documentService.upload(knowledgeBase.id(), file);
        awaitStatus(uploaded.id(), DocumentStatus.SUCCEEDED);
        jdbcTemplate.update(
                "UPDATE source_document SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                uploaded.id());
        DocumentProcessingCoordinator rejectingCoordinator = new DocumentProcessingCoordinator(
                processingProperties,
                task -> {
                    throw new TaskRejectedException("queue full");
                },
                processingService);

        assertThat(rejectingCoordinator.submit(uploaded.id())).isFalse();
        assertThat(documentService.get(uploaded.id()).status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(pendingDocumentRecoveryService.recoverPendingDocuments()).isPositive();

        assertThat(awaitStatus(uploaded.id(), DocumentStatus.SUCCEEDED).errorSummary()).isNull();
    }

    private DocumentResponse awaitStatus(UUID documentId, DocumentStatus expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        DocumentResponse latest = documentService.get(documentId);
        while (System.nanoTime() < deadline) {
            latest = documentService.get(documentId);
            if (latest.status() == expected) {
                return latest;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for document status", exception);
            }
        }
        throw new AssertionError("Document status was " + latest.status() + " instead of " + expected);
    }

    private int vectorCount(UUID documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = ?",
                Integer.class,
                documentId.toString());
        return count == null ? 0 : count;
    }

    private int vectorProfileCount(UUID documentId, String profileId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM vector_store
                WHERE metadata->>'document_id' = ?
                  AND metadata->>'embedding_profile_id' = ?
                """,
                Integer.class,
                documentId.toString(),
                profileId);
        return count == null ? 0 : count;
    }
}
