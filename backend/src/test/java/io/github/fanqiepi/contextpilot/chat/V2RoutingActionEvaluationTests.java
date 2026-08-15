package io.github.fanqiepi.contextpilot.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.fanqiepi.contextpilot.action.ActionRequestController;
import io.github.fanqiepi.contextpilot.action.ActionExecutorDispatcher;
import io.github.fanqiepi.contextpilot.action.ActionParametersCodec;
import io.github.fanqiepi.contextpilot.action.ActionRequestEntity;
import io.github.fanqiepi.contextpilot.action.ActionRequestMapper;
import io.github.fanqiepi.contextpilot.action.ActionRequestProperties;
import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.action.ActionRequestService;
import io.github.fanqiepi.contextpilot.action.ActionRequestStatus;
import io.github.fanqiepi.contextpilot.action.ActionType;
import io.github.fanqiepi.contextpilot.action.CreateKnowledgeBaseActionParameters;
import io.github.fanqiepi.contextpilot.common.ApiExceptionHandler;
import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.evaluation.V2EvaluationAssets;
import io.github.fanqiepi.contextpilot.evaluation.V2EvaluationConfig;
import io.github.fanqiepi.contextpilot.evaluation.V2EvaluationDataset;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class V2RoutingActionEvaluationTests {

    private static final V2EvaluationDataset DATASET = V2EvaluationAssets.dataset();
    private static final V2EvaluationConfig CONFIG = V2EvaluationAssets.config();

    private final SimpleChatReplyPolicy simpleChatReplyPolicy = new SimpleChatReplyPolicy();
    private final CreateKnowledgeBaseIntentPolicy intentPolicy =
            new CreateKnowledgeBaseIntentPolicy();
    private final CapabilityRouter router = new CapabilityRouter(
            simpleChatReplyPolicy, intentPolicy, new KnowledgeBaseHealthIntentPolicy());

    static Stream<V2EvaluationDataset.RoutingCase> routingCases() {
        return DATASET.routingCases().stream();
    }

    static Stream<V2EvaluationDataset.RoutingCase> clarificationCases() {
        return DATASET.routingCases().stream()
                .filter(testCase -> "CLARIFY".equals(testCase.expectedDecision()));
    }

    static Stream<V2EvaluationDataset.ParameterCase> parameterCases() {
        return DATASET.parameterCases().stream();
    }

    static Stream<V2EvaluationDataset.ApiContractCase> apiContractCases() {
        return DATASET.apiContractCases().stream();
    }

    static Stream<V2EvaluationDataset.LifecycleCase> internalFailureCases() {
        return DATASET.lifecycleCases().stream()
                .filter(testCase -> "INTERNAL_FAILURE".equals(testCase.scenario()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routingCases")
    void evaluatesFixedCapabilityRoutes(V2EvaluationDataset.RoutingCase testCase) {
        for (int repetition = 0; repetition < CONFIG.deterministicRepetitions(); repetition++) {
            CapabilityRoute route = router.route(testCase.input(), "eval-" + testCase.id());

            assertThat(route.capabilityId().name()).isEqualTo(testCase.expectedCapability());
            assertThat(route.capabilityVersion()).isEqualTo(CONFIG.capabilityVersion());
            assertThat(route.matchReason().name()).isEqualTo(testCase.expectedReason());
            assertDecision(testCase);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clarificationCases")
    void evaluatesMissingNameClarification(V2EvaluationDataset.RoutingCase testCase) {
        RetrievalService retrievalService = mock(RetrievalService.class);
        ChatPersistenceService persistenceService = mock(ChatPersistenceService.class);
        ChatPromptComposer promptComposer = mock(ChatPromptComposer.class);
        ActionRequestService actionRequestService = mock(ActionRequestService.class);
        ChatProperties properties = new ChatProperties();
        ChatPreparationService preparationService = new ChatPreparationService(
                retrievalService,
                persistenceService,
                promptComposer,
                properties,
                simpleChatReplyPolicy,
                router,
                intentPolicy,
                actionRequestService,
                mock(io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthService.class),
                mock(io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthReportService.class));
        UUID knowledgeBaseId = UUID.randomUUID();
        PendingChatExchange exchange = new PendingChatExchange(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(persistenceService.begin(
                eq(null), eq(knowledgeBaseId), eq(testCase.input()), any(CapabilityRoute.class)))
                .thenReturn(exchange);

        PreparedChat prepared = preparationService.prepare(
                new ChatRequest(null, knowledgeBaseId, testCase.input()),
                "eval-clarify-" + testCase.id());

        assertThat(prepared.route().capabilityId()).isEqualTo(CapabilityId.BUSINESS_ACTION);
        assertThat(prepared.actionRequired()).isFalse();
        assertThat(prepared.directAnswer())
                .isEqualTo(ChatPreparationService.CREATE_KNOWLEDGE_BASE_NAME_CLARIFICATION);
        verifyNoInteractions(retrievalService, promptComposer, actionRequestService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parameterCases")
    void evaluatesStronglyTypedParameters(V2EvaluationDataset.ParameterCase testCase) {
        String name = materialize(testCase.name(), testCase.nameRepeat());
        String description = materialize(testCase.description(), testCase.descriptionRepeat());

        if (!testCase.expectedValid()) {
            assertThatThrownBy(() -> new CreateKnowledgeBaseActionParameters(name, description))
                    .isInstanceOfSatisfying(BadRequestException.class, exception ->
                            assertThat(exception.getCode()).isEqualTo(testCase.expectedErrorCode()));
            return;
        }

        CreateKnowledgeBaseActionParameters parameters =
                new CreateKnowledgeBaseActionParameters(name, description);
        String expectedName = "INPUT".equals(testCase.nameExpectation())
                ? name : testCase.expectedName();
        String expectedDescription = "INPUT".equals(testCase.descriptionExpectation())
                ? description : testCase.expectedDescription();
        assertThat(parameters.name()).isEqualTo(expectedName);
        assertThat(parameters.description()).isEqualTo(expectedDescription);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("apiContractCases")
    void ignoresClientActionTypeAndParameterTampering(
            V2EvaluationDataset.ApiContractCase testCase) throws Exception {
        ActionRequestService actionRequestService = mock(ActionRequestService.class);
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new ActionRequestController(actionRequestService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
        UUID id = UUID.randomUUID();
        when(actionRequestService.confirm(id)).thenReturn(actionRequest(
                id, testCase.expectedName(), ActionRequestStatus.SUCCEEDED));

        mockMvc.perform(post("/api/action-requests/{id}/confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(testCase.requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionType").value(testCase.expectedActionType()))
                .andExpect(jsonPath("$.parameters.name").value(testCase.expectedName()));

        verify(actionRequestService).confirm(id);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("internalFailureCases")
    void returnsSafeSummaryForUnexpectedExecutionFailure(
            V2EvaluationDataset.LifecycleCase testCase) {
        ActionRequestMapper mapper = mock(ActionRequestMapper.class);
        ActionParametersCodec parametersCodec = new ActionParametersCodec(new ObjectMapper());
        ActionExecutorDispatcher executorDispatcher = mock(ActionExecutorDispatcher.class);
        ActionRequestProperties properties = new ActionRequestProperties();
        ActionRequestService service = new ActionRequestService(
                mapper, properties, parametersCodec, executorDispatcher);
        ActionRequestEntity entity = pendingEntity(parametersCodec);
        when(mapper.expire(eq(entity.getId()), any(OffsetDateTime.class))).thenReturn(0);
        when(mapper.selectById(entity.getId())).thenReturn(entity);
        when(mapper.claimExecution(eq(entity.getId()), any(OffsetDateTime.class))).thenAnswer(invocation -> {
            entity.setStatus(ActionRequestStatus.EXECUTING);
            return 1;
        });
        when(executorDispatcher.execute(
                eq(ActionType.CREATE_KNOWLEDGE_BASE), any(CreateKnowledgeBaseActionParameters.class)))
                .thenThrow(new RuntimeException("simulated internal execution failure"));
        doAnswer(invocation -> {
            entity.setStatus(ActionRequestStatus.FAILED);
            entity.setErrorSummary(invocation.getArgument(1));
            entity.setExecutedAt(invocation.getArgument(2));
            return 1;
        }).when(mapper).completeFailure(
                eq(entity.getId()), any(String.class), any(OffsetDateTime.class));

        ActionRequestResponse response = service.confirm(entity.getId());

        assertThat(response.status().name()).isEqualTo(testCase.expectedStatus());
        assertThat(response.errorSummary())
                .isEqualTo("创建知识库失败，请稍后重试")
                .doesNotContain("simulated", "RuntimeException");
    }

    @Test
    void evaluationAssetsMeetCoverageAndThresholdContracts() {
        assertThat(DATASET.version()).isEqualTo(CONFIG.datasetVersion());
        assertThat(CONFIG.actionType()).isEqualTo(ActionType.CREATE_KNOWLEDGE_BASE.name());
        assertThat(CONFIG.deterministicRepetitions()).isGreaterThanOrEqualTo(2);
        assertThat(allCaseIds()).doesNotHaveDuplicates();
        assertThat(DATASET.routingCases()).extracting(V2EvaluationDataset.RoutingCase::expectedCapability)
                .contains(CapabilityId.SIMPLE_CHAT.name(), CapabilityId.KNOWLEDGE_QA.name(),
                        CapabilityId.BUSINESS_ACTION.name());
        assertThat(DATASET.routingCases()).anySatisfy(testCase ->
                assertThat(testCase.tags()).contains("negative_action"));
        assertThat(DATASET.routingCases()).anySatisfy(testCase ->
                assertThat(testCase.tags()).contains("clarification"));
        assertThat(DATASET.lifecycleCases()).extracting(V2EvaluationDataset.LifecycleCase::scenario)
                .containsExactlyInAnyOrder(
                        "UNCONFIRMED",
                        "REJECTED",
                        "EXPIRED",
                        "REPEATED_CONFIRMATION",
                        "CONCURRENT_CONFIRMATION",
                        "EXECUTION_FAILURE",
                        "HISTORY_RECOVERY",
                        "INTERNAL_FAILURE");

        double routingAccuracy = DATASET.routingCases().stream()
                .filter(this::routeMatches)
                .count() / (double) DATASET.routingCases().size();
        List<V2EvaluationDataset.RoutingCase> negativeActionCases = DATASET.routingCases().stream()
                .filter(testCase -> testCase.tags().contains("negative_action"))
                .toList();
        double falsePositiveRate = negativeActionCases.stream()
                .filter(testCase -> router.route(testCase.input(), "metric").capabilityId()
                        == CapabilityId.BUSINESS_ACTION)
                .count() / (double) negativeActionCases.size();
        double validationPassRate = DATASET.parameterCases().stream()
                .filter(this::parameterCasePasses)
                .count() / (double) DATASET.parameterCases().size();

        assertThat(routingAccuracy).isGreaterThanOrEqualTo(
                CONFIG.thresholds().routingAccuracy());
        assertThat(falsePositiveRate).isLessThanOrEqualTo(
                CONFIG.thresholds().businessActionFalsePositiveRate());
        assertThat(validationPassRate).isGreaterThanOrEqualTo(
                CONFIG.thresholds().parameterValidationPassRate());
        assertThat(CONFIG.thresholds().actionSafetyPassRate()).isEqualTo(1.0);
    }

    private void assertDecision(V2EvaluationDataset.RoutingCase testCase) {
        switch (testCase.expectedDecision()) {
            case "DIRECT_REPLY" -> assertThat(simpleChatReplyPolicy.replyTo(testCase.input()))
                    .isPresent();
            case "PROPOSE" -> assertThat(intentPolicy.parse(testCase.input()))
                    .hasValueSatisfying(intent -> {
                        assertThat(intent.name()).isEqualTo(testCase.expectedName());
                        assertThat(intent.description()).isEqualTo(testCase.expectedDescription());
                    });
            case "CLARIFY" -> assertThat(intentPolicy.parse(testCase.input()))
                    .hasValueSatisfying(intent -> assertThat(intent.name()).isNull());
            case "KNOWLEDGE_QA" -> assertThat(
                    router.route(testCase.input(), "decision").capabilityId())
                    .isEqualTo(CapabilityId.KNOWLEDGE_QA);
            default -> throw new IllegalArgumentException(
                    "Unsupported expected decision " + testCase.expectedDecision());
        }
    }

    private boolean routeMatches(V2EvaluationDataset.RoutingCase testCase) {
        CapabilityRoute route = router.route(testCase.input(), "metric");
        return route.capabilityId().name().equals(testCase.expectedCapability())
                && route.matchReason().name().equals(testCase.expectedReason());
    }

    private boolean parameterCasePasses(V2EvaluationDataset.ParameterCase testCase) {
        String name = materialize(testCase.name(), testCase.nameRepeat());
        String description = materialize(testCase.description(), testCase.descriptionRepeat());
        try {
            CreateKnowledgeBaseActionParameters parameters =
                    new CreateKnowledgeBaseActionParameters(name, description);
            if (!testCase.expectedValid()) {
                return false;
            }
            String expectedName = "INPUT".equals(testCase.nameExpectation())
                    ? name : testCase.expectedName();
            String expectedDescription = "INPUT".equals(testCase.descriptionExpectation())
                    ? description : testCase.expectedDescription();
            return parameters.name().equals(expectedName)
                    && java.util.Objects.equals(parameters.description(), expectedDescription);
        } catch (BadRequestException exception) {
            return !testCase.expectedValid()
                    && exception.getCode().equals(testCase.expectedErrorCode());
        }
    }

    private List<String> allCaseIds() {
        return Stream.of(
                        DATASET.routingCases().stream().map(V2EvaluationDataset.RoutingCase::id),
                        DATASET.parameterCases().stream().map(V2EvaluationDataset.ParameterCase::id),
                        DATASET.apiContractCases().stream().map(V2EvaluationDataset.ApiContractCase::id),
                        DATASET.lifecycleCases().stream().map(V2EvaluationDataset.LifecycleCase::id))
                .flatMap(stream -> stream)
                .toList();
    }

    private ActionRequestEntity pendingEntity(ActionParametersCodec parametersCodec) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-14T08:00:00Z");
        ActionRequestEntity entity = new ActionRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setConversationId(UUID.randomUUID());
        entity.setUserMessageId(UUID.randomUUID());
        entity.setAssistantMessageId(UUID.randomUUID());
        entity.setCapabilityId(CapabilityId.BUSINESS_ACTION);
        entity.setCapabilityVersion(CONFIG.capabilityVersion());
        entity.setActionType(ActionType.CREATE_KNOWLEDGE_BASE);
        entity.setParametersJson(parametersCodec.write(
                ActionType.CREATE_KNOWLEDGE_BASE,
                new CreateKnowledgeBaseActionParameters("Internal failure evaluation", null)));
        entity.setDisplaySummary("确认后将创建知识库“Internal failure evaluation”。");
        entity.setStatus(ActionRequestStatus.PENDING_CONFIRMATION);
        entity.setTraceId("eval-internal-failure");
        entity.setExpiresAt(now.plusMinutes(30));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private String materialize(
            String value,
            V2EvaluationDataset.RepeatValue repeatValue) {
        return repeatValue == null ? value : repeatValue.materialize();
    }

    private ActionRequestResponse actionRequest(
            UUID id,
            String name,
            ActionRequestStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-14T08:00:00Z");
        return new ActionRequestResponse(
                id,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CapabilityId.BUSINESS_ACTION,
                CONFIG.capabilityVersion(),
                ActionType.CREATE_KNOWLEDGE_BASE,
                new CreateKnowledgeBaseActionParameters(name, null),
                "确认后将创建知识库“%s”。".formatted(name),
                status,
                "知识库已创建",
                null,
                "eval-api-trace",
                now.plusMinutes(30),
                now.plusMinutes(1),
                now.plusMinutes(1),
                now,
                now.plusMinutes(1));
    }
}
