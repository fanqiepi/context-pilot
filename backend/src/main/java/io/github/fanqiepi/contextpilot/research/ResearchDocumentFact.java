package io.github.fanqiepi.contextpilot.research;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;

public class ResearchDocumentFact {
    private UUID id;
    private UUID knowledgeBaseId;
    private String originalFilename;
    private DocumentStatus status;
    private String embeddingProfileId;
    private boolean currentVectorPresent;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(UUID knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }
    public String getEmbeddingProfileId() { return embeddingProfileId; }
    public void setEmbeddingProfileId(String embeddingProfileId) { this.embeddingProfileId = embeddingProfileId; }
    public boolean isCurrentVectorPresent() { return currentVectorPresent; }
    public void setCurrentVectorPresent(boolean currentVectorPresent) { this.currentVectorPresent = currentVectorPresent; }
}
