package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;

public record KnowledgeBaseHealthIssueResponse(
        UUID id,
        UUID reportId,
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

    static KnowledgeBaseHealthIssueResponse from(KnowledgeBaseHealthIssueEntity entity) {
        return new KnowledgeBaseHealthIssueResponse(
                entity.getId(),
                entity.getReportId(),
                entity.getDocumentId(),
                entity.getOriginalFilename(),
                entity.getIssueType(),
                entity.getSeverity(),
                entity.getObservedDocumentStatus(),
                entity.getObservedProcessingAttempts(),
                entity.getObservedErrorSummary(),
                entity.getObservedEmbeddingProfileId(),
                entity.getObservedVectorCount(),
                entity.getSourceDocumentUpdatedAt(),
                entity.getRecommendedActionType(),
                entity.isActionEligible(),
                entity.getIneligibilityReasonCode(),
                entity.getIneligibilitySummary());
    }
}
