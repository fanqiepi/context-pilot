package io.github.fanqiepi.contextpilot.chat;

import java.util.List;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;

record PreparedChat(
        CapabilityRoute route,
        PendingChatExchange exchange,
        ChatPrompt prompt,
        List<ChatCitationResponse> citations,
        String directAnswer,
        ActionRequestResponse actionRequest) {

    PreparedChat(
            CapabilityRoute route,
            PendingChatExchange exchange,
            ChatPrompt prompt,
            List<ChatCitationResponse> citations) {
        this(route, exchange, prompt, citations, null, null);
    }

    static PreparedChat direct(
            CapabilityRoute route,
            PendingChatExchange exchange,
            String answer) {
        return new PreparedChat(route, exchange, null, List.of(), answer, null);
    }

    static PreparedChat action(
            CapabilityRoute route,
            PendingChatExchange exchange,
            String answer,
            ActionRequestResponse actionRequest) {
        return new PreparedChat(route, exchange, null, List.of(), answer, actionRequest);
    }

    boolean refused() {
        return prompt == null && directAnswer == null;
    }

    boolean hasDirectAnswer() {
        return directAnswer != null;
    }

    boolean actionRequired() {
        return actionRequest != null;
    }
}
