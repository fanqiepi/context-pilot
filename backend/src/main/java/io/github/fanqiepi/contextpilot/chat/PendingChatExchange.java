package io.github.fanqiepi.contextpilot.chat;

import java.util.UUID;

record PendingChatExchange(UUID conversationId, UUID userMessageId, UUID assistantMessageId) {
}
