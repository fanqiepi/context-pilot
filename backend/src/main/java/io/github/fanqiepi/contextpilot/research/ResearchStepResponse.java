package io.github.fanqiepi.contextpilot.research;

import java.util.List;
import java.util.UUID;

public record ResearchStepResponse(
        UUID id,
        int ordinal,
        String goal,
        String query,
        List<UUID> documentIds,
        ResearchStepStatus status,
        int hitCount,
        int retainedEvidenceCount,
        Long latencyMs,
        String errorSummary) {
}
