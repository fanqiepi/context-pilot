package io.github.fanqiepi.contextpilot.taskworkspace;

import java.util.UUID;
import java.time.OffsetDateTime;
import com.baomidou.mybatisplus.annotation.*;

@TableName("research_task")
public class TaskEntity {
    @TableId
    private UUID id;
    private UUID ownerId;
    private UUID knowledgeBaseId;
    private UUID clientRequestId;
    private String requestHash;
    private TaskStatus status;
    private long revision;
    private UUID executionId;
    private String checkpoint;
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
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus value) { status = value; }
    public long getRevision() { return revision; }
    public void setRevision(long value) { revision = value; }
    public UUID getExecutionId() { return executionId; }
    public void setExecutionId(UUID value) { executionId = value; }
    public String getCheckpoint() { return checkpoint; }
    public void setCheckpoint(String value) { checkpoint = value; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime value) { createdAt = value; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime value) { updatedAt = value; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer value) { deleted = value; }
}
