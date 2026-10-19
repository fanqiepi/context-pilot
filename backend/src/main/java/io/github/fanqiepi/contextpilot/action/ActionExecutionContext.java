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
        if ((conversationId == null) != (userMessageId == null)) {
            throw new IllegalArgumentException("Chat provenance must be complete or absent for a dedicated page action");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("Trace ID must not be blank");
        }
    }
}
