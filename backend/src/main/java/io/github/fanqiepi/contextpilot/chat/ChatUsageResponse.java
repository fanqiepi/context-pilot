package io.github.fanqiepi.contextpilot.chat;

public record ChatUsageResponse(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        long latencyMs) {
}
