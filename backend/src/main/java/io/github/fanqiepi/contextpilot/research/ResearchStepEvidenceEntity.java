package io.github.fanqiepi.contextpilot.research;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ResearchStepEvidenceEntity {
    private UUID id; private UUID stepId; private UUID evidenceId; private int rankIndex;
    private Double score; private OffsetDateTime createdAt; private OffsetDateTime updatedAt; private Integer deleted = 0;
    public UUID getId() { return id; } public void setId(UUID v) { id = v; }
    public UUID getStepId() { return stepId; } public void setStepId(UUID v) { stepId = v; }
    public UUID getEvidenceId() { return evidenceId; } public void setEvidenceId(UUID v) { evidenceId = v; }
    public int getRankIndex() { return rankIndex; } public void setRankIndex(int v) { rankIndex = v; }
    public Double getScore() { return score; } public void setScore(Double v) { score = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public Integer getDeleted() { return deleted; } public void setDeleted(Integer v) { deleted = v; }
}
