package io.github.fanqiepi.contextpilot.chat;

import java.util.List;

record PreparedChat(
        CapabilityRoute route,
        PendingChatExchange exchange,
        ChatPrompt prompt,
        List<ChatCitationResponse> citations,
        String directAnswer) {

    PreparedChat(
            CapabilityRoute route,
            PendingChatExchange exchange,
            ChatPrompt prompt,
            List<ChatCitationResponse> citations) {
        this(route, exchange, prompt, citations, null);
    }

    static PreparedChat direct(
            CapabilityRoute route,
            PendingChatExchange exchange,
            String answer) {
        return new PreparedChat(route, exchange, null, List.of(), answer);
    }

    boolean refused() {
        return prompt == null && directAnswer == null;
    }

    boolean hasDirectAnswer() {
        return directAnswer != null;
    }
}
