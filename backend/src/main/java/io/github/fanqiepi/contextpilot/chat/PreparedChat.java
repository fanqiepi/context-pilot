package io.github.fanqiepi.contextpilot.chat;

import java.util.List;

record PreparedChat(
        PendingChatExchange exchange,
        ChatPrompt prompt,
        List<ChatCitationResponse> citations) {

    boolean refused() {
        return prompt == null;
    }
}
