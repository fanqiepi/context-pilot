package io.github.fanqiepi.contextpilot.chat;

import java.util.stream.Stream;

import io.github.fanqiepi.contextpilot.evaluation.V5EvaluationAssets;
import io.github.fanqiepi.contextpilot.evaluation.V5EvaluationDataset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class V5ProductionRoutingEvaluationTests {
    private final CreateUserMemoryIntentPolicy policy = new CreateUserMemoryIntentPolicy();
    private final CapabilityRouter router = new CapabilityRouter(new SimpleChatReplyPolicy(),
            new CreateKnowledgeBaseIntentPolicy(), policy, new KnowledgeBaseHealthIntentPolicy());

    static Stream<V5EvaluationDataset.RoutingCase> frozenCases() {
        return V5EvaluationAssets.dataset().routingCases().stream();
    }

    @ParameterizedTest(name = "{index}: frozen production routing")
    @MethodSource("frozenCases")
    void productionMatchesFrozenCreationContract(V5EvaluationDataset.RoutingCase testCase) {
        var actual = policy.evaluate(testCase.materializedInput());
        assertThat(actual.decision().name()).as(testCase.id()).isEqualTo(testCase.expectedDecision());
        assertThat(actual.content()).as(testCase.id()).isEqualTo(testCase.expectedContent());
        var route = router.route(testCase.materializedInput(), "v5-frozen-routing");
        boolean explicit = !testCase.expectedDecision().equals("NOT_TRIGGERED");
        assertThat(route.capabilityId()).as(testCase.id()).isEqualTo(
                explicit ? CapabilityId.BUSINESS_ACTION : CapabilityId.KNOWLEDGE_QA);
        if (explicit) {
            assertThat(route.matchReason()).isEqualTo(CapabilityMatchReason.EXPLICIT_CREATE_USER_MEMORY);
        }
    }
}
