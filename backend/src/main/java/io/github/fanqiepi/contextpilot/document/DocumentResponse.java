package io.github.fanqiepi.contextpilot.document;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        UUID knowledgeBaseId,
        String originalFilename,
        DocumentFileType fileType,
        String mediaType,
        long sizeBytes,
        String sha256,
        DocumentStatus status,
        String errorSummary,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static DocumentResponse from(SourceDocumentEntity entity) {
        return new DocumentResponse(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getOriginalFilename(),
                entity.getFileType(),
                entity.getMediaType(),
                entity.getSizeBytes(),
                entity.getSha256(),
                entity.getStatus(),
                entity.getErrorSummary(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
