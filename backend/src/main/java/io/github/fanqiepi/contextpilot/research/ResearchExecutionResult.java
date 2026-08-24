package io.github.fanqiepi.contextpilot.research;

import java.util.List;

import io.github.fanqiepi.contextpilot.chat.ChatCitationResponse;
import io.github.fanqiepi.contextpilot.memory.MemoryContextResponse;

public record ResearchExecutionResult(
        ResearchRunResponse run,
        String answer,
        List<ChatCitationResponse> citations,
        String model,
        long latencyMs,
        MemoryContextResponse memoryContext) {

    public ResearchExecutionResult(
            ResearchRunResponse run,
            String answer,
            List<ChatCitationResponse> citations,
            String model,
            long latencyMs) {
        this(run, answer, citations, model, latencyMs, null);
    }

    public ResearchExecutionResult {
        citations = List.copyOf(citations);
    }
}
