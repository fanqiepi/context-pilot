package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.github.fanqiepi.contextpilot.model.ChatModelGateway;
import io.github.fanqiepi.contextpilot.model.ChatModelResult;
import io.github.fanqiepi.contextpilot.observability.AiCallContext;
import io.github.fanqiepi.contextpilot.observability.AiCallLogger;
import org.springframework.stereotype.Service;

@Service
public class ChatApplicationService {

    static final String INSUFFICIENT_EVIDENCE_ANSWER =
            "未在所选知识库中找到足够可靠的依据，无法回答该问题。";

    private final ChatPreparationService preparationService;
    private final ChatPersistenceService persistenceService;
    private final ChatModelGateway chatModelGateway;

    public ChatApplicationService(
            ChatPreparationService preparationService,
            ChatPersistenceService persistenceService,
            ChatModelGateway chatModelGateway) {
        this.preparationService = preparationService;
        this.persistenceService = persistenceService;
        this.chatModelGateway = chatModelGateway;
    }

    public ChatAnswerResponse answer(ChatRequest request, String traceId) {
        PreparedChat prepared = preparationService.prepare(request, traceId);
        PendingChatExchange exchange = prepared.exchange();
        CapabilityRoute route = prepared.route();
        if (prepared.actionRequired()) {
            return new ChatAnswerResponse(
                    exchange.conversationId(),
                    exchange.userMessageId(),
                    exchange.assistantMessageId(),
                    prepared.directAnswer(),
                    false,
                    List.of(),
                    null,
                    null,
                    traceId,
                    route.capabilityId(),
                    route.capabilityVersion(),
                    route.matchReason(),
                    null,
                    prepared.actionRequest());
        }
        if (prepared.healthReportReady()) {
            return new ChatAnswerResponse(
                    exchange.conversationId(),
                    exchange.userMessageId(),
                    exchange.assistantMessageId(),
                    prepared.directAnswer(),
                    false,
                    List.of(),
                    null,
                    null,
                    traceId,
                    route.capabilityId(),
                    route.capabilityVersion(),
                    route.matchReason(),
                    prepared.healthReport(),
                    null);
        }
        if (prepared.hasDirectAnswer()) {
            persistenceService.completeWithoutModel(
                    exchange.assistantMessageId(), prepared.directAnswer());
            return new ChatAnswerResponse(
                    exchange.conversationId(),
                    exchange.userMessageId(),
                    exchange.assistantMessageId(),
                    prepared.directAnswer(),
                    false,
                    List.of(),
                    null,
                    null,
                    traceId,
                    route.capabilityId(),
                    route.capabilityVersion(),
                    route.matchReason(),
                    null,
                    null);
        }
        if (prepared.refused()) {
            persistenceService.completeWithoutModel(
                    exchange.assistantMessageId(), INSUFFICIENT_EVIDENCE_ANSWER);
            return new ChatAnswerResponse(
                    exchange.conversationId(),
                    exchange.userMessageId(),
                    exchange.assistantMessageId(),
                    INSUFFICIENT_EVIDENCE_ANSWER,
                    true,
                    List.of(),
                    null,
                    null,
                    traceId,
                    route.capabilityId(),
                    route.capabilityVersion(),
                    route.matchReason(),
                    null,
                    null);
        }

        ChatPrompt prompt = prepared.prompt();
        UUID modelCallId = persistenceService.beginModelCall(
                exchange.assistantMessageId(),
                chatModelGateway.provider(),
                chatModelGateway.configuredModel(),
                prompt.version(),
                traceId);
        AiCallContext callContext = new AiCallContext(
                "CHAT_COMPLETION", chatModelGateway.provider(), chatModelGateway.configuredModel(),
                traceId, modelCallId, "assistantMessage", exchange.assistantMessageId(), prompt.version(),
                prompt.systemText().length() + prompt.userText().length(), null, null);
        long startNanos = System.nanoTime();
        AiCallLogger.started(callContext);
        ChatModelResult result;
        try {
            result = chatModelGateway.generate(prompt.systemText(), prompt.userText());
        } catch (RuntimeException exception) {
            long latencyMs = elapsedMillis(startNanos);
            AiCallLogger.failed(callContext, latencyMs, exception);
            persistenceService.completeFailure(
                    exchange.assistantMessageId(),
                    modelCallId,
                    latencyMs,
                    "Chat model call failed");
            throw exception;
        }
        long latencyMs = elapsedMillis(startNanos);
        AiCallLogger.succeeded(
                callContext, latencyMs, result.promptTokens(), result.completionTokens(), result.totalTokens(), null);
        List<ChatCitationResponse> citations = prepared.citations();
        persistenceService.completeSuccess(
                exchange.assistantMessageId(),
                modelCallId,
                result.content(),
                citations,
                result,
                latencyMs);
        return new ChatAnswerResponse(
                exchange.conversationId(),
                exchange.userMessageId(),
                exchange.assistantMessageId(),
                result.content(),
                false,
                citations,
                result.model(),
                new ChatUsageResponse(
                        result.promptTokens(),
                        result.completionTokens(),
                        result.totalTokens(),
                        latencyMs),
                traceId,
                route.capabilityId(),
                route.capabilityVersion(),
                route.matchReason(),
                null,
                null);
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
