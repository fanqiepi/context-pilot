package io.github.fanqiepi.contextpilot.research;

import java.util.ArrayList;
import java.util.List;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.CapabilityMatchReason;
import io.github.fanqiepi.contextpilot.chat.ChatRequest;
import io.github.fanqiepi.contextpilot.chat.ChatStreamPayload;
import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(prefix = "contextpilot.research", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResearchStreamingService {
    private final ResearchChatService researchChatService;

    public ResearchStreamingService(ResearchChatService researchChatService) {
        this.researchChatService = researchChatService;
    }

    public Flux<ServerSentEvent<ChatStreamPayload>> stream(ChatRequest request, String traceId) {
        return Flux.defer(() -> {
            ResearchStart start = researchChatService.start(request, traceId);
            List<ServerSentEvent<ChatStreamPayload>> head = List.of(
                    event("message", new ChatStreamPayload.Message(
                            start.exchange().conversationId(), start.exchange().userMessageId(),
                            start.exchange().assistantMessageId(), traceId, CapabilityId.KNOWLEDGE_QA,
                            "v4", CapabilityMatchReason.EXPLICIT_DOCUMENT_COMPARISON), 1),
                    event("route", new ChatStreamPayload.Route(
                            CapabilityId.KNOWLEDGE_QA, "v4",
                            CapabilityMatchReason.EXPLICIT_DOCUMENT_COMPARISON, traceId), 2),
                    event("research_plan", new ChatStreamPayload.ResearchPlan(3, start.run()), 3));
            Mono<ResearchExecutionResult> execution = Mono.fromFuture(
                    researchChatService.executeAsync(start.run().id()), true);
            return Flux.concat(Flux.fromIterable(head), execution.flatMapMany(result -> tail(result, traceId)));
        }).onErrorResume(exception -> Flux.just(error(exception, traceId)));
    }

    private Flux<ServerSentEvent<ChatStreamPayload>> tail(
            ResearchExecutionResult result, String traceId) {
        List<ServerSentEvent<ChatStreamPayload>> events = new ArrayList<>();
        long sequence = 4;
        for (ResearchStepResponse step : result.run().steps()) {
            events.add(event("research_step", new ChatStreamPayload.ResearchStep(
                    sequence, result.run().id(), step), sequence++));
        }
        if (result.answer() != null && !result.answer().isBlank()) {
            events.add(event("delta", new ChatStreamPayload.Delta(result.answer()), sequence++));
        }
        for (io.github.fanqiepi.contextpilot.chat.ChatCitationResponse citation : result.citations()) {
            events.add(event("citation", citation, sequence++));
        }
        ResearchUsageResponse usage = result.run().usage();
        if (usage.totalTokens() != null || usage.retrievalCalls() > 0) {
            events.add(event("usage", new ChatStreamPayload.Usage(
                    result.model() == null ? "deterministic" : result.model(),
                    usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), result.latencyMs()),
                    sequence++));
        }
        if (result.run().executionStatus() == ResearchExecutionStatus.FAILED) {
            events.add(event("error", new ChatStreamPayload.Error(
                    result.run().errorCode(), result.run().errorSummary(), traceId), sequence++));
        }
        events.add(event("done", new ChatStreamPayload.Done(
                doneStatus(result.run()), traceId, result.run().id(), sequence), sequence));
        return Flux.fromIterable(events);
    }

    private String doneStatus(ResearchRunResponse run) {
        return switch (run.executionStatus()) {
            case SUCCEEDED -> run.answerStatus() == ResearchAnswerStatus.REFUSED ? "REFUSED" : "COMPLETED";
            case PARTIAL -> "PARTIAL";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            default -> "FAILED";
        };
    }

    private ServerSentEvent<ChatStreamPayload> error(Throwable exception, String traceId) {
        String code = "RESEARCH_DEPENDENCY_UNAVAILABLE";
        String message = "Research request failed";
        if (exception instanceof BadRequestException value) {
            code = value.getCode(); message = value.getMessage();
        } else if (exception instanceof ResourceNotFoundException value) {
            code = value.getCode(); message = value.getMessage();
        } else if (exception instanceof ConflictException value) {
            code = value.getCode(); message = value.getMessage();
        } else if (exception instanceof InternalServiceException value) {
            code = value.getCode(); message = value.getMessage();
        }
        return event("error", new ChatStreamPayload.Error(code, message, traceId));
    }

    private ServerSentEvent<ChatStreamPayload> event(String name, ChatStreamPayload payload) {
        return ServerSentEvent.<ChatStreamPayload>builder(payload).event(name).build();
    }

    private ServerSentEvent<ChatStreamPayload> event(
            String name, ChatStreamPayload payload, long sequence) {
        return ServerSentEvent.<ChatStreamPayload>builder(payload)
                .event(name)
                .id(Long.toString(sequence))
                .build();
    }
}
