package io.github.fanqiepi.contextpilot.chat;

import java.util.ArrayList;
import java.util.List;

import io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalSearchRequest;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.springframework.stereotype.Service;

@Service
public class ChatPreparationService {

    private final RetrievalService retrievalService;
    private final ChatPersistenceService persistenceService;
    private final ChatPromptComposer promptComposer;
    private final ChatProperties properties;

    public ChatPreparationService(
            RetrievalService retrievalService,
            ChatPersistenceService persistenceService,
            ChatPromptComposer promptComposer,
            ChatProperties properties) {
        this.retrievalService = retrievalService;
        this.persistenceService = persistenceService;
        this.promptComposer = promptComposer;
        this.properties = properties;
    }

    PreparedChat prepare(ChatRequest request, String traceId) {
        String question = request.question().strip();
        persistenceService.validateConversation(request.conversationId(), request.knowledgeBaseId());
        List<RetrievalResultResponse> results = retrievalService.search(
                request.knowledgeBaseId(),
                new RetrievalSearchRequest(question, properties.getRetrievalTopK()));
        List<RetrievalResultResponse> evidence = selectEvidence(results);
        PendingChatExchange exchange = persistenceService.begin(
                request.conversationId(), request.knowledgeBaseId(), question, traceId);
        if (evidence.isEmpty()) {
            return new PreparedChat(exchange, null, List.of());
        }
        return new PreparedChat(
                exchange,
                promptComposer.compose(question, evidence),
                citations(evidence));
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
}
