package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.fanqiepi.contextpilot.chat.CapabilityId;

@TableName("knowledge_base_health_report")
public class KnowledgeBaseHealthReportEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID knowledgeBaseId;
    private UUID conversationId;
    private UUID userMessageId;
    private UUID assistantMessageId;
    private CapabilityId capabilityId;
    private String capabilityVersion;
    private KnowledgeBaseHealthStatus healthStatus;
    private HealthCheckCompleteness completeness;
    private String completenessReason;
    private OffsetDateTime dataAsOf;
    private String embeddingProfileId;
    private String embeddingProvider;
    private String embeddingModel;
    private int embeddingDimensions;
    private String embeddingProfileVersion;
    private long documentTotalCount;
    private long documentPendingCount;
    private long documentProcessingCount;
    private long documentSucceededCount;
    private long documentFailedCount;
    private long documentDeletingCount;
    private long issueCount;
    private int returnedIssueCount;
    private String summary;
    private String traceId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @TableLogic(value = "0", delval = "1")
    private Integer deleted = 0;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(UUID knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public UUID getUserMessageId() {
        return userMessageId;
    }

    public void setUserMessageId(UUID userMessageId) {
        this.userMessageId = userMessageId;
    }

    public UUID getAssistantMessageId() {
        return assistantMessageId;
    }

    public void setAssistantMessageId(UUID assistantMessageId) {
        this.assistantMessageId = assistantMessageId;
    }

    public CapabilityId getCapabilityId() {
        return capabilityId;
    }

    public void setCapabilityId(CapabilityId capabilityId) {
        this.capabilityId = capabilityId;
    }

    public String getCapabilityVersion() {
        return capabilityVersion;
    }

    public void setCapabilityVersion(String capabilityVersion) {
        this.capabilityVersion = capabilityVersion;
    }

    public KnowledgeBaseHealthStatus getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(KnowledgeBaseHealthStatus healthStatus) {
        this.healthStatus = healthStatus;
    }

    public HealthCheckCompleteness getCompleteness() {
        return completeness;
    }

    public void setCompleteness(HealthCheckCompleteness completeness) {
        this.completeness = completeness;
    }

    public String getCompletenessReason() {
        return completenessReason;
    }

    public void setCompletenessReason(String completenessReason) {
        this.completenessReason = completenessReason;
    }

    public OffsetDateTime getDataAsOf() {
        return dataAsOf;
    }

    public void setDataAsOf(OffsetDateTime dataAsOf) {
        this.dataAsOf = dataAsOf;
    }

    public String getEmbeddingProfileId() {
        return embeddingProfileId;
    }

    public void setEmbeddingProfileId(String embeddingProfileId) {
        this.embeddingProfileId = embeddingProfileId;
    }

    public String getEmbeddingProvider() {
        return embeddingProvider;
    }

    public void setEmbeddingProvider(String embeddingProvider) {
        this.embeddingProvider = embeddingProvider;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int embeddingDimensions) {
        this.embeddingDimensions = embeddingDimensions;
    }

    public String getEmbeddingProfileVersion() {
        return embeddingProfileVersion;
    }

    public void setEmbeddingProfileVersion(String embeddingProfileVersion) {
        this.embeddingProfileVersion = embeddingProfileVersion;
    }

    public long getDocumentTotalCount() {
        return documentTotalCount;
    }

    public void setDocumentTotalCount(long documentTotalCount) {
        this.documentTotalCount = documentTotalCount;
    }

    public long getDocumentPendingCount() {
        return documentPendingCount;
    }

    public void setDocumentPendingCount(long documentPendingCount) {
        this.documentPendingCount = documentPendingCount;
    }

    public long getDocumentProcessingCount() {
        return documentProcessingCount;
    }

    public void setDocumentProcessingCount(long documentProcessingCount) {
        this.documentProcessingCount = documentProcessingCount;
    }

    public long getDocumentSucceededCount() {
        return documentSucceededCount;
    }

    public void setDocumentSucceededCount(long documentSucceededCount) {
        this.documentSucceededCount = documentSucceededCount;
    }

    public long getDocumentFailedCount() {
        return documentFailedCount;
    }

    public void setDocumentFailedCount(long documentFailedCount) {
        this.documentFailedCount = documentFailedCount;
    }

    public long getDocumentDeletingCount() {
        return documentDeletingCount;
    }

    public void setDocumentDeletingCount(long documentDeletingCount) {
        this.documentDeletingCount = documentDeletingCount;
    }

    public long getIssueCount() {
        return issueCount;
    }

    public void setIssueCount(long issueCount) {
        this.issueCount = issueCount;
    }

    public int getReturnedIssueCount() {
        return returnedIssueCount;
    }

    public void setReturnedIssueCount(int returnedIssueCount) {
        this.returnedIssueCount = returnedIssueCount;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
