package io.github.fanqiepi.contextpilot.research;

import java.util.List;
import java.util.UUID;

public record ResearchPlanStep(
        UUID stepId,
        int ordinal,
        String goal,
        String query,
        List<UUID> documentIds) {

    public ResearchPlanStep {
        documentIds = List.copyOf(documentIds);
    }
}
