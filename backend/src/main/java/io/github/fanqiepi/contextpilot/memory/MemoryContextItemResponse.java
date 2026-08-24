package io.github.fanqiepi.contextpilot.memory;

import java.util.UUID;

public record MemoryContextItemResponse(
        UUID memoryId,
        int revision,
        String summary,
        boolean contentHidden) {
}
