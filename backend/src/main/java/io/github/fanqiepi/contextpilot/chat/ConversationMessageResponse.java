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
        CapabilityId capabilityId,
        String capabilityVersion,
        CapabilityMatchReason capabilityMatchReason,
        List<ChatCitationResponse> citations,
        boolean helpful,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static ConversationMessageResponse from(
            ChatMessageEntity entity,
            List<ChatCitationResponse> citations,
            boolean helpful) {
        return new ConversationMessageResponse(
                entity.getId(),
                entity.getConversationId(),
                entity.getRole(),
                entity.getContent(),
                entity.getStatus(),
                entity.getErrorSummary(),
                entity.getTraceId(),
                entity.getCapabilityId(),
                entity.getCapabilityVersion(),
                entity.getCapabilityMatchReason(),
                List.copyOf(citations),
                helpful,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
