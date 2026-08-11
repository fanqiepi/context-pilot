package io.github.fanqiepi.contextpilot.chat;

import java.util.List;

record PreparedChat(
        PendingChatExchange exchange,
        ChatPrompt prompt,
        List<ChatCitationResponse> citations,
        String directAnswer) {

    PreparedChat(
            PendingChatExchange exchange,
            ChatPrompt prompt,
            List<ChatCitationResponse> citations) {
        this(exchange, prompt, citations, null);
    }

    static PreparedChat direct(PendingChatExchange exchange, String answer) {
        return new PreparedChat(exchange, null, List.of(), answer);
    }

    boolean refused() {
        return prompt == null && directAnswer == null;
    }

    boolean hasDirectAnswer() {
        return directAnswer != null;
    }
}
