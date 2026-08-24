package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.util.UUID;

public class UserMemoryEntity {

    private UUID id;
    private UUID ownerUserId;
    private UserMemoryType memoryType;
    private UserMemoryScope scopeType;
    private UserMemoryStatus status;
    private Integer currentRevision;
    private String contentHash;
    private OffsetDateTime expiresAt;
    private Long version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted = 0;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }
    public UserMemoryType getMemoryType() { return memoryType; }
    public void setMemoryType(UserMemoryType memoryType) { this.memoryType = memoryType; }
    public UserMemoryScope getScopeType() { return scopeType; }
    public void setScopeType(UserMemoryScope scopeType) { this.scopeType = scopeType; }
    public UserMemoryStatus getStatus() { return status; }
    public void setStatus(UserMemoryStatus status) { this.status = status; }
    public Integer getCurrentRevision() { return currentRevision; }
    public void setCurrentRevision(Integer currentRevision) { this.currentRevision = currentRevision; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
