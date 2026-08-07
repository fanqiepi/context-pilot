package io.github.fanqiepi.contextpilot.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.github.fanqiepi.contextpilot.model.ChatModelGateway;
import io.github.fanqiepi.contextpilot.model.ChatModelResult;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalSearchRequest;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.springframework.stereotype.Service;

@Service
public class ChatApplicationService {

    static final String INSUFFICIENT_EVIDENCE_ANSWER =
            "未在所选知识库中找到足够可靠的依据，无法回答该问题。";

    private final RetrievalService retrievalService;
    private final ChatPersistenceService persistenceService;
    private final ChatPromptComposer promptComposer;
    private final ChatModelGateway chatModelGateway;
    private final ChatProperties properties;

    public ChatApplicationService(
            RetrievalService retrievalService,
            ChatPersistenceService persistenceService,
            ChatPromptComposer promptComposer,
            ChatModelGateway chatModelGateway,
            ChatProperties properties) {
        this.retrievalService = retrievalService;
        this.persistenceService = persistenceService;
        this.promptComposer = promptComposer;
        this.chatModelGateway = chatModelGateway;
        this.properties = properties;
    }

    public ChatAnswerResponse answer(ChatRequest request, String traceId) {
        String question = request.question().strip();
        persistenceService.validateConversation(request.conversationId(), request.knowledgeBaseId());
        List<RetrievalResultResponse> results = retrievalService.search(
                request.knowledgeBaseId(),
                new RetrievalSearchRequest(question, properties.getRetrievalTopK()));
        List<RetrievalResultResponse> evidence = selectEvidence(results);

        PendingChatExchange exchange = persistenceService.begin(
                request.conversationId(), request.knowledgeBaseId(), question, traceId);
        if (evidence.isEmpty()) {
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
                    traceId);
        }

        ChatPrompt prompt = promptComposer.compose(question, evidence);
        UUID modelCallId = persistenceService.beginModelCall(
                exchange.assistantMessageId(),
                chatModelGateway.provider(),
                chatModelGateway.configuredModel(),
                prompt.version(),
                traceId);
        long startNanos = System.nanoTime();
        try {
            ChatModelResult result = chatModelGateway.generate(prompt.systemText(), prompt.userText());
            long latencyMs = elapsedMillis(startNanos);
            List<ChatCitationResponse> citations = citations(evidence);
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
                    traceId);
        } catch (RuntimeException exception) {
            persistenceService.completeFailure(
                    exchange.assistantMessageId(), modelCallId, elapsedMillis(startNanos));
            throw exception;
        }
    }

    private List<RetrievalResultResponse> selectEvidence(List<RetrievalResultResponse> results) {
        List<RetrievalResultResponse> selected = new ArrayList<>();
        int totalCharacters = 0;
        for (RetrievalResultResponse result : results) {
            if (result.content() == null || result.content().isBlank()
                    || result.score() == null
                    || result.score() < properties.getMinSimilarity()) {
                continue;
            }
            if (!selected.isEmpty()
                    && totalCharacters + result.content().length() > properties.getMaxEvidenceCharacters()) {
                break;
            }
            selected.add(result);
            totalCharacters += result.content().length();
        }
        return List.copyOf(selected);
    }

    private List<ChatCitationResponse> citations(List<RetrievalResultResponse> evidence) {
        List<ChatCitationResponse> citations = new ArrayList<>();
        for (int index = 0; index < evidence.size(); index++) {
            citations.add(ChatCitationResponse.from(index + 1, evidence.get(index)));
        }
        return List.copyOf(citations);
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
