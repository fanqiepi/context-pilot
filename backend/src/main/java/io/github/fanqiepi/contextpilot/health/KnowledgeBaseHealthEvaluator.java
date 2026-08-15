package io.github.fanqiepi.contextpilot.health;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProfile;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeBaseHealthEvaluator {

    public KnowledgeBaseHealthAssessment evaluate(
            UUID knowledgeBaseId,
            EmbeddingIndexProfile currentProfile,
            KnowledgeBaseHealthFacts facts,
            boolean documentProcessingEnabled,
            int maximumProcessingAttempts) {
        Objects.requireNonNull(knowledgeBaseId, "Knowledge base id must not be null");
        Objects.requireNonNull(currentProfile, "Current embedding profile must not be null");
        Objects.requireNonNull(facts, "Knowledge base health facts must not be null");
        if (maximumProcessingAttempts <= 0) {
            throw new IllegalArgumentException("Maximum processing attempts must be positive");
        }

        List<KnowledgeBaseHealthIssue> issues = new ArrayList<>(facts.issueCandidates().size());
        for (KnowledgeBaseHealthDocumentFact candidate : facts.issueCandidates()) {
            issues.add(classify(
                    candidate,
                    currentProfile.id(),
                    facts.vectorCheckComplete(),
                    documentProcessingEnabled,
                    maximumProcessingAttempts));
        }

        HealthCheckCompleteness completeness = completeness(facts, issues.size());
        String completenessReason = completenessReason(facts, completeness, issues.size());
        KnowledgeBaseHealthStatus status = healthStatus(facts, completeness);
        String summary = summary(status, facts.issueCount(), issues.size(), facts.documentCounts().inProgress());
        return new KnowledgeBaseHealthAssessment(
                knowledgeBaseId,
                status,
                completeness,
                completenessReason,
                facts.dataAsOf(),
                currentProfile,
                facts.documentCounts(),
                facts.issueCount(),
                issues.size(),
                summary,
                issues);
    }

    private KnowledgeBaseHealthIssue classify(
            KnowledgeBaseHealthDocumentFact fact,
            String currentProfileId,
            boolean vectorCheckComplete,
            boolean documentProcessingEnabled,
            int maximumProcessingAttempts) {
        if (fact.documentStatus() == DocumentStatus.FAILED) {
            Eligibility eligibility = retryEligibility(
                    fact.processingAttempts(), documentProcessingEnabled, maximumProcessingAttempts);
            return issue(
                    fact,
                    KnowledgeBaseHealthIssueType.DOCUMENT_PROCESSING_FAILED,
                    HealthIssueSeverity.ERROR,
                    HealthRecommendedActionType.RETRY_DOCUMENT_PROCESSING,
                    eligibility);
        }
        if (fact.documentStatus() == DocumentStatus.SUCCEEDED && fact.embeddingProfileId() == null) {
            return issue(
                    fact,
                    KnowledgeBaseHealthIssueType.EMBEDDING_PROFILE_UNKNOWN,
                    HealthIssueSeverity.WARNING,
                    HealthRecommendedActionType.REINDEX_DOCUMENT,
                    reindexEligibility(documentProcessingEnabled, vectorCheckComplete));
        }
        if (fact.documentStatus() == DocumentStatus.SUCCEEDED
                && !currentProfileId.equals(fact.embeddingProfileId())) {
            return issue(
                    fact,
                    KnowledgeBaseHealthIssueType.EMBEDDING_PROFILE_OUTDATED,
                    HealthIssueSeverity.WARNING,
                    HealthRecommendedActionType.REINDEX_DOCUMENT,
                    reindexEligibility(documentProcessingEnabled, vectorCheckComplete));
        }
        if (fact.documentStatus() == DocumentStatus.SUCCEEDED
                && currentProfileId.equals(fact.embeddingProfileId())
                && fact.currentProfileVectorCount() != null
                && fact.currentProfileVectorCount() == 0) {
            return issue(
                    fact,
                    KnowledgeBaseHealthIssueType.VECTOR_INDEX_MISSING,
                    HealthIssueSeverity.WARNING,
                    HealthRecommendedActionType.REINDEX_DOCUMENT,
                    reindexEligibility(documentProcessingEnabled, vectorCheckComplete));
        }
        throw new IllegalStateException("Health data port returned a document without a supported health issue");
    }

    private KnowledgeBaseHealthIssue issue(
            KnowledgeBaseHealthDocumentFact fact,
            KnowledgeBaseHealthIssueType issueType,
            HealthIssueSeverity severity,
            HealthRecommendedActionType actionType,
            Eligibility eligibility) {
        return new KnowledgeBaseHealthIssue(
                fact.documentId(),
                fact.originalFilename(),
                issueType,
                severity,
                fact.documentStatus(),
                fact.processingAttempts(),
                fact.errorSummary(),
                fact.embeddingProfileId(),
                fact.currentProfileVectorCount(),
                fact.sourceDocumentUpdatedAt(),
                actionType,
                eligibility.eligible(),
                eligibility.reasonCode(),
                eligibility.summary());
    }

    private Eligibility retryEligibility(
            int processingAttempts,
            boolean documentProcessingEnabled,
            int maximumProcessingAttempts) {
        if (!documentProcessingEnabled) {
            return Eligibility.ineligible(
                    HealthIneligibilityReasonCode.DOCUMENT_PROCESSING_DISABLED,
                    "文档处理当前已关闭。");
        }
        if (processingAttempts >= maximumProcessingAttempts) {
            return Eligibility.ineligible(
                    HealthIneligibilityReasonCode.DOCUMENT_RETRY_LIMIT_REACHED,
                    "文档已达到最大处理次数。");
        }
        return Eligibility.allowed();
    }

    private Eligibility reindexEligibility(boolean documentProcessingEnabled, boolean vectorCheckComplete) {
        if (!documentProcessingEnabled) {
            return Eligibility.ineligible(
                    HealthIneligibilityReasonCode.DOCUMENT_PROCESSING_DISABLED,
                    "文档处理当前已关闭。");
        }
        if (!vectorCheckComplete) {
            return Eligibility.ineligible(
                    HealthIneligibilityReasonCode.VECTOR_INDEX_CHECK_UNAVAILABLE,
                    "向量索引检查当前不可用。");
        }
        return Eligibility.allowed();
    }

    private HealthCheckCompleteness completeness(KnowledgeBaseHealthFacts facts, int returnedIssues) {
        if (!facts.vectorCheckComplete()) {
            return HealthCheckCompleteness.PARTIAL;
        }
        if (facts.issueCount() > returnedIssues) {
            return HealthCheckCompleteness.TRUNCATED;
        }
        return HealthCheckCompleteness.COMPLETE;
    }

    private String completenessReason(
            KnowledgeBaseHealthFacts facts,
            HealthCheckCompleteness completeness,
            int returnedIssues) {
        return switch (completeness) {
            case COMPLETE -> null;
            case TRUNCATED -> "异常明细超过服务端上限，仅返回前 " + returnedIssues + " 条。";
            case PARTIAL -> facts.issueCount() > returnedIssues
                    ? "向量索引存在性检查不可用；已知异常明细仅返回前 " + returnedIssues + " 条。"
                    : "向量索引存在性检查不可用。";
        };
    }

    private KnowledgeBaseHealthStatus healthStatus(
            KnowledgeBaseHealthFacts facts,
            HealthCheckCompleteness completeness) {
        if (facts.issueCount() > 0) {
            return KnowledgeBaseHealthStatus.ATTENTION_REQUIRED;
        }
        if (facts.documentCounts().total() == 0) {
            return completeness == HealthCheckCompleteness.COMPLETE
                    ? KnowledgeBaseHealthStatus.EMPTY
                    : KnowledgeBaseHealthStatus.UNKNOWN;
        }
        if (facts.documentCounts().inProgress() > 0) {
            return KnowledgeBaseHealthStatus.IN_PROGRESS;
        }
        if (completeness != HealthCheckCompleteness.COMPLETE) {
            return KnowledgeBaseHealthStatus.UNKNOWN;
        }
        return KnowledgeBaseHealthStatus.HEALTHY;
    }

    private String summary(
            KnowledgeBaseHealthStatus status,
            long issueCount,
            int returnedIssueCount,
            long inProgressCount) {
        return switch (status) {
            case EMPTY -> "知识库中没有活动文档。";
            case HEALTHY -> "知识库健康，未发现文档处理或索引异常。";
            case IN_PROGRESS -> "知识库当前有 " + inProgressCount + " 个文档正在等待、处理或删除。";
            case ATTENTION_REQUIRED -> "知识库发现 " + issueCount + " 个需要关注的问题，已返回 "
                    + returnedIssueCount + " 条明细。";
            case UNKNOWN -> "健康检查不完整，当前无法确认知识库是否健康。";
        };
    }

    private record Eligibility(
            boolean eligible,
            HealthIneligibilityReasonCode reasonCode,
            String summary) {

        private static Eligibility allowed() {
            return new Eligibility(true, null, null);
        }

        private static Eligibility ineligible(HealthIneligibilityReasonCode reasonCode, String summary) {
            return new Eligibility(false, reasonCode, summary);
        }
    }
}
