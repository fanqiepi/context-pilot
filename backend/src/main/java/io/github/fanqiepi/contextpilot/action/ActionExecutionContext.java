package io.github.fanqiepi.contextpilot.action;

import java.util.Objects;
import java.util.UUID;

public record ActionExecutionContext(
        UUID actionRequestId,
        UUID conversationId,
        UUID userMessageId,
        String traceId) {

    public ActionExecutionContext {
        Objects.requireNonNull(actionRequestId, "Action request ID must not be null");
        Objects.requireNonNull(conversationId, "Conversation ID must not be null");
        Objects.requireNonNull(userMessageId, "User message ID must not be null");
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("Trace ID must not be blank");
        }
    }
}
