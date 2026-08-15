package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthReportResponse;

public record ChatAnswerResponse(
        UUID conversationId,
        UUID userMessageId,
        UUID assistantMessageId,
        String answer,
        boolean refused,
        List<ChatCitationResponse> citations,
        String model,
        ChatUsageResponse usage,
        String traceId,
        CapabilityId capabilityId,
        String capabilityVersion,
        CapabilityMatchReason capabilityMatchReason,
        KnowledgeBaseHealthReportResponse healthReport,
        ActionRequestResponse actionRequest) {
}
