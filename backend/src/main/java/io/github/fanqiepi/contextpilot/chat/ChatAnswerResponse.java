package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthReportResponse;
import io.github.fanqiepi.contextpilot.research.ResearchRunResponse;

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
        ActionRequestResponse actionRequest,
        ResearchRunResponse researchRun) {

    public ChatAnswerResponse(
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
        this(conversationId, userMessageId, assistantMessageId, answer, refused, citations,
                model, usage, traceId, capabilityId, capabilityVersion, capabilityMatchReason,
                healthReport, actionRequest, null);
    }
}
