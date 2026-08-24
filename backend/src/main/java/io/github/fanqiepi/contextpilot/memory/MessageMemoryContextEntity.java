package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.util.UUID;

public class MessageMemoryContextEntity {
    private UUID id;
    private UUID assistantMessageId;
    private UUID memoryId;
    private UUID memoryRevisionId;
    private Integer displayOrder;
    private Integer contextCharacterCount;
    private Boolean contextTruncated;
    private OffsetDateTime createdAt;
    private Integer deleted = 0;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(UUID assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public UUID getMemoryId() { return memoryId; }
    public void setMemoryId(UUID memoryId) { this.memoryId = memoryId; }
    public UUID getMemoryRevisionId() { return memoryRevisionId; }
    public void setMemoryRevisionId(UUID memoryRevisionId) { this.memoryRevisionId = memoryRevisionId; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    public Integer getContextCharacterCount() { return contextCharacterCount; }
    public void setContextCharacterCount(Integer contextCharacterCount) { this.contextCharacterCount = contextCharacterCount; }
    public Boolean getContextTruncated() { return contextTruncated; }
    public void setContextTruncated(Boolean contextTruncated) { this.contextTruncated = contextTruncated; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
