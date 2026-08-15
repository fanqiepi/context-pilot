package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.ChatStreamPayload;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProfile;

public record KnowledgeBaseHealthReportResponse(
        UUID id,
        UUID knowledgeBaseId,
        UUID conversationId,
        UUID userMessageId,
        UUID assistantMessageId,
        CapabilityId capabilityId,
        String capabilityVersion,
        KnowledgeBaseHealthStatus healthStatus,
        HealthCheckCompleteness completeness,
        String completenessReason,
        OffsetDateTime dataAsOf,
        EmbeddingIndexProfile currentEmbeddingProfile,
        DocumentStatusCounts documentCounts,
        long issueCount,
        int returnedIssueCount,
        String summary,
        List<KnowledgeBaseHealthIssueResponse> issues,
        String traceId,
        OffsetDateTime createdAt) implements ChatStreamPayload {

    public KnowledgeBaseHealthReportResponse {
        issues = List.copyOf(issues);
        if (returnedIssueCount != issues.size()) {
            throw new IllegalArgumentException("Returned issue count must match the issue list size");
        }
    }

    static KnowledgeBaseHealthReportResponse from(
            KnowledgeBaseHealthReportEntity entity,
            List<KnowledgeBaseHealthIssueEntity> issueEntities) {
        List<KnowledgeBaseHealthIssueResponse> issues = issueEntities.stream()
                .map(KnowledgeBaseHealthIssueResponse::from)
                .toList();
        return new KnowledgeBaseHealthReportResponse(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getConversationId(),
                entity.getUserMessageId(),
                entity.getAssistantMessageId(),
                entity.getCapabilityId(),
                entity.getCapabilityVersion(),
                entity.getHealthStatus(),
                entity.getCompleteness(),
                entity.getCompletenessReason(),
                entity.getDataAsOf(),
                new EmbeddingIndexProfile(
                        entity.getEmbeddingProfileId(),
                        entity.getEmbeddingProvider(),
                        entity.getEmbeddingModel(),
                        entity.getEmbeddingDimensions(),
                        entity.getEmbeddingProfileVersion()),
                new DocumentStatusCounts(
                        entity.getDocumentTotalCount(),
                        entity.getDocumentPendingCount(),
                        entity.getDocumentProcessingCount(),
                        entity.getDocumentSucceededCount(),
                        entity.getDocumentFailedCount(),
                        entity.getDocumentDeletingCount()),
                entity.getIssueCount(),
                entity.getReturnedIssueCount(),
                entity.getSummary(),
                issues,
                entity.getTraceId(),
                entity.getCreatedAt());
    }
}
