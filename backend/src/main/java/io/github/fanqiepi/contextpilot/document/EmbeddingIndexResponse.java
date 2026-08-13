package io.github.fanqiepi.contextpilot.document;

import java.time.OffsetDateTime;

public record EmbeddingIndexResponse(
        String profileId,
        String provider,
        String model,
        Integer dimensions,
        String profileVersion,
        OffsetDateTime indexedAt) {

    static EmbeddingIndexResponse from(SourceDocumentEntity entity) {
        if (entity.getEmbeddingProfileId() == null) {
            return null;
        }
        return new EmbeddingIndexResponse(
                entity.getEmbeddingProfileId(),
                entity.getEmbeddingProvider(),
                entity.getEmbeddingModel(),
                entity.getEmbeddingDimensions(),
                entity.getEmbeddingProfileVersion(),
                entity.getIndexedAt());
    }
}
