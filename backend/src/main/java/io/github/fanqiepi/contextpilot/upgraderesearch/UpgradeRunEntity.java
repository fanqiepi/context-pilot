package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.UUID;
import java.time.OffsetDateTime;
import com.baomidou.mybatisplus.annotation.*;

@TableName("upgrade_research_run")
public class UpgradeRunEntity {
    @TableId
    private UUID id;
    private UUID ownerId;
    private UUID knowledgeBaseId;
    private UUID clientRequestId;
    private String requestHash;
    private String question;
    private String documentSnapshots;
    private String repositoryKey;
    private String releaseTags;
    private UUID retryOfRunId;
    private String budgetVersion;
    private String promptVersion;
    private String schemaVersion;
    private UpgradeRunStatus status;
    private long revision;
    private OffsetDateTime deadline;
    private String traceId;
    private int modelCalls;
    private int toolRequests;
    private int retrievalCalls;
    private int httpCalls;
    private String draft;
    private String draftHash;
    private String gaps;
    private String errorCode;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableLogic
    private Integer deleted;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID value) { ownerId = value; }
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(UUID value) { knowledgeBaseId = value; }
    public UUID getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(UUID value) { clientRequestId = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public String getQuestion() { return question; }
    public void setQuestion(String value) { question = value; }
    public String getDocumentSnapshots() { return documentSnapshots; }
    public void setDocumentSnapshots(String value) { documentSnapshots = value; }
    public String getRepositoryKey() { return repositoryKey; }
    public void setRepositoryKey(String value) { repositoryKey = value; }
    public String getReleaseTags() { return releaseTags; }
    public void setReleaseTags(String value) { releaseTags = value; }
    public UUID getRetryOfRunId() { return retryOfRunId; }
    public void setRetryOfRunId(UUID value) { retryOfRunId = value; }
    public String getBudgetVersion() { return budgetVersion; }
    public void setBudgetVersion(String value) { budgetVersion = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { promptVersion = value; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public UpgradeRunStatus getStatus() { return status; }
    public void setStatus(UpgradeRunStatus value) { status = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public OffsetDateTime getDeadline() { return deadline; }
    public void setDeadline(OffsetDateTime value) { deadline = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId = value; }
    public int getModelCalls() { return modelCalls; }
    public void setModelCalls(int value) { modelCalls = value; }
    public int getToolRequests() { return toolRequests; }
    public void setToolRequests(int value) { toolRequests = value; }
    public int getRetrievalCalls() { return retrievalCalls; }
    public void setRetrievalCalls(int value) { retrievalCalls = value; }
    public int getHttpCalls() { return httpCalls; }
    public void setHttpCalls(int value) { httpCalls = value; }
    public String getDraft() { return draft; }
    public void setDraft(String value) { draft = value; }
    public String getDraftHash() { return draftHash; }
    public void setDraftHash(String value) { draftHash = value; }
    public String getGaps() { return gaps; }
    public void setGaps(String value) { gaps = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer value) { deleted = value; }
}
