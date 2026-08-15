package io.github.fanqiepi.contextpilot.evaluation;

import java.util.List;

public record V2EvaluationDataset(
        String datasetId,
        String version,
        String description,
        List<RoutingCase> routingCases,
        List<ParameterCase> parameterCases,
        List<ApiContractCase> apiContractCases,
        List<LifecycleCase> lifecycleCases) {

    public record RoutingCase(
            String id,
            String input,
            String expectedCapability,
            String expectedReason,
            String expectedDecision,
            String expectedName,
            String expectedDescription,
            List<String> tags) {
    }

    public record ParameterCase(
            String id,
            String name,
            RepeatValue nameRepeat,
            String description,
            RepeatValue descriptionRepeat,
            boolean expectedValid,
            String expectedName,
            String nameExpectation,
            String expectedDescription,
            String descriptionExpectation,
            String expectedErrorCode,
            List<String> tags) {
    }

    public record ApiContractCase(
            String id,
            String requestBody,
            String expectedActionType,
            String expectedName,
            List<String> tags) {
    }

    public record LifecycleCase(
            String id,
            String scenario,
            String expectedStatus,
            int expectedActiveKnowledgeBases,
            boolean expectSafeErrorSummary,
            List<String> tags) {
    }

    public record RepeatValue(String value, int count) {
        public String materialize() {
            return value.repeat(count);
        }
    }
}
