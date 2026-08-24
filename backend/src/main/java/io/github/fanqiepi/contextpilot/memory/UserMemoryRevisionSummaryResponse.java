package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;

public record UserMemoryRevisionSummaryResponse(
        int revision,
        UserMemorySourceType sourceType,
        String changeReason,
        OffsetDateTime createdAt) {

    static UserMemoryRevisionSummaryResponse from(UserMemoryRevisionEntity revision) {
        return new UserMemoryRevisionSummaryResponse(
                revision.getRevisionNumber(),
                revision.getSourceType(),
                revision.getChangeReason(),
                revision.getCreatedAt());
    }
}
