package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProfile;

public record KnowledgeBaseHealthAssessment(
        UUID knowledgeBaseId,
        KnowledgeBaseHealthStatus healthStatus,
        HealthCheckCompleteness completeness,
        String completenessReason,
        OffsetDateTime dataAsOf,
        EmbeddingIndexProfile currentEmbeddingProfile,
        DocumentStatusCounts documentCounts,
        long issueCount,
        int returnedIssueCount,
        String summary,
        List<KnowledgeBaseHealthIssue> issues) {

    public KnowledgeBaseHealthAssessment {
        issues = List.copyOf(issues);
        if (returnedIssueCount != issues.size()) {
            throw new IllegalArgumentException("Returned issue count must match the issue list size");
        }
        if (issueCount < returnedIssueCount) {
            throw new IllegalArgumentException("Issue count must not be smaller than returned issues");
        }
    }
}
