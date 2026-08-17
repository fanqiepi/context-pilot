package io.github.fanqiepi.contextpilot.research;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ResearchRunResponse(
        UUID id,
        UUID knowledgeBaseId,
        UUID conversationId,
        UUID userMessageId,
        UUID assistantMessageId,
        UUID clientRequestId,
        ResearchTaskType taskType,
        String planVersion,
        List<UUID> documentIds,
        ResearchExecutionStatus executionStatus,
        ResearchAnswerStatus answerStatus,
        List<ResearchStepResponse> steps,
        ResearchBudget budget,
        ResearchUsageResponse usage,
        String errorCode,
        String errorSummary,
        String traceId,
        UUID retryOfRunId,
        OffsetDateTime startedAt,
        OffsetDateTime cancelledAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
