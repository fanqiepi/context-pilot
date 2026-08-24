package io.github.fanqiepi.contextpilot.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class V5ExplicitMemoryEvaluationTests {

    private static final V5EvaluationDataset DATASET = V5EvaluationAssets.dataset();
    private static final V5EvaluationConfig CONFIG = V5EvaluationAssets.config();
    private static final V5ExplicitMemoryEvaluationHarness HARNESS =
            new V5ExplicitMemoryEvaluationHarness(CONFIG);

    static Stream<V5EvaluationDataset.RoutingCase> routingCases() {
        return DATASET.routingCases().stream();
    }

    static Stream<V5EvaluationDataset.PreferenceCase> preferenceCases() {
        return DATASET.preferenceCases().stream();
    }

    static Stream<V5EvaluationDataset.SafetyCase> safetyCases() {
        return DATASET.safetyCases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routingCases")
    void explicitCreationContractIsDeterministic(V5EvaluationDataset.RoutingCase testCase) {
        V5ExplicitMemoryEvaluationHarness.RoutingResult result = HARNESS.evaluateRouting(testCase);

        assertThat(result.decision()).isEqualTo(testCase.expectedDecision());
        assertThat(result.content()).isEqualTo(testCase.expectedContent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preferenceCases")
    void boundedCandidateAppliesPreferenceOrCurrentRequest(V5EvaluationDataset.PreferenceCase testCase) {
        V5ExplicitMemoryEvaluationHarness.PreferenceResult result = HARNESS.evaluatePreference(
                testCase, V5ExplicitMemoryEvaluationHarness.PreferenceStrategy.WITH_BOUNDED_MEMORY);

        assertThat(result.actualStyle()).isEqualTo(testCase.expectedStyle());
        assertThat(result.expectedStyleSatisfied()).isTrue();
        if (testCase.overrideExpected()) {
            assertThat(result.currentRequestPrecedenceSatisfied()).isTrue();
            assertThat(result.memoryApplied()).isFalse();
        }
        if (testCase.memoryMode().equals("IGNORE")) {
            assertThat(result.memoryApplied()).isFalse();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("safetyCases")
    void preferenceDataCannotChangeRouteGroundingCitationOrBudget(V5EvaluationDataset.SafetyCase testCase) {
        V5ExplicitMemoryEvaluationHarness.SafetyResult result = HARNESS.evaluateSafety(testCase);

        assertThat(result.route()).isEqualTo(testCase.expectedRoute());
        assertThat(result.disposition()).isEqualTo(testCase.expectedDisposition());
        assertThat(result.citationIds()).containsExactlyElementsOf(testCase.expectedCitationIds());
        assertThat(result.selectedMemories()).hasSize(testCase.expectedSelectedCount());
        assertThat(result.truncated()).isEqualTo(testCase.expectTruncated());
        assertThat(result.withinBudget()).isTrue();
    }

    @Test
    void assetsMeetApprovedCoverageAndBudgetContracts() {
        assertThat(DATASET.version()).isEqualTo(CONFIG.datasetVersion());
        assertThat(CONFIG.intentVersion()).isEqualTo("v1");
        assertThat(CONFIG.preferencePolicyVersion()).isEqualTo("v1");
        assertThat(DATASET.documents()).extracting(V5EvaluationDataset.DocumentFixture::id)
                .containsExactly("DOC-A");
        assertThat(V5EvaluationAssets.corpus(DATASET).get("DOC-A"))
                .contains("本机或可信网络", "10 条", "2000 字符");
        assertThat(DATASET.routingCases()).hasSizeGreaterThanOrEqualTo(12);
        assertThat(DATASET.preferenceCases()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(DATASET.safetyCases()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(DATASET.lifecycleCoverage()).hasSizeGreaterThanOrEqualTo(12);
        assertThat(DATASET.preferenceCases().stream().filter(V5EvaluationDataset.PreferenceCase::overrideExpected))
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(DATASET.preferenceCases())
                .filteredOn(testCase -> testCase.memoryMode().equals("IGNORE"))
                .hasSizeGreaterThanOrEqualTo(1);

        Set<String> allIds = new HashSet<>();
        Stream.of(
                        DATASET.routingCases().stream().map(V5EvaluationDataset.RoutingCase::id),
                        DATASET.preferenceCases().stream().map(V5EvaluationDataset.PreferenceCase::id),
                        DATASET.safetyCases().stream().map(V5EvaluationDataset.SafetyCase::id),
                        DATASET.lifecycleCoverage().stream().map(V5EvaluationDataset.LifecycleCoverageCase::id))
                .flatMap(stream -> stream)
                .forEach(id -> assertThat(allIds.add(id)).as("unique case id %s", id).isTrue());

        assertThat(DATASET.safetyCases())
                .flatExtracting(V5EvaluationDataset.SafetyCase::memoryFixtures)
                .allSatisfy(memory -> assertThat(memory.materializedContent().length())
                        .isBetween(1, CONFIG.budgets().maximumMemoryContentCharacters()));
        assertThat(CONFIG.budgets().maximumStoredMemories()).isEqualTo(100);
        assertThat(CONFIG.budgets().maximumActiveMemories()).isEqualTo(20);
        assertThat(CONFIG.budgets().maximumContextMemories()).isEqualTo(10);
        assertThat(CONFIG.budgets().maximumContextCharacters()).isEqualTo(2000);
        assertThat(DATASET.lifecycleCoverage())
                .extracting(V5EvaluationDataset.LifecycleCoverageCase::scenario)
                .containsExactlyInAnyOrder(
                        "UNCONFIRMED_ZERO_SIDE_EFFECT",
                        "REPEATED_CONFIRMATION_IDEMPOTENT",
                        "CONCURRENT_CONFIRMATION_IDEMPOTENT",
                        "EXACT_DUPLICATE_REUSES_ACTIVE",
                        "CORRECTION_CREATES_REVISION",
                        "DISABLE_EXCLUDES_CONTEXT",
                        "EXPIRY_EXCLUDES_CONTEXT",
                        "LOGICAL_DELETE_EXCLUDES_CONTEXT",
                        "VERSION_CONFLICT",
                        "HISTORY_PINS_REVISION",
                        "OWNER_IS_SERVER_DERIVED",
                        "MEMORY_LIMIT_ENFORCEMENT");

        List<String> allCaseIds = allIds.stream().toList();
        assertThat(CONFIG.representativeCaseIds()).hasSize(8).doesNotHaveDuplicates();
        assertThat(allCaseIds).containsAll(CONFIG.representativeCaseIds());
    }

    @Test
    void boundedCandidateShowsStableBenefitOverNoMemoryBaseline() {
        List<V5ExplicitMemoryEvaluationHarness.PreferenceResult> baseline = evaluatePreferences(
                V5ExplicitMemoryEvaluationHarness.PreferenceStrategy.NO_MEMORY_BASELINE);
        List<V5ExplicitMemoryEvaluationHarness.PreferenceResult> candidate = evaluatePreferences(
                V5ExplicitMemoryEvaluationHarness.PreferenceStrategy.WITH_BOUNDED_MEMORY);

        double baselineAdherence = rate(baseline,
                V5ExplicitMemoryEvaluationHarness.PreferenceResult::expectedStyleSatisfied);
        double candidateAdherence = rate(candidate,
                V5ExplicitMemoryEvaluationHarness.PreferenceResult::expectedStyleSatisfied);
        double overrideAccuracy = DATASET.preferenceCases().stream()
                .filter(V5EvaluationDataset.PreferenceCase::overrideExpected)
                .map(testCase -> HARNESS.evaluatePreference(
                        testCase, V5ExplicitMemoryEvaluationHarness.PreferenceStrategy.WITH_BOUNDED_MEMORY))
                .filter(V5ExplicitMemoryEvaluationHarness.PreferenceResult::currentRequestPrecedenceSatisfied)
                .count() / (double) DATASET.preferenceCases().stream()
                .filter(V5EvaluationDataset.PreferenceCase::overrideExpected)
                .count();

        assertThat(candidateAdherence)
                .isGreaterThanOrEqualTo(CONFIG.thresholds().candidatePreferenceAdherence());
        assertThat((candidateAdherence - baselineAdherence) * 100)
                .isGreaterThanOrEqualTo(CONFIG.thresholds().minimumPreferenceImprovementPoints());
        assertThat(overrideAccuracy)
                .isGreaterThanOrEqualTo(CONFIG.thresholds().currentRequestOverrideAccuracy());
    }

    @Test
    void routingSafetyAndBudgetThresholdsAreFullyMet() {
        double routingAccuracy = DATASET.routingCases().stream()
                .filter(testCase -> HARNESS.evaluateRouting(testCase).decision()
                        .equals(testCase.expectedDecision()))
                .count() / (double) DATASET.routingCases().size();
        List<V5ExplicitMemoryEvaluationHarness.SafetyResult> safetyResults = DATASET.safetyCases().stream()
                .map(HARNESS::evaluateSafety)
                .toList();
        long safeCases = 0;
        for (int index = 0; index < safetyResults.size(); index++) {
            V5ExplicitMemoryEvaluationHarness.SafetyResult result = safetyResults.get(index);
            V5EvaluationDataset.SafetyCase testCase = DATASET.safetyCases().get(index);
            if (result.route().equals(testCase.expectedRoute())
                    && result.disposition().equals(testCase.expectedDisposition())
                    && result.citationIds().equals(testCase.expectedCitationIds())) {
                safeCases++;
            }
        }
        double safetyAccuracy = safeCases / (double) safetyResults.size();

        assertThat(routingAccuracy).isGreaterThanOrEqualTo(CONFIG.thresholds().explicitRoutingAccuracy());
        assertThat(safetyAccuracy).isGreaterThanOrEqualTo(CONFIG.thresholds().safetyIsolationAccuracy());
        assertThat(rate(safetyResults, V5ExplicitMemoryEvaluationHarness.SafetyResult::withinBudget))
                .isGreaterThanOrEqualTo(CONFIG.thresholds().budgetCompliance());
    }

    @Test
    void representativeCasesAreStableAcrossRepetitions() {
        for (String caseId : CONFIG.representativeCaseIds()) {
            Object first = evaluateRepresentative(caseId);
            for (int repetition = 1; repetition < CONFIG.deterministicRepetitions(); repetition++) {
                assertThat(evaluateRepresentative(caseId)).isEqualTo(first);
            }
        }
    }

    private List<V5ExplicitMemoryEvaluationHarness.PreferenceResult> evaluatePreferences(
            V5ExplicitMemoryEvaluationHarness.PreferenceStrategy strategy) {
        return DATASET.preferenceCases().stream()
                .map(testCase -> HARNESS.evaluatePreference(testCase, strategy))
                .toList();
    }

    private Object evaluateRepresentative(String caseId) {
        return DATASET.routingCases().stream()
                .filter(testCase -> testCase.id().equals(caseId))
                .<Object>map(HARNESS::evaluateRouting)
                .findFirst()
                .or(() -> DATASET.preferenceCases().stream()
                        .filter(testCase -> testCase.id().equals(caseId))
                        .<Object>map(testCase -> HARNESS.evaluatePreference(
                                testCase,
                                V5ExplicitMemoryEvaluationHarness.PreferenceStrategy.WITH_BOUNDED_MEMORY))
                        .findFirst())
                .or(() -> DATASET.safetyCases().stream()
                        .filter(testCase -> testCase.id().equals(caseId))
                        .<Object>map(HARNESS::evaluateSafety)
                        .findFirst())
                .orElseThrow();
    }

    private <T> double rate(List<T> results, Predicate<T> predicate) {
        return results.stream().filter(predicate).count() / (double) results.size();
    }
}
