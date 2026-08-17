package io.github.fanqiepi.contextpilot.research;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ResearchStepEntity {
    private UUID id; private UUID runId; private int ordinal; private String goal; private String query;
    private String documentIdsJson; private ResearchStepStatus status; private int hitCount;
    private int retainedEvidenceCount; private Long latencyMs; private String errorSummary;
    private OffsetDateTime createdAt; private OffsetDateTime updatedAt; private Integer deleted = 0;
    public UUID getId() { return id; } public void setId(UUID v) { id = v; }
    public UUID getRunId() { return runId; } public void setRunId(UUID v) { runId = v; }
    public int getOrdinal() { return ordinal; } public void setOrdinal(int v) { ordinal = v; }
    public String getGoal() { return goal; } public void setGoal(String v) { goal = v; }
    public String getQuery() { return query; } public void setQuery(String v) { query = v; }
    public String getDocumentIdsJson() { return documentIdsJson; } public void setDocumentIdsJson(String v) { documentIdsJson = v; }
    public ResearchStepStatus getStatus() { return status; } public void setStatus(ResearchStepStatus v) { status = v; }
    public int getHitCount() { return hitCount; } public void setHitCount(int v) { hitCount = v; }
    public int getRetainedEvidenceCount() { return retainedEvidenceCount; } public void setRetainedEvidenceCount(int v) { retainedEvidenceCount = v; }
    public Long getLatencyMs() { return latencyMs; } public void setLatencyMs(Long v) { latencyMs = v; }
    public String getErrorSummary() { return errorSummary; } public void setErrorSummary(String v) { errorSummary = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public Integer getDeleted() { return deleted; } public void setDeleted(Integer v) { deleted = v; }
}
