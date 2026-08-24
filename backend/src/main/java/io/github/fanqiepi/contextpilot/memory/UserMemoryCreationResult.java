package io.github.fanqiepi.contextpilot.memory;

import java.util.UUID;

public record UserMemoryCreationResult(
        UUID memoryId,
        int revision,
        String content,
        boolean created) {
}
