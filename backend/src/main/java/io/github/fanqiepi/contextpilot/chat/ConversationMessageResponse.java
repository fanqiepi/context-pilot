package io.github.fanqiepi.contextpilot.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ConversationMessageResponse(
        UUID id,
        UUID conversationId,
        ChatMessageRole role,
        String content,
        ChatMessageStatus status,
        String errorSummary,
        String traceId,
        List<ChatCitationResponse> citations,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static ConversationMessageResponse from(
            ChatMessageEntity entity,
            List<ChatCitationResponse> citations) {
        return new ConversationMessageResponse(
                entity.getId(),
                entity.getConversationId(),
                entity.getRole(),
                entity.getContent(),
                entity.getStatus(),
                entity.getErrorSummary(),
                entity.getTraceId(),
                List.copyOf(citations),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
