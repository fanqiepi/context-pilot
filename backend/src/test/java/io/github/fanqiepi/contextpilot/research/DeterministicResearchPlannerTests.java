package io.github.fanqiepi.contextpilot.research;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicResearchPlannerTests {
    private final DeterministicResearchPlanner planner = new DeterministicResearchPlanner();

    @Test
    void createsOneOrderedStepPerDetectedDimensionAndCoversEveryDocument() {
        List<UUID> documents = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        ResearchPlan plan = planner.plan("比较安全机制、部署方式和成本模型", documents);

        assertThat(plan.planVersion()).isEqualTo("document-comparison-fixed-v1");
        assertThat(plan.steps()).extracting(ResearchPlanStep::query)
                .containsExactly(
                        "security 安全机制 安全",
                        "deployment 部署方式 部署",
                        "cost 成本模型 成本 计费");
        assertThat(plan.steps()).allSatisfy(step -> assertThat(step.documentIds()).isEqualTo(documents));
    }

    @Test
    void fallsBackToOneQuestionStepWhenNoKnownDimensionIsDetected() {
        List<UUID> documents = List.of(UUID.randomUUID(), UUID.randomUUID());

        ResearchPlan plan = planner.plan("比较这两份资料最重要的差异", documents);

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().getFirst().query()).isEqualTo("比较这两份资料最重要的差异");
    }

    @Test
    void rejectsQuestionsThatExceedTheFourStepBudget() {
        List<UUID> documents = List.of(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> planner.plan(
                "比较部署、安全、成本、合规和恢复机制", documents))
                .isInstanceOf(BadRequestException.class)
                .extracting("code")
                .isEqualTo("RESEARCH_PLAN_INVALID");
    }
}
