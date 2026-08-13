package io.github.fanqiepi.contextpilot.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalSearchRequest;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.springframework.stereotype.Service;

@Service
public class ChatPreparationService {

    static final String BUSINESS_ACTION_NOT_AVAILABLE_ANSWER =
            "已识别到创建知识库请求。该操作需要先生成提案并由你明确确认；"
                    + "当前尚未创建知识库。";
    static final String CREATE_KNOWLEDGE_BASE_NAME_CLARIFICATION =
            "请提供要创建的知识库名称，例如：创建一个名为 Java 学习的知识库。"
                    + "在你明确确认前不会创建知识库。";

    private final RetrievalService retrievalService;
    private final ChatPersistenceService persistenceService;
    private final ChatPromptComposer promptComposer;
    private final ChatProperties properties;
    private final SimpleChatReplyPolicy simpleReplyPolicy;
    private final CapabilityRouter capabilityRouter;
    private final CreateKnowledgeBaseIntentPolicy createKnowledgeBaseIntentPolicy;

    public ChatPreparationService(
            RetrievalService retrievalService,
            ChatPersistenceService persistenceService,
            ChatPromptComposer promptComposer,
            ChatProperties properties,
            SimpleChatReplyPolicy simpleReplyPolicy,
            CapabilityRouter capabilityRouter,
            CreateKnowledgeBaseIntentPolicy createKnowledgeBaseIntentPolicy) {
        this.retrievalService = retrievalService;
        this.persistenceService = persistenceService;
        this.promptComposer = promptComposer;
        this.properties = properties;
        this.simpleReplyPolicy = simpleReplyPolicy;
        this.capabilityRouter = capabilityRouter;
        this.createKnowledgeBaseIntentPolicy = createKnowledgeBaseIntentPolicy;
    }

    PreparedChat prepare(ChatRequest request, String traceId) {
        String question = request.question().strip();
        persistenceService.validateConversation(request.conversationId(), request.knowledgeBaseId());
        CapabilityRoute route = capabilityRouter.route(question, traceId);
        return switch (route.capabilityId()) {
            case SIMPLE_CHAT -> prepareSimpleChat(request, question, route);
            case KNOWLEDGE_QA -> prepareKnowledgeQa(request, question, route);
            case BUSINESS_ACTION -> prepareBusinessAction(request, question, route);
        };
    }

    private PreparedChat prepareSimpleChat(
            ChatRequest request,
            String question,
            CapabilityRoute route) {
        Optional<String> directAnswer = simpleReplyPolicy.replyTo(question);
        if (directAnswer.isEmpty()) {
            throw new IllegalStateException("SIMPLE_CHAT route did not have a deterministic reply");
        }
        PendingChatExchange exchange = persistenceService.begin(
                request.conversationId(), request.knowledgeBaseId(), question, route);
        return PreparedChat.direct(route, exchange, directAnswer.get());
    }

    private PreparedChat prepareKnowledgeQa(
            ChatRequest request,
            String question,
            CapabilityRoute route) {
        List<RetrievalResultResponse> results = retrievalService.search(
                request.knowledgeBaseId(),
                new RetrievalSearchRequest(question, properties.getRetrievalTopK()));
        List<RetrievalResultResponse> evidence = selectEvidence(results);
        PendingChatExchange exchange = persistenceService.begin(
                request.conversationId(), request.knowledgeBaseId(), question, route);
        if (evidence.isEmpty()) {
            return new PreparedChat(route, exchange, null, List.of());
        }
        return new PreparedChat(
                route,
                exchange,
                promptComposer.compose(question, evidence),
                citations(evidence));
    }

    private PreparedChat prepareBusinessAction(
            ChatRequest request,
            String question,
            CapabilityRoute route) {
        PendingChatExchange exchange = persistenceService.begin(
                request.conversationId(), request.knowledgeBaseId(), question, route);
        String answer = createKnowledgeBaseIntentPolicy.hasExplicitName(question)
                ? BUSINESS_ACTION_NOT_AVAILABLE_ANSWER
                : CREATE_KNOWLEDGE_BASE_NAME_CLARIFICATION;
        return PreparedChat.direct(route, exchange, answer);
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
