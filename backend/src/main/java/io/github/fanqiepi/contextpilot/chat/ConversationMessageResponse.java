package io.github.fanqiepi.contextpilot.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthReportResponse;
import io.github.fanqiepi.contextpilot.research.ResearchRunSummaryResponse;
import io.github.fanqiepi.contextpilot.memory.MemoryContextResponse;

public record ConversationMessageResponse(
        UUID id,
        UUID conversationId,
        ChatMessageRole role,
        String content,
        ChatMessageStatus status,
        String errorSummary,
        String traceId,
        CapabilityId capabilityId,
        String capabilityVersion,
        CapabilityMatchReason capabilityMatchReason,
        KnowledgeBaseHealthReportResponse healthReport,
        ActionRequestResponse actionRequest,
        ResearchRunSummaryResponse researchRun,
        List<ChatCitationResponse> citations,
        boolean helpful,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        MemoryContextResponse memoryContext) {

    public ConversationMessageResponse(
            UUID id,
            UUID conversationId,
            ChatMessageRole role,
            String content,
            ChatMessageStatus status,
            String errorSummary,
            String traceId,
            CapabilityId capabilityId,
            String capabilityVersion,
            CapabilityMatchReason capabilityMatchReason,
            KnowledgeBaseHealthReportResponse healthReport,
            ActionRequestResponse actionRequest,
            ResearchRunSummaryResponse researchRun,
            List<ChatCitationResponse> citations,
            boolean helpful,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this(id, conversationId, role, content, status, errorSummary, traceId,
                capabilityId, capabilityVersion, capabilityMatchReason, healthReport,
                actionRequest, researchRun, citations, helpful, createdAt, updatedAt, null);
    }

    public ConversationMessageResponse(
            UUID id,
            UUID conversationId,
            ChatMessageRole role,
            String content,
            ChatMessageStatus status,
            String errorSummary,
            String traceId,
            CapabilityId capabilityId,
            String capabilityVersion,
            CapabilityMatchReason capabilityMatchReason,
            KnowledgeBaseHealthReportResponse healthReport,
            ActionRequestResponse actionRequest,
            List<ChatCitationResponse> citations,
            boolean helpful,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this(id, conversationId, role, content, status, errorSummary, traceId, capabilityId,
                capabilityVersion, capabilityMatchReason, healthReport, actionRequest, null,
                citations, helpful, createdAt, updatedAt, null);
    }

    public static ConversationMessageResponse from(
            ChatMessageEntity entity,
            KnowledgeBaseHealthReportResponse healthReport,
            ActionRequestResponse actionRequest,
            ResearchRunSummaryResponse researchRun,
            List<ChatCitationResponse> citations,
            boolean helpful,
            MemoryContextResponse memoryContext) {
        return new ConversationMessageResponse(
                entity.getId(),
                entity.getConversationId(),
                entity.getRole(),
                entity.getContent(),
                entity.getStatus(),
                entity.getErrorSummary(),
                entity.getTraceId(),
                entity.getCapabilityId(),
                entity.getCapabilityVersion(),
                entity.getCapabilityMatchReason(),
                healthReport,
                actionRequest,
                researchRun,
                List.copyOf(citations),
                helpful,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                memoryContext);
    }

    public static ConversationMessageResponse from(
            ChatMessageEntity entity,
            KnowledgeBaseHealthReportResponse healthReport,
            ActionRequestResponse actionRequest,
            ResearchRunSummaryResponse researchRun,
            List<ChatCitationResponse> citations,
            boolean helpful) {
        return from(entity, healthReport, actionRequest, researchRun, citations, helpful, null);
    }
}
