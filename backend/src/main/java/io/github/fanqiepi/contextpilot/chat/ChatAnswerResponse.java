package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.UUID;

public record ChatAnswerResponse(
        UUID conversationId,
        UUID userMessageId,
        UUID assistantMessageId,
        String answer,
        boolean refused,
        List<ChatCitationResponse> citations,
        String model,
        ChatUsageResponse usage,
        String traceId) {
}
