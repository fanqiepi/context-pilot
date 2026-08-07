package io.github.fanqiepi.contextpilot.chat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.InternalServiceException;
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
import static org.mockito.Mockito.verify;
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
                .thenReturn(new PreparedChat(exchange, prompt, List.of(citation)));
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
                .containsExactly("message", "delta", "delta", "citation", "usage", "done");
        ChatStreamPayload.Message message = (ChatStreamPayload.Message) events.getFirst().data();
        assertThat(message.traceId()).isEqualTo("trace-success");
        ChatStreamPayload.Usage usage = (ChatStreamPayload.Usage) events.get(4).data();
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
                .thenReturn(new PreparedChat(exchange, null, List.of()));

        List<ServerSentEvent<ChatStreamPayload>> events = service
                .stream(request, "trace-refused")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        assertThat(events.stream().map(ServerSentEvent::event).toList())
                .containsExactly("message", "delta", "done");
        ChatStreamPayload.Done done = (ChatStreamPayload.Done) events.getLast().data();
        assertThat(done.status()).isEqualTo("REFUSED");
        verify(persistenceService).completeWithoutModel(
                exchange.assistantMessageId(),
                ChatApplicationService.INSUFFICIENT_EVIDENCE_ANSWER);
    }

    @Test
    void emitsSafeErrorAndPersistsFailedModelCall() {
        ChatRequest request = request();
        PendingChatExchange exchange = exchange();
        ChatPrompt prompt = new ChatPrompt("system", "user", "v1");
        UUID modelCallId = UUID.randomUUID();
        when(preparationService.prepare(request, "trace-error"))
                .thenReturn(new PreparedChat(exchange, prompt, List.of(citation())));
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
                .containsExactly("message", "error");
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
                .thenReturn(new PreparedChat(exchange, prompt, List.of(citation())));
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
}
