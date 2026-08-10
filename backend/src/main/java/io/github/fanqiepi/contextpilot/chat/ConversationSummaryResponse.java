package io.github.fanqiepi.contextpilot.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConversationSummaryResponse(
        UUID id,
        UUID knowledgeBaseId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static ConversationSummaryResponse from(ConversationEntity entity) {
        return new ConversationSummaryResponse(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getTitle(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
