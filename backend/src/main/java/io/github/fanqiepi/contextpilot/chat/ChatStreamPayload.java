package io.github.fanqiepi.contextpilot.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.action.ActionRequestStatus;
import io.github.fanqiepi.contextpilot.action.ActionParameters;
import io.github.fanqiepi.contextpilot.action.ActionType;

public interface ChatStreamPayload {

    record Message(
            UUID conversationId,
            UUID userMessageId,
            UUID assistantMessageId,
            String traceId,
            CapabilityId capabilityId,
            String capabilityVersion,
            CapabilityMatchReason capabilityMatchReason) implements ChatStreamPayload {
    }

    record Delta(String content) implements ChatStreamPayload {
    }

    record Route(
            CapabilityId capabilityId,
            String capabilityVersion,
            CapabilityMatchReason capabilityMatchReason,
            String traceId) implements ChatStreamPayload {
    }

    record ActionRequired(
            UUID actionRequestId,
            UUID conversationId,
            UUID userMessageId,
            UUID assistantMessageId,
            CapabilityId capabilityId,
            String capabilityVersion,
            ActionType actionType,
            ActionParameters parameters,
            String displaySummary,
            ActionRequestStatus status,
            String resultSummary,
            String errorSummary,
            String traceId,
            OffsetDateTime expiresAt,
            OffsetDateTime confirmedAt,
            OffsetDateTime executedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) implements ChatStreamPayload {

        static ActionRequired from(ActionRequestResponse response) {
            return new ActionRequired(
                    response.id(),
                    response.conversationId(),
                    response.userMessageId(),
                    response.assistantMessageId(),
                    response.capabilityId(),
                    response.capabilityVersion(),
                    response.actionType(),
                    response.parameters(),
                    response.displaySummary(),
                    response.status(),
                    response.resultSummary(),
                    response.errorSummary(),
                    response.traceId(),
                    response.expiresAt(),
                    response.confirmedAt(),
                    response.executedAt(),
                    response.createdAt(),
                    response.updatedAt());
        }
    }

    record Usage(
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            long latencyMs) implements ChatStreamPayload {
    }

    record Done(String status, String traceId) implements ChatStreamPayload {
    }

    record Error(String code, String message, String traceId) implements ChatStreamPayload {
    }
}
