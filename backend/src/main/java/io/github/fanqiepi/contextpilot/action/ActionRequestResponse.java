package io.github.fanqiepi.contextpilot.action;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;

public record ActionRequestResponse(
        UUID id,
        UUID conversationId,
        UUID userMessageId,
        UUID assistantMessageId,
        CapabilityId capabilityId,
        String capabilityVersion,
        ActionType actionType,
        CreateKnowledgeBaseActionParameters parameters,
        String displaySummary,
        ActionRequestStatus status,
        String resultSummary,
        String errorSummary,
        String traceId,
        OffsetDateTime expiresAt,
        OffsetDateTime confirmedAt,
        OffsetDateTime executedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static ActionRequestResponse from(ActionRequestEntity entity) {
        return new ActionRequestResponse(
                entity.getId(),
                entity.getConversationId(),
                entity.getUserMessageId(),
                entity.getAssistantMessageId(),
                entity.getCapabilityId(),
                entity.getCapabilityVersion(),
                entity.getActionType(),
                new CreateKnowledgeBaseActionParameters(entity.getName(), entity.getDescription()),
                entity.getDisplaySummary(),
                entity.getStatus(),
                entity.getResultSummary(),
                entity.getErrorSummary(),
                entity.getTraceId(),
                entity.getExpiresAt(),
                entity.getConfirmedAt(),
                entity.getExecutedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
