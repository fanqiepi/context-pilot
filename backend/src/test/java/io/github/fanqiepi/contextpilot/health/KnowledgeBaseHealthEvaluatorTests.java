package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseHealthEvaluatorTests {

    private static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-08-15T08:00:00Z");
    private static final EmbeddingIndexProfile CURRENT_PROFILE = new EmbeddingIndexProfile(
            "offline_deterministic_1024_v1", "OFFLINE", "deterministic", 1024, "v1");

    private final KnowledgeBaseHealthEvaluator evaluator = new KnowledgeBaseHealthEvaluator();

    @Test
    void classifiesEmptyHealthyAndInProgressKnowledgeBases() {
        UUID knowledgeBaseId = UUID.randomUUID();

        KnowledgeBaseHealthAssessment empty = evaluate(
                knowledgeBaseId,
                new DocumentStatusCounts(0, 0, 0, 0, 0, 0),
                0,
                true,
                List.of());
        KnowledgeBaseHealthAssessment healthy = evaluate(
                knowledgeBaseId,
                new DocumentStatusCounts(2, 0, 0, 2, 0, 0),
                0,
                true,
                List.of());
        KnowledgeBaseHealthAssessment inProgress = evaluate(
                knowledgeBaseId,
                new DocumentStatusCounts(2, 1, 1, 0, 0, 0),
                0,
                true,
                List.of());

        assertThat(empty.healthStatus()).isEqualTo(KnowledgeBaseHealthStatus.EMPTY);
        assertThat(healthy.healthStatus()).isEqualTo(KnowledgeBaseHealthStatus.HEALTHY);
        assertThat(inProgress.healthStatus()).isEqualTo(KnowledgeBaseHealthStatus.IN_PROGRESS);
        assertThat(inProgress.summary()).contains("2");
    }

    @Test
    void classifiesAllV3IssueTypesAndAppliesActionEligibility() {
        UUID knowledgeBaseId = UUID.randomUUID();
        List<KnowledgeBaseHealthDocumentFact> candidates = List.of(
                fact(DocumentStatus.FAILED, 3, null, null),
                fact(DocumentStatus.SUCCEEDED, 1, null, 0L),
                fact(DocumentStatus.SUCCEEDED, 1, "offline_deterministic_1024_v0", 0L),
                fact(DocumentStatus.SUCCEEDED, 1, CURRENT_PROFILE.id(), 0L));

        KnowledgeBaseHealthAssessment assessment = evaluate(
                knowledgeBaseId,
                new DocumentStatusCounts(4, 0, 0, 3, 1, 0),
                4,
                true,
                candidates);

        assertThat(assessment.healthStatus()).isEqualTo(KnowledgeBaseHealthStatus.ATTENTION_REQUIRED);
        assertThat(assessment.completeness()).isEqualTo(HealthCheckCompleteness.COMPLETE);
        assertThat(assessment.issues())
                .extracting(KnowledgeBaseHealthIssue::issueType)
                .containsExactly(
                        KnowledgeBaseHealthIssueType.DOCUMENT_PROCESSING_FAILED,
                        KnowledgeBaseHealthIssueType.EMBEDDING_PROFILE_UNKNOWN,
                        KnowledgeBaseHealthIssueType.EMBEDDING_PROFILE_OUTDATED,
                        KnowledgeBaseHealthIssueType.VECTOR_INDEX_MISSING);
        assertThat(assessment.issues().getFirst().actionEligible()).isFalse();
        assertThat(assessment.issues().getFirst().ineligibilityReasonCode())
                .isEqualTo(HealthIneligibilityReasonCode.DOCUMENT_RETRY_LIMIT_REACHED);
        assertThat(assessment.issues().subList(1, 4))
                .allMatch(KnowledgeBaseHealthIssue::actionEligible)
                .allMatch(issue -> issue.recommendedActionType() == HealthRecommendedActionType.REINDEX_DOCUMENT);
    }

    @Test
    void marksVectorFailureAsPartialAndDoesNotOfferReindex() {
        KnowledgeBaseHealthDocumentFact outdated = fact(
                DocumentStatus.SUCCEEDED,
                1,
                "offline_deterministic_1024_v0",
                null);

        KnowledgeBaseHealthAssessment assessment = evaluate(
                UUID.randomUUID(),
                new DocumentStatusCounts(1, 0, 0, 1, 0, 0),
                1,
                false,
                List.of(outdated));

        assertThat(assessment.completeness()).isEqualTo(HealthCheckCompleteness.PARTIAL);
        assertThat(assessment.healthStatus()).isEqualTo(KnowledgeBaseHealthStatus.ATTENTION_REQUIRED);
        assertThat(assessment.issues().getFirst().actionEligible()).isFalse();
        assertThat(assessment.issues().getFirst().ineligibilityReasonCode())
                .isEqualTo(HealthIneligibilityReasonCode.VECTOR_INDEX_CHECK_UNAVAILABLE);
    }

    @Test
    void usesUnknownWhenAnIncompleteCheckHasNoOtherKnownState() {
        KnowledgeBaseHealthAssessment assessment = evaluate(
                UUID.randomUUID(),
                new DocumentStatusCounts(1, 0, 0, 1, 0, 0),
                0,
                false,
                List.of());

        assertThat(assessment.completeness()).isEqualTo(HealthCheckCompleteness.PARTIAL);
        assertThat(assessment.healthStatus()).isEqualTo(KnowledgeBaseHealthStatus.UNKNOWN);
    }

    @Test
    void reportsTruncationWithoutChangingTheExactIssueCount() {
        KnowledgeBaseHealthAssessment assessment = evaluate(
                UUID.randomUUID(),
                new DocumentStatusCounts(2, 0, 0, 0, 2, 0),
                2,
                true,
                List.of(fact(DocumentStatus.FAILED, 1, null, null)));

        assertThat(assessment.completeness()).isEqualTo(HealthCheckCompleteness.TRUNCATED);
        assertThat(assessment.issueCount()).isEqualTo(2);
        assertThat(assessment.returnedIssueCount()).isEqualTo(1);
        assertThat(assessment.completenessReason()).contains("1");
    }

    private KnowledgeBaseHealthAssessment evaluate(
            UUID knowledgeBaseId,
            DocumentStatusCounts counts,
            long issueCount,
            boolean vectorCheckComplete,
            List<KnowledgeBaseHealthDocumentFact> candidates) {
        return evaluator.evaluate(
                knowledgeBaseId,
                CURRENT_PROFILE,
                new KnowledgeBaseHealthFacts(
                        DATA_AS_OF,
                        counts,
                        issueCount,
                        vectorCheckComplete,
                        candidates),
                true,
                3);
    }

    private KnowledgeBaseHealthDocumentFact fact(
            DocumentStatus status,
            int processingAttempts,
            String embeddingProfileId,
            Long vectorCount) {
        return new KnowledgeBaseHealthDocumentFact(
                UUID.randomUUID(),
                UUID.randomUUID() + ".txt",
                status,
                processingAttempts,
                status == DocumentStatus.FAILED ? "文档处理失败。" : null,
                embeddingProfileId,
                vectorCount,
                DATA_AS_OF.minusMinutes(1));
    }
}
