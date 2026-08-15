package io.github.fanqiepi.contextpilot.evaluation;

import java.util.List;

public record V3EvaluationDataset(
        String datasetId,
        String version,
        String description,
        List<RoutingCase> routingCases,
        List<HealthCase> healthCases,
        List<LifecycleCoverageCase> lifecycleCoverage) {

    public record RoutingCase(
            String id,
            String input,
            String expectedCapability,
            String expectedVersion,
            String expectedReason,
            boolean healthIntent,
            List<String> tags) {
    }

    public record HealthCase(
            String id,
            StatusCounts counts,
            long issueCount,
            boolean vectorCheckComplete,
            boolean documentProcessingEnabled,
            int maximumProcessingAttempts,
            List<DocumentCandidate> candidates,
            String expectedStatus,
            String expectedCompleteness,
            List<String> expectedIssueTypes,
            int expectedEligibleActions,
            List<String> tags) {
    }

    public record StatusCounts(
            long total,
            long pending,
            long processing,
            long succeeded,
            long failed,
            long deleting) {
    }

    public record DocumentCandidate(
            String status,
            int processingAttempts,
            String embeddingProfileId,
            Long currentProfileVectorCount) {
    }

    public record LifecycleCoverageCase(
            String id,
            String scenario,
            List<String> tags) {
    }
}
