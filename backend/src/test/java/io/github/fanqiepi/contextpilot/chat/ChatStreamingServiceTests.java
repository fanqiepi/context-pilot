package io.github.fanqiepi.contextpilot.chat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.action.ActionRequestStatus;
import io.github.fanqiepi.contextpilot.action.ActionType;
import io.github.fanqiepi.contextpilot.action.CreateKnowledgeBaseActionParameters;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthReportResponse;
import io.github.fanqiepi.contextpilot.model.ChatModelChunk;
import io.github.fanqiepi.contextpilot.model.ChatModelGateway;
import io.github.fanqiepi.contextpilot.model.ChatModelResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatStreamingServiceTests {

    @Mock
    private ChatPreparationService preparationService;
    @Mock
    private ChatPersistenceService persistenceService;
    @Mock
    private ChatModelGateway chatModelGateway;

    private ChatStreamingService service;

    @BeforeEach
    void setUp() {
        service = new ChatStreamingService(
                preparationService,
                persistenceService,
                chatModelGateway);
    }

    @Test
    void emitsStableSuccessfulEventOrderAndPersistsBeforeDone() {
        ChatRequest request = request();
        PendingChatExchange exchange = exchange();
        ChatCitationResponse citation = citation();
        ChatPrompt prompt = new ChatPrompt("system", "user", "v1");
        UUID modelCallId = UUID.randomUUID();
        when(preparationService.prepare(request, "trace-success"))
                .thenReturn(new PreparedChat(
                        knowledgeRoute("trace-success"), exchange, prompt, List.of(citation)));
        stubModelCall(exchange, modelCallId);
        when(chatModelGateway.stream("system", "user")).thenReturn(Flux.just(
                new ChatModelChunk("Hello ", "deepseek-v4-flash", null, null, null),
                new ChatModelChunk("world", "deepseek-v4-flash", 20, 4, 24)));

        List<ServerSentEvent<ChatStreamPayload>> events = service
                .stream(request, "trace-success")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events.stream().map(ServerSentEvent::event).toList())
                .containsExactly("message", "route", "delta", "delta", "citation", "usage", "done");
        ChatStreamPayload.Message message = (ChatStreamPayload.Message) events.getFirst().data();
        assertThat(message.traceId()).isEqualTo("trace-success");
        assertThat(message.capabilityId()).isEqualTo(CapabilityId.KNOWLEDGE_QA);
        assertThat(message.capabilityVersion()).isEqualTo("v1");
        ChatStreamPayload.Usage usage = (ChatStreamPayload.Usage) events.get(5).data();
        assertThat(usage.totalTokens()).isEqualTo(24);
        ChatStreamPayload.Done done = (ChatStreamPayload.Done) events.getLast().data();
        assertThat(done.traceId()).isEqualTo("trace-success");
        verify(persistenceService).completeSuccess(
                eq(exchange.assistantMessageId()),
                eq(modelCallId),
                eq("Hello world"),
                eq(List.of(citation)),
                eq(new ChatModelResult("Hello world", "deepseek-v4-flash", 20, 4, 24)),
                anyLong());
        verify(persistenceService).beginModelCall(
                exchange.assistantMessageId(),
                "DEEPSEEK",
                "deepseek-v4-flash",
                "v1",
                "trace-success");
    }

    @Test
    void emitsMessageDeltaAndDoneForDeterministicRefusalWithoutModelCall() {
        ChatRequest request = request();
        PendingChatExchange exchange = exchange();
        when(preparationService.prepare(request, "trace-refused"))
                .thenReturn(new PreparedChat(
                        knowledgeRoute("trace-refused"), exchange, null, List.of()));

        List<ServerSentEvent<ChatStreamPayload>> events = service
                .stream(request, "trace-refused")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events.stream().map(ServerSentEvent::event).toList())
                .containsExactly("message", "route", "delta", "done");
        ChatStreamPayload.Done done = (ChatStreamPayload.Done) events.getLast().data();
        assertThat(done.status()).isEqualTo("REFUSED");
        verify(persistenceService).completeWithoutModel(
                exchange.assistantMessageId(),
                ChatApplicationService.INSUFFICIENT_EVIDENCE_ANSWER);
    }

    @Test
    void emitsCompletedDirectAnswerWithoutModelCall() {
        ChatRequest request = request();
        PendingChatExchange exchange = exchange();
        when(preparationService.prepare(request, "trace-direct"))
                .thenReturn(PreparedChat.direct(
                        simpleRoute("trace-direct"),
                        exchange,
                        SimpleChatReplyPolicy.IDENTITY_REPLY));

        List<ServerSentEvent<ChatStreamPayload>> events = service
                .stream(request, "trace-direct")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events.stream().map(ServerSentEvent::event).toList())
                .containsExactly("message", "route", "delta", "done");
        ChatStreamPayload.Delta delta = (ChatStreamPayload.Delta) events.get(2).data();
        assertThat(delta.content()).isEqualTo(SimpleChatReplyPolicy.IDENTITY_REPLY);
        ChatStreamPayload.Done done = (ChatStreamPayload.Done) events.getLast().data();
        assertThat(done.status()).isEqualTo("COMPLETED");
        verify(persistenceService).completeWithoutModel(
                exchange.assistantMessageId(), SimpleChatReplyPolicy.IDENTITY_REPLY);
        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void emitsPersistedHealthReportInStableOrderWithoutModelCall() {
        ChatRequest request = request();
        PendingChatExchange exchange = exchange();
        KnowledgeBaseHealthReportResponse report = mock(KnowledgeBaseHealthReportResponse.class);
        when(report.summary()).thenReturn("知识库健康，未发现异常。");
        PreparedChat prepared = PreparedChat.health(healthRoute("trace-health"), exchange, report);
        when(preparationService.prepare(request, "trace-health"))
                .thenReturn(prepared);

        List<ServerSentEvent<ChatStreamPayload>> events = service
                .stream(request, "trace-health")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events.stream().map(ServerSentEvent::event).toList())
                .containsExactly("message", "route", "delta", "health_report", "done");
        ChatStreamPayload.Route route = (ChatStreamPayload.Route) events.get(1).data();
        assertThat(route.capabilityVersion()).isEqualTo("v2");
        assertThat(route.capabilityMatchReason())
                .isEqualTo(CapabilityMatchReason.EXPLICIT_KNOWLEDGE_BASE_HEALTH);
        assertThat(events.get(3).data()).isSameAs(report);
        assertThat(((ChatStreamPayload.Done) events.getLast().data()).status()).isEqualTo("COMPLETED");
        verifyNoInteractions(persistenceService, chatModelGateway);
    }

    @Test
    void emitsPersistedActionProposalWithoutDeltaOrModelCall() {
        ChatRequest request = request();
        PendingChatExchange exchange = exchange();
        CapabilityRoute route = CapabilityRoute.matched(
                CapabilityId.BUSINESS_ACTION,
                CapabilityMatchReason.EXPLICIT_CREATE_KNOWLEDGE_BASE,
                "trace-action");
        ActionRequestResponse actionRequest = actionRequest(exchange);
        when(preparationService.prepare(request, "trace-action"))
                .thenReturn(PreparedChat.action(
                        route,
                        exchange,
                        ChatPreparationService.BUSINESS_ACTION_PROPOSAL_ANSWER,
                        actionRequest));

        List<ServerSentEvent<ChatStreamPayload>> events = service
                .stream(request, "trace-action")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events.stream().map(ServerSentEvent::event).toList())
                .containsExactly("message", "route", "action_required", "done");
        ChatStreamPayload.ActionRequired required =
                (ChatStreamPayload.ActionRequired) events.get(2).data();
        assertThat(required.actionRequestId()).isEqualTo(actionRequest.id());
        assertThat(required.parameters()).isEqualTo(actionRequest.parameters());
        ChatStreamPayload.Done done = (ChatStreamPayload.Done) events.getLast().data();
        assertThat(done.status()).isEqualTo("AWAITING_CONFIRMATION");
        verifyNoInteractions(chatModelGateway);
    }

    @Test
    void emitsSafeErrorAndPersistsFailedModelCall() {
        ChatRequest request = request();
        PendingChatExchange exchange = exchange();
        ChatPrompt prompt = new ChatPrompt("system", "user", "v1");
        UUID modelCallId = UUID.randomUUID();
        when(preparationService.prepare(request, "trace-error"))
                .thenReturn(new PreparedChat(
                        knowledgeRoute("trace-error"), exchange, prompt, List.of(citation())));
        stubModelCall(exchange, modelCallId);
        when(chatModelGateway.stream("system", "user")).thenReturn(Flux.error(
                new InternalServiceException(
                        "CHAT_MODEL_STREAM_FAILED",
                        "Chat model stream failed",
                        new RuntimeException("private provider detail"))));

        List<ServerSentEvent<ChatStreamPayload>> events = service
                .stream(request, "trace-error")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events.stream().map(ServerSentEvent::event).toList())
                .containsExactly("message", "route", "error");
        ChatStreamPayload.Error error = (ChatStreamPayload.Error) events.getLast().data();
        assertThat(error.code()).isEqualTo("CHAT_MODEL_STREAM_FAILED");
        assertThat(error.message()).doesNotContain("private provider detail");
        assertThat(error.traceId()).isEqualTo("trace-error");
        verify(persistenceService).completeFailure(
                eq(exchange.assistantMessageId()),
                eq(modelCallId),
                anyLong(),
                eq("Chat model stream failed"));
    }

    @Test
    void marksPendingCallFailedWhenClientCancels() {
        ChatRequest request = request();
        PendingChatExchange exchange = exchange();
        ChatPrompt prompt = new ChatPrompt("system", "user", "v1");
        UUID modelCallId = UUID.randomUUID();
        when(preparationService.prepare(request, "trace-cancel"))
                .thenReturn(new PreparedChat(
                        knowledgeRoute("trace-cancel"), exchange, prompt, List.of(citation())));
        stubModelCall(exchange, modelCallId);
        when(chatModelGateway.stream("system", "user")).thenReturn(Flux.never());

        List<String> receivedEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        service.stream(request, "trace-cancel").subscribe(
                new BaseSubscriber<ServerSentEvent<ChatStreamPayload>>() {
                    @Override
                    protected void hookOnNext(ServerSentEvent<ChatStreamPayload> event) {
                        receivedEvents.add(event.event());
                        cancel();
                    }
                });

        assertThat(receivedEvents).containsExactly("message");
        verify(persistenceService, timeout(1000)).completeFailure(
                eq(exchange.assistantMessageId()),
                eq(modelCallId),
                anyLong(),
                eq("Chat stream cancelled by client"));
    }

    private void stubModelCall(PendingChatExchange exchange, UUID modelCallId) {
        when(chatModelGateway.provider()).thenReturn("DEEPSEEK");
        when(chatModelGateway.configuredModel()).thenReturn("deepseek-v4-flash");
        when(persistenceService.beginModelCall(
                eq(exchange.assistantMessageId()),
                eq("DEEPSEEK"),
                eq("deepseek-v4-flash"),
                eq("v1"),
                anyString()))
                .thenReturn(modelCallId);
    }

    private ChatRequest request() {
        return new ChatRequest(null, UUID.randomUUID(), "Question");
    }

    private PendingChatExchange exchange() {
        return new PendingChatExchange(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private ChatCitationResponse citation() {
        return new ChatCitationResponse(
                1,
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "notes.txt",
                0,
                null,
                0.9,
                "Evidence");
    }

    private ActionRequestResponse actionRequest(PendingChatExchange exchange) {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.parse("2026-08-13T08:00:00Z");
        return new ActionRequestResponse(
                UUID.randomUUID(),
                exchange.conversationId(),
                exchange.userMessageId(),
                exchange.assistantMessageId(),
                CapabilityId.BUSINESS_ACTION,
                "v1",
                ActionType.CREATE_KNOWLEDGE_BASE,
                new CreateKnowledgeBaseActionParameters("Java 学习", null),
                "确认后将创建知识库“Java 学习”。",
                ActionRequestStatus.PENDING_CONFIRMATION,
                null,
                null,
                "trace-action",
                now.plusMinutes(30),
                null,
                null,
                now,
                now);
    }

    private CapabilityRoute knowledgeRoute(String traceId) {
        return CapabilityRoute.matched(
                CapabilityId.KNOWLEDGE_QA,
                CapabilityMatchReason.DEFAULT_KNOWLEDGE_QA,
                traceId);
    }

    private CapabilityRoute simpleRoute(String traceId) {
        return CapabilityRoute.matched(
                CapabilityId.SIMPLE_CHAT,
                CapabilityMatchReason.SIMPLE_INTERACTION_WHITELIST,
                traceId);
    }

    private CapabilityRoute healthRoute(String traceId) {
        return CapabilityRoute.matched(
                CapabilityId.KNOWLEDGE_QA,
                "v2",
                CapabilityMatchReason.EXPLICIT_KNOWLEDGE_BASE_HEALTH,
                traceId);
    }
}
