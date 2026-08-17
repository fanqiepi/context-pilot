package io.github.fanqiepi.contextpilot.research;

public record ResearchUsageResponse(
        int retrievalCalls,
        int rawHits,
        int evidenceChunks,
        int evidenceCharacters,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {
}
