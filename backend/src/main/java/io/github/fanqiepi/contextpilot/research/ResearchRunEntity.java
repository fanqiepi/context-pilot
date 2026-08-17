package io.github.fanqiepi.contextpilot.research;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ResearchRunEntity {
    private UUID id;
    private UUID knowledgeBaseId;
    private UUID conversationId;
    private UUID userMessageId;
    private UUID assistantMessageId;
    private UUID clientRequestId;
    private String requestFingerprint;
    private ResearchTaskType taskType;
    private String planVersion;
    private String selectedDocumentIdsJson;
    private ResearchExecutionStatus executionStatus;
    private ResearchAnswerStatus answerStatus;
    private int maxPlanSteps;
    private int maxRetrievalCalls;
    private int maxRawHits;
    private int maxEvidenceChunks;
    private int maxEvidenceCharacters;
    private long hardTimeoutMillis;
    private int actualRetrievalCalls;
    private int actualRawHits;
    private int actualEvidenceChunks;
    private int actualEvidenceCharacters;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer currentStepOrdinal;
    private String errorCode;
    private String errorSummary;
    private String traceId;
    private UUID retryOfRunId;
    private OffsetDateTime startedAt;
    private OffsetDateTime cancelledAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted = 0;

    public UUID getId() { return id; } public void setId(UUID v) { id = v; }
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; } public void setKnowledgeBaseId(UUID v) { knowledgeBaseId = v; }
    public UUID getConversationId() { return conversationId; } public void setConversationId(UUID v) { conversationId = v; }
    public UUID getUserMessageId() { return userMessageId; } public void setUserMessageId(UUID v) { userMessageId = v; }
    public UUID getAssistantMessageId() { return assistantMessageId; } public void setAssistantMessageId(UUID v) { assistantMessageId = v; }
    public UUID getClientRequestId() { return clientRequestId; } public void setClientRequestId(UUID v) { clientRequestId = v; }
    public String getRequestFingerprint() { return requestFingerprint; } public void setRequestFingerprint(String v) { requestFingerprint = v; }
    public ResearchTaskType getTaskType() { return taskType; } public void setTaskType(ResearchTaskType v) { taskType = v; }
    public String getPlanVersion() { return planVersion; } public void setPlanVersion(String v) { planVersion = v; }
    public String getSelectedDocumentIdsJson() { return selectedDocumentIdsJson; } public void setSelectedDocumentIdsJson(String v) { selectedDocumentIdsJson = v; }
    public ResearchExecutionStatus getExecutionStatus() { return executionStatus; } public void setExecutionStatus(ResearchExecutionStatus v) { executionStatus = v; }
    public ResearchAnswerStatus getAnswerStatus() { return answerStatus; } public void setAnswerStatus(ResearchAnswerStatus v) { answerStatus = v; }
    public int getMaxPlanSteps() { return maxPlanSteps; } public void setMaxPlanSteps(int v) { maxPlanSteps = v; }
    public int getMaxRetrievalCalls() { return maxRetrievalCalls; } public void setMaxRetrievalCalls(int v) { maxRetrievalCalls = v; }
    public int getMaxRawHits() { return maxRawHits; } public void setMaxRawHits(int v) { maxRawHits = v; }
    public int getMaxEvidenceChunks() { return maxEvidenceChunks; } public void setMaxEvidenceChunks(int v) { maxEvidenceChunks = v; }
    public int getMaxEvidenceCharacters() { return maxEvidenceCharacters; } public void setMaxEvidenceCharacters(int v) { maxEvidenceCharacters = v; }
    public long getHardTimeoutMillis() { return hardTimeoutMillis; } public void setHardTimeoutMillis(long v) { hardTimeoutMillis = v; }
    public int getActualRetrievalCalls() { return actualRetrievalCalls; } public void setActualRetrievalCalls(int v) { actualRetrievalCalls = v; }
    public int getActualRawHits() { return actualRawHits; } public void setActualRawHits(int v) { actualRawHits = v; }
    public int getActualEvidenceChunks() { return actualEvidenceChunks; } public void setActualEvidenceChunks(int v) { actualEvidenceChunks = v; }
    public int getActualEvidenceCharacters() { return actualEvidenceCharacters; } public void setActualEvidenceCharacters(int v) { actualEvidenceCharacters = v; }
    public Integer getPromptTokens() { return promptTokens; } public void setPromptTokens(Integer v) { promptTokens = v; }
    public Integer getCompletionTokens() { return completionTokens; } public void setCompletionTokens(Integer v) { completionTokens = v; }
    public Integer getTotalTokens() { return totalTokens; } public void setTotalTokens(Integer v) { totalTokens = v; }
    public Integer getCurrentStepOrdinal() { return currentStepOrdinal; } public void setCurrentStepOrdinal(Integer v) { currentStepOrdinal = v; }
    public String getErrorCode() { return errorCode; } public void setErrorCode(String v) { errorCode = v; }
    public String getErrorSummary() { return errorSummary; } public void setErrorSummary(String v) { errorSummary = v; }
    public String getTraceId() { return traceId; } public void setTraceId(String v) { traceId = v; }
    public UUID getRetryOfRunId() { return retryOfRunId; } public void setRetryOfRunId(UUID v) { retryOfRunId = v; }
    public OffsetDateTime getStartedAt() { return startedAt; } public void setStartedAt(OffsetDateTime v) { startedAt = v; }
    public OffsetDateTime getCancelledAt() { return cancelledAt; } public void setCancelledAt(OffsetDateTime v) { cancelledAt = v; }
    public OffsetDateTime getCompletedAt() { return completedAt; } public void setCompletedAt(OffsetDateTime v) { completedAt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public Integer getDeleted() { return deleted; } public void setDeleted(Integer v) { deleted = v; }
}
