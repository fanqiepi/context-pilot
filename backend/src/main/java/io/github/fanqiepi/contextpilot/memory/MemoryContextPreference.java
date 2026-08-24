package io.github.fanqiepi.contextpilot.memory;

import java.util.UUID;

public record MemoryContextPreference(
        UUID memoryId,
        UUID revisionId,
        int revision,
        String content) {
}
