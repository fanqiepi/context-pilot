package io.github.fanqiepi.contextpilot.action;

import io.github.fanqiepi.contextpilot.common.BadRequestException;

public record CreateKnowledgeBaseActionParameters(String name, String description) {

    public CreateKnowledgeBaseActionParameters {
        name = normalize(name);
        description = normalizeNullable(description);
        if (name == null || name.isEmpty()) {
            throw new BadRequestException(
                    "INVALID_KNOWLEDGE_BASE_NAME",
                    "Knowledge base name must not be blank");
        }
        if (name.length() > 100) {
            throw new BadRequestException(
                    "KNOWLEDGE_BASE_NAME_TOO_LONG",
                    "Knowledge base name must not exceed 100 characters");
        }
        if (description != null && description.length() > 1000) {
            throw new BadRequestException(
                    "KNOWLEDGE_BASE_DESCRIPTION_TOO_LONG",
                    "Knowledge base description must not exceed 1000 characters");
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.strip().replaceAll("\\s+", " ");
    }

    private static String normalizeNullable(String value) {
        String normalized = normalize(value);
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }
}
