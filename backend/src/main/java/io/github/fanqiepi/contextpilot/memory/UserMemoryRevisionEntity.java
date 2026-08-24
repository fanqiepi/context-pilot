package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.util.UUID;

public class UserMemoryRevisionEntity {

    private UUID id;
    private UUID memoryId;
    private Integer revisionNumber;
    private String content;
    private String contentHash;
    private UserMemorySourceType sourceType;
    private UUID sourceConversationId;
    private UUID sourceMessageId;
    private String changeReason;
    private OffsetDateTime createdAt;
    private Integer deleted = 0;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getMemoryId() { return memoryId; }
    public void setMemoryId(UUID memoryId) { this.memoryId = memoryId; }
    public Integer getRevisionNumber() { return revisionNumber; }
    public void setRevisionNumber(Integer revisionNumber) { this.revisionNumber = revisionNumber; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public UserMemorySourceType getSourceType() { return sourceType; }
    public void setSourceType(UserMemorySourceType sourceType) { this.sourceType = sourceType; }
    public UUID getSourceConversationId() { return sourceConversationId; }
    public void setSourceConversationId(UUID sourceConversationId) { this.sourceConversationId = sourceConversationId; }
    public UUID getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(UUID sourceMessageId) { this.sourceMessageId = sourceMessageId; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
