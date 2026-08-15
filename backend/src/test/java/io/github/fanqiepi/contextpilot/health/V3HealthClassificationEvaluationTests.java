package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProfile;
import io.github.fanqiepi.contextpilot.evaluation.V3EvaluationAssets;
import io.github.fanqiepi.contextpilot.evaluation.V3EvaluationConfig;
import io.github.fanqiepi.contextpilot.evaluation.V3EvaluationDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class V3HealthClassificationEvaluationTests {

    private static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-08-15T08:00:00Z");
    private static final V3EvaluationDataset DATASET = V3EvaluationAssets.dataset();
    private static final V3EvaluationConfig CONFIG = V3EvaluationAssets.config();
    private static final EmbeddingIndexProfile CURRENT_PROFILE = new EmbeddingIndexProfile(
            CONFIG.embeddingProfileId(), "CONTEXTPILOT", "deterministic-embedding", 1024, "v1");

    private final KnowledgeBaseHealthEvaluator evaluator = new KnowledgeBaseHealthEvaluator();

    static Stream<V3EvaluationDataset.HealthCase> healthCases() {
        return DATASET.healthCases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("healthCases")
    void evaluatesFixedHealthClassificationAndEligibility(V3EvaluationDataset.HealthCase testCase) {
        KnowledgeBaseHealthAssessment assessment = evaluate(testCase);

        assertThat(assessment.healthStatus().name()).isEqualTo(testCase.expectedStatus());
        assertThat(assessment.completeness().name()).isEqualTo(testCase.expectedCompleteness());
        assertThat(assessment.issues())
                .extracting(issue -> issue.issueType().name())
                .containsExactlyElementsOf(testCase.expectedIssueTypes());
        assertThat(assessment.issues().stream().filter(KnowledgeBaseHealthIssue::actionEligible).count())
                .isEqualTo(testCase.expectedEligibleActions());
        assertThat(assessment.dataAsOf()).isEqualTo(DATA_AS_OF);
    }

    @Test
    void meetsClassificationCompletenessAndEligibilityThresholds() {
        assertThat(DATASET.version()).isEqualTo(CONFIG.datasetVersion());
        assertThat(DATASET.healthCases()).extracting(V3EvaluationDataset.HealthCase::id)
                .doesNotHaveDuplicates();
        assertThat(DATASET.lifecycleCoverage()).extracting(V3EvaluationDataset.LifecycleCoverageCase::scenario)
                .containsExactlyInAnyOrder(
                        "POSTGRES_VECTOR_ISOLATION",
                        "IMMUTABLE_HISTORY_RECOVERY",
                        "DUPLICATE_FILENAME_ISSUE_SELECTION",
                        "STALE_PROPOSAL_REJECTED",
                        "UNCONFIRMED_ZERO_SIDE_EFFECT",
                        "CONCURRENT_CONFIRMATION_SINGLE_ACCEPTANCE",
                        "QUEUE_REJECTION_REMAINS_PENDING",
                        "PENDING_RECOVERY_AND_ATOMIC_CLAIM",
                        "V1_V2_REGRESSION");

        double classificationAccuracy = DATASET.healthCases().stream()
                .filter(testCase -> evaluate(testCase).healthStatus().name().equals(testCase.expectedStatus()))
                .count() / (double) DATASET.healthCases().size();
        double completenessAccuracy = DATASET.healthCases().stream()
                .filter(testCase -> evaluate(testCase).completeness().name()
                        .equals(testCase.expectedCompleteness()))
                .count() / (double) DATASET.healthCases().size();
        double eligibilityAccuracy = DATASET.healthCases().stream()
                .filter(testCase -> evaluate(testCase).issues().stream()
                        .filter(KnowledgeBaseHealthIssue::actionEligible)
                        .count() == testCase.expectedEligibleActions())
                .count() / (double) DATASET.healthCases().size();

        assertThat(classificationAccuracy)
                .isGreaterThanOrEqualTo(CONFIG.thresholds().healthClassificationAccuracy());
        assertThat(completenessAccuracy)
                .isGreaterThanOrEqualTo(CONFIG.thresholds().completenessAccuracy());
        assertThat(eligibilityAccuracy)
                .isGreaterThanOrEqualTo(CONFIG.thresholds().repairEligibilityAccuracy());
        assertThat(CONFIG.thresholds().lifecycleSafetyPassRate()).isEqualTo(1.0);
        assertThat(CONFIG.thresholds().historyTraceIntegrityRate()).isEqualTo(1.0);
    }

    private KnowledgeBaseHealthAssessment evaluate(V3EvaluationDataset.HealthCase testCase) {
        V3EvaluationDataset.StatusCounts counts = testCase.counts();
        List<KnowledgeBaseHealthDocumentFact> candidates = testCase.candidates().stream()
                .map(this::candidate)
                .toList();
        return evaluator.evaluate(
                UUID.nameUUIDFromBytes(testCase.id().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                CURRENT_PROFILE,
                new KnowledgeBaseHealthFacts(
                        DATA_AS_OF,
                        new DocumentStatusCounts(
                                counts.total(),
                                counts.pending(),
                                counts.processing(),
                                counts.succeeded(),
                                counts.failed(),
                                counts.deleting()),
                        testCase.issueCount(),
                        testCase.vectorCheckComplete(),
                        candidates),
                testCase.documentProcessingEnabled(),
                testCase.maximumProcessingAttempts());
    }

    private KnowledgeBaseHealthDocumentFact candidate(V3EvaluationDataset.DocumentCandidate candidate) {
        DocumentStatus status = DocumentStatus.valueOf(candidate.status());
        return new KnowledgeBaseHealthDocumentFact(
                UUID.randomUUID(),
                "public-evaluation.txt",
                status,
                candidate.processingAttempts(),
                status == DocumentStatus.FAILED ? "公开评估文档处理失败。" : null,
                candidate.embeddingProfileId(),
                candidate.currentProfileVectorCount(),
                DATA_AS_OF.minusMinutes(1));
    }
}
