package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;

public class UserMemoryUpdateRequest {

    @NotNull(message = "version is required")
    private Long version;
    private String content;
    private UserMemoryStatus status;
    private OffsetDateTime expiresAt;
    private boolean contentProvided;
    private boolean statusProvided;
    private boolean expiresAtProvided;

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
        this.contentProvided = true;
    }

    public UserMemoryStatus getStatus() {
        return status;
    }

    public void setStatus(UserMemoryStatus status) {
        this.status = status;
        this.statusProvided = true;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
        this.expiresAtProvided = true;
    }

    @JsonIgnore
    public boolean isContentProvided() {
        return contentProvided;
    }

    @JsonIgnore
    public boolean isStatusProvided() {
        return statusProvided;
    }

    @JsonIgnore
    public boolean isExpiresAtProvided() {
        return expiresAtProvided;
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("Unknown response preference field: " + fieldName);
    }
}
