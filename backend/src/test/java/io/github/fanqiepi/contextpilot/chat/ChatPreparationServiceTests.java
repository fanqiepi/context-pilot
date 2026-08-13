package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatPreparationServiceTests {

    @Mock
    private RetrievalService retrievalService;
    @Mock
    private ChatPersistenceService persistenceService;
    @Mock
    private ChatPromptComposer promptComposer;

    private ChatPreparationService service;

    @BeforeEach
    void setUp() {
        ChatProperties properties = new ChatProperties();
        properties.setMinSimilarity(0.5);
        properties.setRetrievalTopK(5);
        SimpleChatReplyPolicy simpleReplyPolicy = new SimpleChatReplyPolicy();
        CreateKnowledgeBaseIntentPolicy createKnowledgeBaseIntentPolicy =
                new CreateKnowledgeBaseIntentPolicy();
        service = new ChatPreparationService(
                retrievalService,
                persistenceService,
                promptComposer,
                properties,
                simpleReplyPolicy,
                new CapabilityRouter(simpleReplyPolicy, createKnowledgeBaseIntentPolicy),
                createKnowledgeBaseIntentPolicy);
    }

    @Test
    void preparesPromptAndCitationsFromReliableEvidence() {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        ChatRequest request = new ChatRequest(conversationId, knowledgeBaseId, "  Question?  ");
        RetrievalResultResponse evidence = new RetrievalResultResponse(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "notes.txt",
                3,
                2,
                "Reliable evidence",
                0.9);
        ChatPrompt prompt = new ChatPrompt("system", "user", "v1");
        when(retrievalService.search(eq(knowledgeBaseId), any())).thenReturn(List.of(evidence));
        when(persistenceService.begin(
                eq(conversationId), eq(knowledgeBaseId), eq("Question?"), any(CapabilityRoute.class)))
                .thenReturn(exchange);
        when(promptComposer.compose("Question?", List.of(evidence))).thenReturn(prompt);

        PreparedChat prepared = service.prepare(request, "trace-1");

        assertThat(prepared.refused()).isFalse();
        assertThat(prepared.route().capabilityId()).isEqualTo(CapabilityId.KNOWLEDGE_QA);
        assertThat(prepared.prompt()).isEqualTo(prompt);
        assertThat(prepared.citations()).singleElement()
                .satisfies(citation -> {
                    assertThat(citation.rank()).isOne();
                    assertThat(citation.pageNumber()).isEqualTo(2);
                });
        verify(persistenceService).validateConversation(conversationId, knowledgeBaseId);
    }

    @Test
    void preparesRefusalWhenEvidenceIsBelowThreshold() {
        UUID knowledgeBaseId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, "Unknown");
        RetrievalResultResponse weakEvidence = new RetrievalResultResponse(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "notes.txt",
                0,
                null,
                "Weak evidence",
                0.2);
        when(retrievalService.search(eq(knowledgeBaseId), any())).thenReturn(List.of(weakEvidence));
        when(persistenceService.begin(eq(null), eq(knowledgeBaseId), eq("Unknown"), any(CapabilityRoute.class)))
                .thenReturn(exchange);

        PreparedChat prepared = service.prepare(request, "trace-2");

        assertThat(prepared.refused()).isTrue();
        assertThat(prepared.route().matchReason()).isEqualTo(CapabilityMatchReason.DEFAULT_KNOWLEDGE_QA);
        assertThat(prepared.citations()).isEmpty();
    }

    @Test
    void preparesDirectAnswerWithoutRetrievalForWhitelistedInteraction() {
        UUID knowledgeBaseId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, " 你是谁？ ");
        when(persistenceService.begin(
                eq(null), eq(knowledgeBaseId), eq("你是谁？"), any(CapabilityRoute.class)))
                .thenReturn(exchange);

        PreparedChat prepared = service.prepare(request, "trace-direct");

        assertThat(prepared.hasDirectAnswer()).isTrue();
        assertThat(prepared.route().capabilityId()).isEqualTo(CapabilityId.SIMPLE_CHAT);
        assertThat(prepared.directAnswer()).isEqualTo(SimpleChatReplyPolicy.IDENTITY_REPLY);
        assertThat(prepared.refused()).isFalse();
        assertThat(prepared.citations()).isEmpty();
        verify(persistenceService).validateConversation(null, knowledgeBaseId);
        verifyNoInteractions(retrievalService, promptComposer);
    }

    @Test
    void clarifiesMissingBusinessActionNameWithoutExecutingOrRetrieving() {
        UUID knowledgeBaseId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, "创建知识库");
        when(persistenceService.begin(
                eq(null), eq(knowledgeBaseId), eq("创建知识库"), any(CapabilityRoute.class)))
                .thenReturn(exchange);

        PreparedChat prepared = service.prepare(request, "trace-action-clarification");

        assertThat(prepared.route().capabilityId()).isEqualTo(CapabilityId.BUSINESS_ACTION);
        assertThat(prepared.directAnswer())
                .isEqualTo(ChatPreparationService.CREATE_KNOWLEDGE_BASE_NAME_CLARIFICATION);
        verifyNoInteractions(retrievalService, promptComposer);
    }

    @Test
    void preparesSafeDirectAnswerForBusinessActionWithoutExecutingOrRetrieving() {
        UUID knowledgeBaseId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, "创建知识库：Java 学习");
        when(persistenceService.begin(
                eq(null), eq(knowledgeBaseId), eq("创建知识库：Java 学习"), any(CapabilityRoute.class)))
                .thenReturn(exchange);

        PreparedChat prepared = service.prepare(request, "trace-action");

        assertThat(prepared.route().capabilityId()).isEqualTo(CapabilityId.BUSINESS_ACTION);
        assertThat(prepared.route().matchReason())
                .isEqualTo(CapabilityMatchReason.EXPLICIT_CREATE_KNOWLEDGE_BASE);
        assertThat(prepared.directAnswer())
                .isEqualTo(ChatPreparationService.BUSINESS_ACTION_NOT_AVAILABLE_ANSWER);
        verifyNoInteractions(retrievalService, promptComposer);
    }

    private PendingChatExchange exchange() {
        return new PendingChatExchange(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
