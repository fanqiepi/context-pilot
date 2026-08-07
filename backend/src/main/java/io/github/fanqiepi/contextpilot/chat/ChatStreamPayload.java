package io.github.fanqiepi.contextpilot.chat;

import java.util.UUID;

public interface ChatStreamPayload {

    record Message(
            UUID conversationId,
            UUID userMessageId,
            UUID assistantMessageId,
            String traceId) implements ChatStreamPayload {
    }

    record Delta(String content) implements ChatStreamPayload {
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
