package io.github.fanqiepi.contextpilot.feedback;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AnswerFeedbackResponse(
        UUID id,
        UUID messageId,
        UUID knowledgeBaseId,
        String traceId,
        boolean helpful,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static AnswerFeedbackResponse from(
            AnswerFeedbackEntity entity,
            UUID knowledgeBaseId,
            String traceId) {
        return new AnswerFeedbackResponse(
                entity.getId(),
                entity.getMessageId(),
                knowledgeBaseId,
                traceId,
                true,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
