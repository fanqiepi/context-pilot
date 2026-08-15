package io.github.fanqiepi.contextpilot.action;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;

public class ActionRequestEntity {

    private UUID id;
    private UUID conversationId;
    private UUID userMessageId;
    private UUID assistantMessageId;
    private CapabilityId capabilityId;
    private String capabilityVersion;
    private ActionType actionType;
    private String parametersJson;
    private UUID targetDocumentId;
    private UUID healthIssueId;
    private String displaySummary;
    private ActionRequestStatus status;
    private String resultSummary;
    private String errorSummary;
    private String traceId;
    private OffsetDateTime expiresAt;
    private OffsetDateTime confirmedAt;
    private OffsetDateTime executedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted = 0;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public UUID getUserMessageId() { return userMessageId; }
    public void setUserMessageId(UUID userMessageId) { this.userMessageId = userMessageId; }
    public UUID getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(UUID assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public CapabilityId getCapabilityId() { return capabilityId; }
    public void setCapabilityId(CapabilityId capabilityId) { this.capabilityId = capabilityId; }
    public String getCapabilityVersion() { return capabilityVersion; }
    public void setCapabilityVersion(String capabilityVersion) { this.capabilityVersion = capabilityVersion; }
    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }
    public String getParametersJson() { return parametersJson; }
    public void setParametersJson(String parametersJson) { this.parametersJson = parametersJson; }
    public UUID getTargetDocumentId() { return targetDocumentId; }
    public void setTargetDocumentId(UUID targetDocumentId) { this.targetDocumentId = targetDocumentId; }
    public UUID getHealthIssueId() { return healthIssueId; }
    public void setHealthIssueId(UUID healthIssueId) { this.healthIssueId = healthIssueId; }
    public String getDisplaySummary() { return displaySummary; }
    public void setDisplaySummary(String displaySummary) { this.displaySummary = displaySummary; }
    public ActionRequestStatus getStatus() { return status; }
    public void setStatus(ActionRequestStatus status) { this.status = status; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(OffsetDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public OffsetDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(OffsetDateTime executedAt) { this.executedAt = executedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
