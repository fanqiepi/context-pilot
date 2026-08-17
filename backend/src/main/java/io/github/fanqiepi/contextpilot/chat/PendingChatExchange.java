package io.github.fanqiepi.contextpilot.chat;

import java.util.UUID;

public record PendingChatExchange(UUID conversationId, UUID userMessageId, UUID assistantMessageId) {
}
