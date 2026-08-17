package io.github.fanqiepi.contextpilot.research;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import org.springframework.stereotype.Component;

@Component
public class ResearchPlanValidator {

    public void validate(ResearchPlan plan, List<UUID> selectedDocumentIds, ResearchBudget budget) {
        if (plan == null || !DeterministicResearchPlanner.PLAN_VERSION.equals(plan.planVersion())
                || plan.taskType() != ResearchTaskType.DOCUMENT_COMPARISON
                || plan.steps().isEmpty() || plan.steps().size() > budget.maximumPlanSteps()) {
            invalid();
        }
        Set<UUID> selected = Set.copyOf(selectedDocumentIds);
        Set<UUID> covered = new HashSet<>();
        Set<String> signatures = new HashSet<>();
        int retrievalCalls = 0;
        int expectedOrdinal = 1;
        for (ResearchPlanStep step : plan.steps()) {
            if (step.stepId() == null || step.ordinal() != expectedOrdinal++
                    || step.goal() == null || step.goal().isBlank() || step.goal().length() > 300
                    || step.query() == null || step.query().isBlank() || step.query().length() > 2000
                    || step.documentIds().isEmpty() || !Set.copyOf(step.documentIds()).equals(selected)
                    || step.documentIds().size() != selected.size()) {
                invalid();
            }
            String signature = step.query().strip().toLowerCase(Locale.ROOT) + "|" + step.documentIds();
            if (!signatures.add(signature)) {
                invalid();
            }
            covered.addAll(step.documentIds());
            retrievalCalls += step.documentIds().size();
        }
        if (!covered.equals(selected) || retrievalCalls > budget.maximumRetrievalCalls()) {
            invalid();
        }
    }

    private void invalid() {
        throw new BadRequestException("RESEARCH_PLAN_INVALID", "Deterministic research plan is invalid");
    }
}
