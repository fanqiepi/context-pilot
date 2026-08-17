package io.github.fanqiepi.contextpilot.research;

import java.util.UUID;

public record ResearchRunSummaryResponse(
        UUID id,
        ResearchTaskType taskType,
        ResearchExecutionStatus executionStatus,
        ResearchAnswerStatus answerStatus,
        int totalSteps,
        int completedSteps,
        String errorCode,
        String errorSummary) {
}
