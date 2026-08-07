package io.github.fanqiepi.contextpilot.chat;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        UUID conversationId,
        @NotNull UUID knowledgeBaseId,
        @NotBlank @Size(max = 2000) String question) {
}
