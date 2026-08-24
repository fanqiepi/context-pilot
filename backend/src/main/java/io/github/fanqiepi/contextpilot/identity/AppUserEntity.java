package io.github.fanqiepi.contextpilot.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

public class AppUserEntity {

    private UUID id;
    private String userKey;
    private String displayName;
    private AppUserStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Integer deleted;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUserKey() { return userKey; }
    public void setUserKey(String userKey) { this.userKey = userKey; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public AppUserStatus getStatus() { return status; }
    public void setStatus(AppUserStatus status) { this.status = status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
