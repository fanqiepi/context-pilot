package io.github.fanqiepi.contextpilot.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.action.ActionRequestService;
import io.github.fanqiepi.contextpilot.action.CreateKnowledgeBaseActionParameters;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthAssessment;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthReportResponse;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthReportService;
import io.github.fanqiepi.contextpilot.health.KnowledgeBaseHealthService;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalSearchRequest;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatPreparationService {

    static final String BUSINESS_ACTION_PROPOSAL_ANSWER =
            "已生成创建知识库提案。请核对名称、描述和影响，并通过操作卡片明确确认或取消。";
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
    private final ActionRequestService actionRequestService;
    private final KnowledgeBaseHealthService healthService;
    private final KnowledgeBaseHealthReportService healthReportService;

    public ChatPreparationService(
            RetrievalService retrievalService,
            ChatPersistenceService persistenceService,
            ChatPromptComposer promptComposer,
            ChatProperties properties,
            SimpleChatReplyPolicy simpleReplyPolicy,
            CapabilityRouter capabilityRouter,
            CreateKnowledgeBaseIntentPolicy createKnowledgeBaseIntentPolicy,
            ActionRequestService actionRequestService,
            KnowledgeBaseHealthService healthService,
            KnowledgeBaseHealthReportService healthReportService) {
        this.retrievalService = retrievalService;
        this.persistenceService = persistenceService;
        this.promptComposer = promptComposer;
        this.properties = properties;
        this.simpleReplyPolicy = simpleReplyPolicy;
        this.capabilityRouter = capabilityRouter;
        this.createKnowledgeBaseIntentPolicy = createKnowledgeBaseIntentPolicy;
        this.actionRequestService = actionRequestService;
        this.healthService = healthService;
        this.healthReportService = healthReportService;
    }

    @Transactional
    public PreparedChat prepare(ChatRequest request, String traceId) {
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
        if (route.matchReason() == CapabilityMatchReason.EXPLICIT_KNOWLEDGE_BASE_HEALTH) {
            return prepareKnowledgeBaseHealth(request, question, route);
        }
        List<RetrievalResultResponse> results = retrievalService.search(
                request.knowledgeBaseId(),
                new RetrievalSearchRequest(question, properties.getRetrievalTopK()),
                route.traceId());
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

    private PreparedChat prepareKnowledgeBaseHealth(
            ChatRequest request,
            String question,
            CapabilityRoute route) {
        PendingChatExchange exchange = persistenceService.begin(
                request.conversationId(), request.knowledgeBaseId(), question, route);
        KnowledgeBaseHealthAssessment assessment = healthService.inspect(request.knowledgeBaseId());
        KnowledgeBaseHealthReportResponse report = healthReportService.create(
                exchange.conversationId(),
                exchange.userMessageId(),
                exchange.assistantMessageId(),
                assessment);
        return PreparedChat.health(route, exchange, report);
    }

    private PreparedChat prepareBusinessAction(
            ChatRequest request,
            String question,
            CapabilityRoute route) {
        CreateKnowledgeBaseIntentPolicy.CreateKnowledgeBaseIntent intent =
                createKnowledgeBaseIntentPolicy.parse(question)
                        .orElseThrow(() -> new IllegalStateException(
                                "BUSINESS_ACTION route did not contain a supported action request"));
        if (intent.name() == null) {
            PendingChatExchange exchange = persistenceService.begin(
                    request.conversationId(), request.knowledgeBaseId(), question, route);
            return PreparedChat.direct(route, exchange, CREATE_KNOWLEDGE_BASE_NAME_CLARIFICATION);
        }
        CreateKnowledgeBaseActionParameters parameters = new CreateKnowledgeBaseActionParameters(
                intent.name(), intent.description());
        PendingChatExchange exchange = persistenceService.begin(
                request.conversationId(), request.knowledgeBaseId(), question, route);
        ActionRequestResponse actionRequest = actionRequestService.proposeCreateKnowledgeBase(
                exchange.conversationId(),
                exchange.userMessageId(),
                exchange.assistantMessageId(),
                route.capabilityId(),
                route.capabilityVersion(),
                route.traceId(),
                parameters);
        persistenceService.completeWithoutModel(
                exchange.assistantMessageId(), BUSINESS_ACTION_PROPOSAL_ANSWER);
        return PreparedChat.action(
                route, exchange, BUSINESS_ACTION_PROPOSAL_ANSWER, actionRequest);
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
