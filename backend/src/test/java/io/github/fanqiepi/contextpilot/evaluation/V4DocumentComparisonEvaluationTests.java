package io.github.fanqiepi.contextpilot.evaluation;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class V4DocumentComparisonEvaluationTests {

    private static final V4EvaluationDataset DATASET = V4EvaluationAssets.dataset();
    private static final V4EvaluationConfig CONFIG = V4EvaluationAssets.config();
    private static final V4ResearchEvaluationHarness HARNESS =
            new V4ResearchEvaluationHarness(DATASET, CONFIG);

    static Stream<V4EvaluationDataset.SemanticCase> semanticCases() {
        return DATASET.semanticCases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("semanticCases")
    void boundedCandidateHonorsExpectedDispositionScopeAndBudget(
            V4EvaluationDataset.SemanticCase testCase) {
        V4ResearchEvaluationHarness.EvaluationResult result = HARNESS.evaluate(
                testCase, V4ResearchEvaluationHarness.Strategy.BOUNDED_PLAN_AND_EXECUTE);

        assertThat(result.disposition()).isEqualTo(testCase.expectedDisposition());
        assertThat(result.taskComplete()).isTrue();
        assertThat(result.planSchemaValid()).isTrue();
        assertThat(result.rangeIsolated()).isTrue();
        assertThat(result.withinBudget()).isTrue();
        assertThat(result.missingStatements()).containsAll(testCase.expectedMissingEvidence());
    }

    @Test
    void assetsMeetFixedCoverageAndLifecycleContracts() {
        assertThat(DATASET.version()).isEqualTo(CONFIG.datasetVersion());
        assertThat(CONFIG.planVersion()).isEqualTo("v1");
        assertThat(DATASET.documents()).extracting(V4EvaluationDataset.DocumentFixture::id)
                .doesNotHaveDuplicates();
        assertThat(DATASET.semanticCases()).extracting(V4EvaluationDataset.SemanticCase::id)
                .doesNotHaveDuplicates();
        assertThat(DATASET.semanticCases()).hasSize(32);
        assertThat(categoryCount()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "CONTROL", 8L,
                "MULTI_STEP", 12L,
                "PARTIAL_MISSING", 4L,
                "GLOBAL_UNGROUNDED", 4L,
                "ISOLATION_INJECTION", 4L));
        assertThat(DATASET.lifecycleCoverage()).hasSizeGreaterThanOrEqualTo(12);
        assertThat(DATASET.lifecycleCoverage())
                .extracting(V4EvaluationDataset.LifecycleCoverageCase::scenario)
                .containsExactlyInAnyOrder(
                        "DOCUMENT_BECOMES_INELIGIBLE",
                        "PLAN_REPAIR_FAILURE",
                        "SINGLE_DOCUMENT_RETRIEVAL_FAILURE",
                        "PLANNER_DEPENDENCY_FAILURE",
                        "SYNTHESIZER_FAILURE",
                        "HARD_TIMEOUT",
                        "USER_CANCELLATION",
                        "IDEMPOTENT_DUPLICATE_REQUEST",
                        "SSE_DISCONNECT_CONTINUES",
                        "HISTORY_RECOVERY",
                        "APPLICATION_RESTART_CONVERGES_FAILED",
                        "RETRY_CREATES_LINKED_RUN");
        assertThat(CONFIG.representativeCaseIds()).hasSize(8).doesNotHaveDuplicates();
        assertThat(DATASET.semanticCases().stream().map(V4EvaluationDataset.SemanticCase::id).toList())
                .containsAll(CONFIG.representativeCaseIds());
        assertThat(V4EvaluationAssets.corpus(DATASET)).hasSize(DATASET.documents().size());
        assertThat(CONFIG.budgets().minimumDocuments()).isEqualTo(2);
        assertThat(CONFIG.budgets().maximumDocuments()).isEqualTo(5);
        assertThat(CONFIG.budgets().maximumPlanSteps()).isEqualTo(4);
        assertThat(CONFIG.budgets().maximumRetrievalCalls()).isEqualTo(20);
        assertThat(CONFIG.budgets().perDocumentTopK()).isEqualTo(3);
        assertThat(CONFIG.budgets().maximumRawHits()).isEqualTo(60);
        assertThat(CONFIG.budgets().maximumEvidenceChunks()).isEqualTo(24);
        assertThat(CONFIG.budgets().maximumEvidenceCharacters()).isEqualTo(24000);
        assertThat(CONFIG.budgets().maximumExcerptCharacters()).isEqualTo(1000);
        assertThat(CONFIG.budgets().maximumStepEvidenceCharacters()).isEqualTo(8000);
        assertThat(CONFIG.budgets().maximumModelCalls()).isEqualTo(3);
        assertThat(CONFIG.budgets().hardTimeoutMillis()).isEqualTo(90000);
    }

    @Test
    void candidateMeetsQualitySafetyAndImprovementThresholds() {
        List<V4EvaluationDataset.SemanticCase> explicitCases = cases(testCase -> testCase.explicitResearch());
        List<V4EvaluationDataset.SemanticCase> multiStepCases = cases("MULTI_STEP");
        List<V4EvaluationDataset.SemanticCase> controlCases = cases("CONTROL");
        List<V4EvaluationDataset.SemanticCase> missingAndRefusalCases = cases(testCase ->
                testCase.category().equals("PARTIAL_MISSING")
                        || testCase.category().equals("GLOBAL_UNGROUNDED"));

        List<V4ResearchEvaluationHarness.EvaluationResult> candidate = evaluate(
                explicitCases, V4ResearchEvaluationHarness.Strategy.BOUNDED_PLAN_AND_EXECUTE);
        List<V4ResearchEvaluationHarness.EvaluationResult> multiCandidate = evaluate(
                multiStepCases, V4ResearchEvaluationHarness.Strategy.BOUNDED_PLAN_AND_EXECUTE);
        List<V4ResearchEvaluationHarness.EvaluationResult> multiSingleTurn = evaluate(
                multiStepCases, V4ResearchEvaluationHarness.Strategy.SINGLE_TURN_RAG);
        List<V4ResearchEvaluationHarness.EvaluationResult> controlCandidate = evaluate(
                controlCases, V4ResearchEvaluationHarness.Strategy.BOUNDED_PLAN_AND_EXECUTE);
        List<V4ResearchEvaluationHarness.EvaluationResult> controlSingleTurn = evaluate(
                controlCases, V4ResearchEvaluationHarness.Strategy.SINGLE_TURN_RAG);
        List<V4ResearchEvaluationHarness.EvaluationResult> missingAndRefusal = evaluate(
                missingAndRefusalCases, V4ResearchEvaluationHarness.Strategy.BOUNDED_PLAN_AND_EXECUTE);

        double multiCandidateCompletion = rate(multiCandidate, V4ResearchEvaluationHarness.EvaluationResult::taskComplete);
        double multiSingleTurnCompletion = rate(multiSingleTurn, V4ResearchEvaluationHarness.EvaluationResult::taskComplete);
        double controlCandidateCompletion = rate(controlCandidate, V4ResearchEvaluationHarness.EvaluationResult::taskComplete);
        double controlSingleTurnCompletion = rate(controlSingleTurn, V4ResearchEvaluationHarness.EvaluationResult::taskComplete);

        assertThat(rate(candidate, V4ResearchEvaluationHarness.EvaluationResult::planSchemaValid))
                .isGreaterThanOrEqualTo(CONFIG.thresholds().planSchemaValidity());
        assertThat(multiCandidateCompletion)
                .isGreaterThanOrEqualTo(CONFIG.thresholds().multiStepTaskCompletion());
        assertThat((multiCandidateCompletion - multiSingleTurnCompletion) * 100)
                .isGreaterThanOrEqualTo(CONFIG.thresholds().multiStepImprovementPoints());
        assertThat(average(multiCandidate, V4ResearchEvaluationHarness.EvaluationResult::dimensionCoverage))
                .isGreaterThanOrEqualTo(CONFIG.thresholds().comparisonDimensionCoverage());
        assertThat(average(candidate, V4ResearchEvaluationHarness.EvaluationResult::citationCorrectness))
                .isGreaterThanOrEqualTo(CONFIG.thresholds().citationCorrectness());
        assertThat(average(candidate, V4ResearchEvaluationHarness.EvaluationResult::citationCoverage))
                .isGreaterThanOrEqualTo(CONFIG.thresholds().citationCoverage());
        assertThat(average(candidate, V4ResearchEvaluationHarness.EvaluationResult::citationCoverage))
                .isGreaterThanOrEqualTo(CONFIG.thresholds().selectedDocumentCoverage());
        assertThat(average(candidate, V4ResearchEvaluationHarness.EvaluationResult::answerFaithfulness))
                .isGreaterThanOrEqualTo(CONFIG.thresholds().answerFaithfulness());
        assertThat(rate(missingAndRefusal, V4ResearchEvaluationHarness.EvaluationResult::taskComplete))
                .isGreaterThanOrEqualTo(CONFIG.thresholds().missingAndRefusalCorrectness());
        assertThat((controlSingleTurnCompletion - controlCandidateCompletion) * 100)
                .isLessThanOrEqualTo(CONFIG.thresholds().maximumControlQualityDropPoints());
        assertThat(rate(candidate, V4ResearchEvaluationHarness.EvaluationResult::rangeIsolated))
                .isGreaterThanOrEqualTo(CONFIG.thresholds().rangeIsolation());
        assertThat(rate(candidate, V4ResearchEvaluationHarness.EvaluationResult::withinBudget))
                .isGreaterThanOrEqualTo(CONFIG.thresholds().budgetCompliance());
    }

    @Test
    void explicitEntryAndRepresentativeRepetitionsAreDeterministic() {
        double entryAccuracy = DATASET.semanticCases().stream().filter(testCase -> {
            V4ResearchEvaluationHarness.EvaluationResult result = HARNESS.evaluate(
                    testCase, V4ResearchEvaluationHarness.Strategy.BOUNDED_PLAN_AND_EXECUTE);
            return testCase.explicitResearch()
                    ? !result.disposition().equals("NOT_TRIGGERED")
                    : result.disposition().equals("NOT_TRIGGERED");
        }).count() / (double) DATASET.semanticCases().size();
        assertThat(entryAccuracy).isGreaterThanOrEqualTo(CONFIG.thresholds().explicitEntryAccuracy());

        for (String caseId : CONFIG.representativeCaseIds()) {
            V4EvaluationDataset.SemanticCase testCase = DATASET.semanticCases().stream()
                    .filter(candidate -> candidate.id().equals(caseId))
                    .findFirst()
                    .orElseThrow();
            V4ResearchEvaluationHarness.EvaluationResult first = HARNESS.evaluate(
                    testCase, V4ResearchEvaluationHarness.Strategy.BOUNDED_PLAN_AND_EXECUTE);
            for (int repetition = 1; repetition < CONFIG.deterministicRepetitions(); repetition++) {
                assertThat(HARNESS.evaluate(
                        testCase, V4ResearchEvaluationHarness.Strategy.BOUNDED_PLAN_AND_EXECUTE))
                        .isEqualTo(first);
            }
        }
    }

    private Map<String, Long> categoryCount() {
        return DATASET.semanticCases().stream().collect(java.util.stream.Collectors.groupingBy(
                V4EvaluationDataset.SemanticCase::category,
                java.util.stream.Collectors.counting()));
    }

    private List<V4EvaluationDataset.SemanticCase> cases(String category) {
        return cases(testCase -> testCase.category().equals(category));
    }

    private List<V4EvaluationDataset.SemanticCase> cases(
            Predicate<V4EvaluationDataset.SemanticCase> predicate) {
        return DATASET.semanticCases().stream().filter(predicate).toList();
    }

    private List<V4ResearchEvaluationHarness.EvaluationResult> evaluate(
            List<V4EvaluationDataset.SemanticCase> cases,
            V4ResearchEvaluationHarness.Strategy strategy) {
        return cases.stream().map(testCase -> HARNESS.evaluate(testCase, strategy)).toList();
    }

    private double rate(
            List<V4ResearchEvaluationHarness.EvaluationResult> results,
            Predicate<V4ResearchEvaluationHarness.EvaluationResult> predicate) {
        return results.stream().filter(predicate).count() / (double) results.size();
    }

    private double average(
            List<V4ResearchEvaluationHarness.EvaluationResult> results,
            java.util.function.ToDoubleFunction<V4ResearchEvaluationHarness.EvaluationResult> metric) {
        return results.stream().mapToDouble(metric).average().orElseThrow();
    }
}
