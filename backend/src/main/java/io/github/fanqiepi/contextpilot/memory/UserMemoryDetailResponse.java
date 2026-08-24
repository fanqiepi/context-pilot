package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record UserMemoryDetailResponse(
        UUID id,
        UserMemoryType memoryType,
        UserMemoryScope scope,
        UserMemoryStatus status,
        String content,
        int currentRevision,
        UserMemorySourceType sourceType,
        OffsetDateTime expiresAt,
        boolean expired,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<UserMemoryRevisionSummaryResponse> revisions) {
}
