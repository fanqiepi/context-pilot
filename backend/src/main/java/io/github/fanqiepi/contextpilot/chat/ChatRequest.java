package io.github.fanqiepi.contextpilot.chat;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import io.github.fanqiepi.contextpilot.research.ResearchRequest;
import io.github.fanqiepi.contextpilot.memory.MemoryMode;

public record ChatRequest(
        UUID conversationId,
        @NotNull UUID knowledgeBaseId,
        @NotBlank @Size(max = 2000) String question,
        @Valid ResearchRequest research,
        MemoryMode memoryMode) {

    public ChatRequest(UUID conversationId, UUID knowledgeBaseId, String question) {
        this(conversationId, knowledgeBaseId, question, null, MemoryMode.USE);
    }

    public ChatRequest(
            UUID conversationId,
            UUID knowledgeBaseId,
            String question,
            ResearchRequest research) {
        this(conversationId, knowledgeBaseId, question, research, MemoryMode.USE);
    }

    public MemoryMode effectiveMemoryMode() {
        return memoryMode == null ? MemoryMode.USE : memoryMode;
    }
}
