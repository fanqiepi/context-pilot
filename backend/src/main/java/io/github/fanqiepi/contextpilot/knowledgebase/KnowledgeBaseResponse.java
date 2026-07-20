package io.github.fanqiepi.contextpilot.knowledgebase;

import java.time.OffsetDateTime;
import java.util.UUID;

public record KnowledgeBaseResponse(
        UUID id,
        String name,
        String description,
        KnowledgeBaseStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static KnowledgeBaseResponse from(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
