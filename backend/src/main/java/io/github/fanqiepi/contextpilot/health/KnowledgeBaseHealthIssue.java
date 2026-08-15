package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;

public record KnowledgeBaseHealthIssue(
        UUID documentId,
        String originalFilename,
        KnowledgeBaseHealthIssueType issueType,
        HealthIssueSeverity severity,
        DocumentStatus observedDocumentStatus,
        int observedProcessingAttempts,
        String observedErrorSummary,
        String observedEmbeddingProfileId,
        Long observedVectorCount,
        OffsetDateTime sourceDocumentUpdatedAt,
        HealthRecommendedActionType recommendedActionType,
        boolean actionEligible,
        HealthIneligibilityReasonCode ineligibilityReasonCode,
        String ineligibilitySummary) {
}
