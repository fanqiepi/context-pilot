package io.github.fanqiepi.contextpilot.researchreview;

import java.util.UUID;
import java.time.OffsetDateTime;
import com.baomidou.mybatisplus.annotation.*;

@TableName("research_review")
public class ReviewEntity {
    @TableId
    private UUID id;
    private UUID ownerId;
    private UUID taskId;
    private long taskRevision;
    private int round;
    private UUID clientRequestId;
    private String requestHash;
    private String snapshotHash;
    private String snapshotJson;
    private ReviewData.Status status;
    private ReviewData.Overall overallVerdict;
    private String resultJson;
    private long revision;
    private UUID executionId;
    private String errorCode;
    private String traceId;
    private String promptVersion;
    private String model;
    private String usageJson;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableLogic
    private Integer deleted;
    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getOwnerId() { return ownerId; }
    public void setOwnerId(UUID value) { ownerId = value; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID value) { taskId = value; }
    public long getTaskRevision() { return taskRevision; }
    public void setTaskRevision(long value) { taskRevision = value; }
    public int getRound() { return round; }
    public void setRound(int value) { round = value; }
    public UUID getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(UUID value) { clientRequestId = value; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String value) { requestHash = value; }
    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String value) { snapshotHash = value; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String value) { snapshotJson = value; }
    public ReviewData.Status getStatus() { return status; }
    public void setStatus(ReviewData.Status value) { status = value; }
    public ReviewData.Overall getOverallVerdict() { return overallVerdict; }
    public void setOverallVerdict(ReviewData.Overall value) { overallVerdict = value; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String value) { resultJson = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public UUID getExecutionId() { return executionId; }
    public void setExecutionId(UUID value) { executionId = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String value) { traceId = value; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String value) { promptVersion = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getUsageJson() { return usageJson; }
    public void setUsageJson(String value) { usageJson = value; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime value) { startedAt = value; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime value) { completedAt = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer value) { deleted = value; }
}
