package io.github.fanqiepi.contextpilot.research;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.CapabilityMatchReason;
import io.github.fanqiepi.contextpilot.chat.ChatAnswerResponse;
import io.github.fanqiepi.contextpilot.chat.ChatRequest;
import io.github.fanqiepi.contextpilot.chat.ChatUsageResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(prefix = "contextpilot.research", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResearchChatService {
    private final ResearchStartService startService;
    private final ResearchExecutorService executorService;
    private final Executor taskExecutor;

    public ResearchChatService(
            ResearchStartService startService,
            ResearchExecutorService executorService,
            @Qualifier("researchTaskExecutor") Executor taskExecutor) {
        this.startService = startService;
        this.executorService = executorService;
        this.taskExecutor = taskExecutor;
    }

    public ChatAnswerResponse answer(ChatRequest request, String traceId) {
        ResearchStart start = startService.start(request, traceId);
        ResearchExecutionResult result = executorService.execute(start.run().id());
        ResearchUsageResponse usage = result.run().usage();
        return new ChatAnswerResponse(
                start.exchange().conversationId(), start.exchange().userMessageId(),
                start.exchange().assistantMessageId(), result.answer(),
                result.run().answerStatus() == ResearchAnswerStatus.REFUSED,
                result.citations(), result.model(),
                new ChatUsageResponse(
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), result.latencyMs()),
                traceId, CapabilityId.KNOWLEDGE_QA, "v4",
                CapabilityMatchReason.EXPLICIT_DOCUMENT_COMPARISON,
                null, null, result.run());
    }

    public ResearchStart start(ChatRequest request, String traceId) {
        return startService.start(request, traceId);
    }

    public CompletableFuture<ResearchExecutionResult> executeAsync(java.util.UUID runId) {
        return CompletableFuture.supplyAsync(() -> executorService.execute(runId), taskExecutor);
    }
}
