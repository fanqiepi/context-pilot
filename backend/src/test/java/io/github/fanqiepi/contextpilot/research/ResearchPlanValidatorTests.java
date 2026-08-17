package io.github.fanqiepi.contextpilot.research;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchPlanValidatorTests {
    private final ResearchPlanValidator validator = new ResearchPlanValidator();

    @Test
    void rejectsAPlanStepThatDropsOneFrozenDocument() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ResearchPlan plan = new ResearchPlan(
                DeterministicResearchPlanner.PLAN_VERSION,
                ResearchTaskType.DOCUMENT_COMPARISON,
                List.of(new ResearchPlanStep(UUID.randomUUID(), 1, "goal", "query", List.of(first))));

        assertThatThrownBy(() -> validator.validate(plan, List.of(first, second), ResearchBudget.V1))
                .isInstanceOf(BadRequestException.class);
    }
}
