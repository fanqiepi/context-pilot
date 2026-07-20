package io.github.fanqiepi.contextpilot.knowledgebase;

import jakarta.validation.constraints.Size;

public record KnowledgeBaseUpdateRequest(
        @Size(max = 100, message = "name must not exceed 100 characters")
        String name,
        @Size(max = 1000, message = "description must not exceed 1000 characters")
        String description) {
}
