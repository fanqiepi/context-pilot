package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.stream.Stream;

import io.github.fanqiepi.contextpilot.evaluation.V3EvaluationAssets;
import io.github.fanqiepi.contextpilot.evaluation.V3EvaluationConfig;
import io.github.fanqiepi.contextpilot.evaluation.V3EvaluationDataset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class V3HealthRoutingEvaluationTests {

    private static final V3EvaluationDataset DATASET = V3EvaluationAssets.dataset();
    private static final V3EvaluationConfig CONFIG = V3EvaluationAssets.config();

    private final CapabilityRouter router = new CapabilityRouter(
            new SimpleChatReplyPolicy(),
            new CreateKnowledgeBaseIntentPolicy(),
            new KnowledgeBaseHealthIntentPolicy());

    static Stream<V3EvaluationDataset.RoutingCase> routingCases() {
        return DATASET.routingCases().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routingCases")
    void evaluatesFixedV3HealthIntentRoutes(V3EvaluationDataset.RoutingCase testCase) {
        for (int repetition = 0; repetition < CONFIG.deterministicRepetitions(); repetition++) {
            CapabilityRoute route = router.route(testCase.input(), "v3-eval-" + testCase.id());

            assertThat(route.capabilityId().name()).isEqualTo(testCase.expectedCapability());
            assertThat(route.capabilityVersion()).isEqualTo(testCase.expectedVersion());
            assertThat(route.matchReason().name()).isEqualTo(testCase.expectedReason());
        }
    }

    @Test
    void meetsHealthIntentAccuracyAndFalsePositiveThresholds() {
        assertThat(DATASET.version()).isEqualTo(CONFIG.datasetVersion());
        assertThat(CONFIG.healthCapabilityVersion()).isEqualTo("v2");
        assertThat(CONFIG.deterministicRepetitions()).isGreaterThanOrEqualTo(2);
        assertThat(DATASET.routingCases()).extracting(V3EvaluationDataset.RoutingCase::id)
                .doesNotHaveDuplicates();

        double accuracy = DATASET.routingCases().stream()
                .filter(this::matchesExpectedRoute)
                .count() / (double) DATASET.routingCases().size();
        List<V3EvaluationDataset.RoutingCase> negativeCases = DATASET.routingCases().stream()
                .filter(testCase -> !testCase.healthIntent())
                .toList();
        double falsePositiveRate = negativeCases.stream()
                .filter(testCase -> router.route(testCase.input(), "v3-metric").matchReason()
                        == CapabilityMatchReason.EXPLICIT_KNOWLEDGE_BASE_HEALTH)
                .count() / (double) negativeCases.size();

        assertThat(accuracy).isGreaterThanOrEqualTo(CONFIG.thresholds().healthIntentAccuracy());
        assertThat(falsePositiveRate)
                .isLessThanOrEqualTo(CONFIG.thresholds().healthIntentFalsePositiveRate());
        assertThat(DATASET.routingCases()).anySatisfy(testCase ->
                assertThat(testCase.tags()).contains("compound"));
    }

    private boolean matchesExpectedRoute(V3EvaluationDataset.RoutingCase testCase) {
        CapabilityRoute route = router.route(testCase.input(), "v3-metric");
        return route.capabilityId().name().equals(testCase.expectedCapability())
                && route.capabilityVersion().equals(testCase.expectedVersion())
                && route.matchReason().name().equals(testCase.expectedReason());
    }
}
