package io.github.fanqiepi.contextpilot.research;

import java.util.List;

import io.github.fanqiepi.contextpilot.chat.ChatCitationResponse;

public record ResearchExecutionResult(
        ResearchRunResponse run,
        String answer,
        List<ChatCitationResponse> citations,
        String model,
        long latencyMs) {

    public ResearchExecutionResult {
        citations = List.copyOf(citations);
    }
}
