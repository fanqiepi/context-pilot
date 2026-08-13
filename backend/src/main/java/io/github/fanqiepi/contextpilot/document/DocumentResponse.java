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
        int processingAttempts,
        EmbeddingIndexResponse embeddingIndex,
        EmbeddingIndexCompatibility embeddingIndexCompatibility,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static DocumentResponse from(SourceDocumentEntity entity, EmbeddingIndexProfile currentProfile) {
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
                entity.getProcessingAttempts(),
                EmbeddingIndexResponse.from(entity),
                compatibility(entity, currentProfile),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static EmbeddingIndexCompatibility compatibility(
            SourceDocumentEntity entity,
            EmbeddingIndexProfile currentProfile) {
        if (entity.getStatus() != DocumentStatus.SUCCEEDED) {
            return EmbeddingIndexCompatibility.NOT_INDEXED;
        }
        if (entity.getEmbeddingProfileId() == null) {
            return EmbeddingIndexCompatibility.UNKNOWN;
        }
        return currentProfile.id().equals(entity.getEmbeddingProfileId())
                ? EmbeddingIndexCompatibility.CURRENT
                : EmbeddingIndexCompatibility.OUTDATED;
    }
}
