package io.github.fanqiepi.contextpilot.observability;

import java.util.UUID;

public record AiCallContext(
        String operation,
        String provider,
        String model,
        String traceId,
        UUID callId,
        String resourceType,
        UUID resourceId,
        String promptVersion,
        Integer inputCharacters,
        Integer itemCount,
        Integer maxOutputTokens) {
}
