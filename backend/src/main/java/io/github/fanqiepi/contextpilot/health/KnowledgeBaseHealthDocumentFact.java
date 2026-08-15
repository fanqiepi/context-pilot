package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;

public record KnowledgeBaseHealthDocumentFact(
        UUID documentId,
        String originalFilename,
        DocumentStatus documentStatus,
        int processingAttempts,
        String errorSummary,
        String embeddingProfileId,
        Long currentProfileVectorCount,
        OffsetDateTime sourceDocumentUpdatedAt) {

    public KnowledgeBaseHealthDocumentFact {
        Objects.requireNonNull(documentId, "Document id must not be null");
        Objects.requireNonNull(originalFilename, "Original filename must not be null");
        Objects.requireNonNull(documentStatus, "Document status must not be null");
        Objects.requireNonNull(sourceDocumentUpdatedAt, "Source document update time must not be null");
        if (processingAttempts < 0) {
            throw new IllegalArgumentException("Document processing attempts must not be negative");
        }
        if (currentProfileVectorCount != null && currentProfileVectorCount < 0) {
            throw new IllegalArgumentException("Vector count must not be negative");
        }
    }
}
