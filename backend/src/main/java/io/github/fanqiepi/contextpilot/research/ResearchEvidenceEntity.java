package io.github.fanqiepi.contextpilot.research;

import java.time.OffsetDateTime;
import java.util.UUID;

public class ResearchEvidenceEntity {
    private UUID id; private UUID runId; private UUID documentId; private UUID vectorId;
    private String originalFilename; private int chunkIndex; private Integer pageNumber;
    private String embeddingProfileId; private Double score; private String excerpt;
    private OffsetDateTime createdAt; private OffsetDateTime updatedAt; private Integer deleted = 0;
    public UUID getId() { return id; } public void setId(UUID v) { id = v; }
    public UUID getRunId() { return runId; } public void setRunId(UUID v) { runId = v; }
    public UUID getDocumentId() { return documentId; } public void setDocumentId(UUID v) { documentId = v; }
    public UUID getVectorId() { return vectorId; } public void setVectorId(UUID v) { vectorId = v; }
    public String getOriginalFilename() { return originalFilename; } public void setOriginalFilename(String v) { originalFilename = v; }
    public int getChunkIndex() { return chunkIndex; } public void setChunkIndex(int v) { chunkIndex = v; }
    public Integer getPageNumber() { return pageNumber; } public void setPageNumber(Integer v) { pageNumber = v; }
    public String getEmbeddingProfileId() { return embeddingProfileId; } public void setEmbeddingProfileId(String v) { embeddingProfileId = v; }
    public Double getScore() { return score; } public void setScore(Double v) { score = v; }
    public String getExcerpt() { return excerpt; } public void setExcerpt(String v) { excerpt = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(OffsetDateTime v) { updatedAt = v; }
    public Integer getDeleted() { return deleted; } public void setDeleted(Integer v) { deleted = v; }
}
