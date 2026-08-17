package io.github.fanqiepi.contextpilot.research;

import java.util.List;

public record ResearchPlan(
        String planVersion,
        ResearchTaskType taskType,
        List<ResearchPlanStep> steps) {

    public ResearchPlan {
        steps = List.copyOf(steps);
    }
}
