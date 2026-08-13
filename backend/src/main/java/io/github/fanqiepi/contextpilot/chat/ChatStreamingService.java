package io.github.fanqiepi.contextpilot.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.model.ChatModelChunk;
import io.github.fanqiepi.contextpilot.model.ChatModelGateway;
import io.github.fanqiepi.contextpilot.model.ChatModelResult;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.SignalType;

@Service
public class ChatStreamingService {

    private static final String CANCELLED_SUMMARY = "Chat stream cancelled by client";
    private static final String FAILED_SUMMARY = "Chat model stream failed";

    private final ChatPreparationService preparationService;
    private final ChatPersistenceService persistenceService;
    private final ChatModelGateway chatModelGateway;

    public ChatStreamingService(
            ChatPreparationService preparationService,
            ChatPersistenceService persistenceService,
            ChatModelGateway chatModelGateway) {
        this.preparationService = preparationService;
        this.persistenceService = persistenceService;
        this.chatModelGateway = chatModelGateway;
    }

    public Flux<ServerSentEvent<ChatStreamPayload>> stream(ChatRequest request, String traceId) {
        return Flux.defer(() -> buildStream(preparationService.prepare(request, traceId), traceId))
                .onErrorResume(exception -> Flux.just(errorEvent(exception, traceId)));
    }

    private Flux<ServerSentEvent<ChatStreamPayload>> buildStream(
            PreparedChat prepared,
            String traceId) {
        PendingChatExchange exchange = prepared.exchange();
        ServerSentEvent<ChatStreamPayload> message = event(
                "message",
                new ChatStreamPayload.Message(
                        exchange.conversationId(),
                        exchange.userMessageId(),
                        exchange.assistantMessageId(),
                        traceId,
                        prepared.route().capabilityId(),
                        prepared.route().capabilityVersion(),
                        prepared.route().matchReason()));
        ServerSentEvent<ChatStreamPayload> route = event(
                "route",
                new ChatStreamPayload.Route(
                        prepared.route().capabilityId(),
                        prepared.route().capabilityVersion(),
                        prepared.route().matchReason(),
                        traceId));
        if (prepared.actionRequired()) {
            return Flux.just(
                    message,
                    route,
                    event("action_required", ChatStreamPayload.ActionRequired.from(
                            prepared.actionRequest())),
                    event("done", new ChatStreamPayload.Done("AWAITING_CONFIRMATION", traceId)));
        }
        if (prepared.hasDirectAnswer()) {
            persistenceService.completeWithoutModel(
                    exchange.assistantMessageId(), prepared.directAnswer());
            return Flux.just(
                    message,
                    route,
                    event("delta", new ChatStreamPayload.Delta(prepared.directAnswer())),
                    event("done", new ChatStreamPayload.Done("COMPLETED", traceId)));
        }
        if (prepared.refused()) {
            persistenceService.completeWithoutModel(
                    exchange.assistantMessageId(),
                    ChatApplicationService.INSUFFICIENT_EVIDENCE_ANSWER);
            return Flux.just(
                    message,
                    route,
                    event("delta", new ChatStreamPayload.Delta(
                            ChatApplicationService.INSUFFICIENT_EVIDENCE_ANSWER)),
                    event("done", new ChatStreamPayload.Done("REFUSED", traceId)));
        }

        ChatPrompt prompt = prepared.prompt();
        UUID modelCallId = persistenceService.beginModelCall(
                exchange.assistantMessageId(),
                chatModelGateway.provider(),
                chatModelGateway.configuredModel(),
                prompt.version(),
                traceId);
        StreamLifecycle lifecycle = new StreamLifecycle(
                exchange.assistantMessageId(), modelCallId, prepared.citations());

        Flux<ServerSentEvent<ChatStreamPayload>> deltas = chatModelGateway
                .stream(prompt.systemText(), prompt.userText())
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(lifecycle::accept)
                .filter(chunk -> chunk.content() != null && !chunk.content().isEmpty())
                .map(chunk -> event("delta", new ChatStreamPayload.Delta(chunk.content())));

        Flux<ServerSentEvent<ChatStreamPayload>> tail = Flux.defer(() -> {
            ChatModelResult result = lifecycle.result();
            long latencyMs = lifecycle.elapsedMillis();
            lifecycle.complete(result, latencyMs);
            List<ServerSentEvent<ChatStreamPayload>> events = new ArrayList<>();
            for (ChatCitationResponse citation : prepared.citations()) {
                events.add(event("citation", citation));
            }
            events.add(event("usage", new ChatStreamPayload.Usage(
                    result.model(),
                    result.promptTokens(),
                    result.completionTokens(),
                    result.totalTokens(),
                    latencyMs)));
            events.add(event("done", new ChatStreamPayload.Done("COMPLETED", traceId)));
            return Flux.fromIterable(events);
        });

        return Flux.concat(Flux.just(message, route), deltas, tail)
                .onErrorResume(exception -> {
                    lifecycle.failSafely(FAILED_SUMMARY, exception);
                    return Flux.just(errorEvent(exception, traceId));
                })
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        lifecycle.failSafely(CANCELLED_SUMMARY, null);
                    }
                });
    }

    private ServerSentEvent<ChatStreamPayload> errorEvent(Throwable exception, String traceId) {
        String code = "CHAT_STREAM_FAILED";
        String message = "Chat stream failed";
        if (exception instanceof InternalServiceException internal) {
            code = internal.getCode();
            message = internal.getMessage();
        } else if (exception instanceof BadRequestException badRequest) {
            code = badRequest.getCode();
            message = badRequest.getMessage();
        } else if (exception instanceof ResourceNotFoundException notFound) {
            code = notFound.getCode();
            message = notFound.getMessage();
        } else if (exception instanceof ConflictException conflict) {
            code = conflict.getCode();
            message = conflict.getMessage();
        }
        return event("error", new ChatStreamPayload.Error(code, message, traceId));
    }

    private ServerSentEvent<ChatStreamPayload> event(String name, ChatStreamPayload payload) {
        return ServerSentEvent.<ChatStreamPayload>builder(payload).event(name).build();
    }

    private final class StreamLifecycle {

        private final UUID assistantMessageId;
        private final UUID modelCallId;
        private final List<ChatCitationResponse> citations;
        private final long startNanos = System.nanoTime();
        private final StringBuilder answer = new StringBuilder();
        private String model = chatModelGateway.configuredModel();
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
        private boolean terminal;

        private StreamLifecycle(
                UUID assistantMessageId,
                UUID modelCallId,
                List<ChatCitationResponse> citations) {
            this.assistantMessageId = assistantMessageId;
            this.modelCallId = modelCallId;
            this.citations = citations;
        }

        synchronized void accept(ChatModelChunk chunk) {
            if (chunk.content() != null) {
                answer.append(chunk.content());
            }
            if (chunk.model() != null && !chunk.model().isBlank()) {
                model = chunk.model();
            }
            if (chunk.promptTokens() != null) {
                promptTokens = chunk.promptTokens();
            }
            if (chunk.completionTokens() != null) {
                completionTokens = chunk.completionTokens();
            }
            if (chunk.totalTokens() != null) {
                totalTokens = chunk.totalTokens();
            }
        }

        synchronized ChatModelResult result() {
            if (answer.toString().isBlank()) {
                throw new InternalServiceException(
                        "CHAT_MODEL_EMPTY_RESPONSE",
                        "Chat model returned an empty response",
                        new IllegalStateException("Streaming response did not contain answer text"));
            }
            return new ChatModelResult(
                    answer.toString().strip(),
                    model,
                    promptTokens,
                    completionTokens,
                    totalTokens);
        }

        synchronized void complete(ChatModelResult result, long latencyMs) {
            if (terminal) {
                return;
            }
            persistenceService.completeSuccess(
                    assistantMessageId,
                    modelCallId,
                    result.content(),
                    citations,
                    result,
                    latencyMs);
            terminal = true;
        }

        synchronized void failSafely(String summary, Throwable original) {
            if (terminal) {
                return;
            }
            try {
                persistenceService.completeFailure(
                        assistantMessageId,
                        modelCallId,
                        elapsedMillis(),
                        summary);
                terminal = true;
            } catch (RuntimeException persistenceFailure) {
                if (original != null) {
                    original.addSuppressed(persistenceFailure);
                }
            }
        }

        long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        }
    }
}
