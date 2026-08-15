package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;

@TableName("knowledge_base_health_issue")
public class KnowledgeBaseHealthIssueEntity {

    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID reportId;
    private UUID documentId;
    private String originalFilename;
    private KnowledgeBaseHealthIssueType issueType;
    private HealthIssueSeverity severity;
    private DocumentStatus observedDocumentStatus;
    private int observedProcessingAttempts;
    private String observedErrorSummary;
    private String observedEmbeddingProfileId;
    private Long observedVectorCount;
    private OffsetDateTime sourceDocumentUpdatedAt;
    private HealthRecommendedActionType recommendedActionType;
    private boolean actionEligible;
    private HealthIneligibilityReasonCode ineligibilityReasonCode;
    private String ineligibilitySummary;
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

    public UUID getReportId() {
        return reportId;
    }

    public void setReportId(UUID reportId) {
        this.reportId = reportId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public KnowledgeBaseHealthIssueType getIssueType() {
        return issueType;
    }

    public void setIssueType(KnowledgeBaseHealthIssueType issueType) {
        this.issueType = issueType;
    }

    public HealthIssueSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(HealthIssueSeverity severity) {
        this.severity = severity;
    }

    public DocumentStatus getObservedDocumentStatus() {
        return observedDocumentStatus;
    }

    public void setObservedDocumentStatus(DocumentStatus observedDocumentStatus) {
        this.observedDocumentStatus = observedDocumentStatus;
    }

    public int getObservedProcessingAttempts() {
        return observedProcessingAttempts;
    }

    public void setObservedProcessingAttempts(int observedProcessingAttempts) {
        this.observedProcessingAttempts = observedProcessingAttempts;
    }

    public String getObservedErrorSummary() {
        return observedErrorSummary;
    }

    public void setObservedErrorSummary(String observedErrorSummary) {
        this.observedErrorSummary = observedErrorSummary;
    }

    public String getObservedEmbeddingProfileId() {
        return observedEmbeddingProfileId;
    }

    public void setObservedEmbeddingProfileId(String observedEmbeddingProfileId) {
        this.observedEmbeddingProfileId = observedEmbeddingProfileId;
    }

    public Long getObservedVectorCount() {
        return observedVectorCount;
    }

    public void setObservedVectorCount(Long observedVectorCount) {
        this.observedVectorCount = observedVectorCount;
    }

    public OffsetDateTime getSourceDocumentUpdatedAt() {
        return sourceDocumentUpdatedAt;
    }

    public void setSourceDocumentUpdatedAt(OffsetDateTime sourceDocumentUpdatedAt) {
        this.sourceDocumentUpdatedAt = sourceDocumentUpdatedAt;
    }

    public HealthRecommendedActionType getRecommendedActionType() {
        return recommendedActionType;
    }

    public void setRecommendedActionType(HealthRecommendedActionType recommendedActionType) {
        this.recommendedActionType = recommendedActionType;
    }

    public boolean isActionEligible() {
        return actionEligible;
    }

    public void setActionEligible(boolean actionEligible) {
        this.actionEligible = actionEligible;
    }

    public HealthIneligibilityReasonCode getIneligibilityReasonCode() {
        return ineligibilityReasonCode;
    }

    public void setIneligibilityReasonCode(HealthIneligibilityReasonCode ineligibilityReasonCode) {
        this.ineligibilityReasonCode = ineligibilityReasonCode;
    }

    public String getIneligibilitySummary() {
        return ineligibilitySummary;
    }

    public void setIneligibilitySummary(String ineligibilitySummary) {
        this.ineligibilitySummary = ineligibilitySummary;
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
