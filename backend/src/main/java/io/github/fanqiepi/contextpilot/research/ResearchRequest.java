package io.github.fanqiepi.contextpilot.research;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResearchRequest(
        @NotNull UUID clientRequestId,
        @NotNull ResearchTaskType taskType,
        @NotNull @Size(min = 2, max = 5) List<@NotNull UUID> documentIds,
        UUID retryOfRunId) {

    public ResearchRequest {
        documentIds = documentIds == null ? null : List.copyOf(documentIds);
    }

    public ResearchRequest(UUID clientRequestId, ResearchTaskType taskType, List<UUID> documentIds) {
        this(clientRequestId, taskType, documentIds, null);
    }
}
