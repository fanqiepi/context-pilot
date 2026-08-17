package io.github.fanqiepi.contextpilot.research;

import io.github.fanqiepi.contextpilot.chat.PendingChatExchange;

public record ResearchStart(
        PendingChatExchange exchange,
        ResearchRunResponse run,
        boolean reusedExisting) {
}
