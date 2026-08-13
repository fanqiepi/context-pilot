package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.model.ChatModelGateway;
import io.github.fanqiepi.contextpilot.model.ChatModelResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatApplicationServiceTests {

    @Mock
    private ChatPreparationService preparationService;
    @Mock
    private ChatPersistenceService persistenceService;
    @Mock
    private ChatModelGateway chatModelGateway;

    private ChatApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ChatApplicationService(
                preparationService,
                persistenceService,
                chatModelGateway);
    }

    @Test
    void answersFromReliableEvidenceAndPersistsCitation() {
        UUID knowledgeBaseId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        UUID documentId = UUID.randomUUID();
        ChatCitationResponse citation = new ChatCitationResponse(
                1,
                UUID.randomUUID().toString(),
                documentId,
                "architecture.md",
                2,
                null,
                0.91,
                "ContextPilot stores vectors in PostgreSQL with pgvector.");
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, "Where are vectors stored?");
        UUID modelCallId = UUID.randomUUID();
        ChatModelResult modelResult = new ChatModelResult(
                "Vectors are stored in PostgreSQL with pgvector [1].",
                "deepseek-v4-flash",
                30,
                12,
                42);

        when(preparationService.prepare(request, "trace-1")).thenReturn(new PreparedChat(
                knowledgeRoute("trace-1"),
                exchange,
                new ChatPrompt("system", "user", "v1"),
                List.of(citation)));
        when(chatModelGateway.provider()).thenReturn("DEEPSEEK");
        when(chatModelGateway.configuredModel()).thenReturn("deepseek-v4-flash");
        when(persistenceService.beginModelCall(
                exchange.assistantMessageId(), "DEEPSEEK", "deepseek-v4-flash", "v1", "trace-1"))
                .thenReturn(modelCallId);
        when(chatModelGateway.generate("system", "user")).thenReturn(modelResult);

        ChatAnswerResponse response = service.answer(request, "trace-1");

        assertThat(response.refused()).isFalse();
        assertThat(response.answer()).isEqualTo(modelResult.content());
        assertThat(response.citations()).hasSize(1);
        assertThat(response.citations().getFirst().documentId()).isEqualTo(documentId);
        assertThat(response.citations().getFirst().rank()).isOne();
        assertThat(response.usage().totalTokens()).isEqualTo(42);
        assertThat(response.capabilityId()).isEqualTo(CapabilityId.KNOWLEDGE_QA);
        assertThat(response.capabilityVersion()).isEqualTo("v1");
        verify(persistenceService).completeSuccess(
                eq(exchange.assistantMessageId()),
                eq(modelCallId),
                eq(modelResult.content()),
                eq(response.citations()),
                eq(modelResult),
                any(Long.class));
    }

    @Test
    void refusesWithoutReliableEvidenceAndDoesNotCallModel() {
        UUID knowledgeBaseId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, "Unknown question");
        when(preparationService.prepare(request, "trace-2"))
                .thenReturn(new PreparedChat(knowledgeRoute("trace-2"), exchange, null, List.of()));

        ChatAnswerResponse response = service.answer(request, "trace-2");

        assertThat(response.refused()).isTrue();
        assertThat(response.answer()).isEqualTo(ChatApplicationService.INSUFFICIENT_EVIDENCE_ANSWER);
        assertThat(response.citations()).isEmpty();
        assertThat(response.model()).isNull();
        verify(persistenceService).completeWithoutModel(
                exchange.assistantMessageId(), ChatApplicationService.INSUFFICIENT_EVIDENCE_ANSWER);
        verify(chatModelGateway, never()).generate(anyString(), anyString());
    }

    @Test
    void returnsDirectAnswerWithoutCallingModel() {
        UUID knowledgeBaseId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, "你是谁？");
        when(preparationService.prepare(request, "trace-direct"))
                .thenReturn(PreparedChat.direct(
                        simpleRoute("trace-direct"),
                        exchange,
                        SimpleChatReplyPolicy.IDENTITY_REPLY));

        ChatAnswerResponse response = service.answer(request, "trace-direct");

        assertThat(response.refused()).isFalse();
        assertThat(response.answer()).isEqualTo(SimpleChatReplyPolicy.IDENTITY_REPLY);
        assertThat(response.citations()).isEmpty();
        assertThat(response.model()).isNull();
        assertThat(response.usage()).isNull();
        assertThat(response.capabilityId()).isEqualTo(CapabilityId.SIMPLE_CHAT);
        assertThat(response.capabilityMatchReason())
                .isEqualTo(CapabilityMatchReason.SIMPLE_INTERACTION_WHITELIST);
        verify(persistenceService).completeWithoutModel(
                exchange.assistantMessageId(), SimpleChatReplyPolicy.IDENTITY_REPLY);
        verify(chatModelGateway, never()).generate(anyString(), anyString());
    }

    @Test
    void recordsModelFailureBeforeReturningSafeError() {
        UUID knowledgeBaseId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, "Question");
        UUID modelCallId = UUID.randomUUID();
        when(preparationService.prepare(request, "trace-3")).thenReturn(new PreparedChat(
                knowledgeRoute("trace-3"),
                exchange,
                new ChatPrompt("system", "user", "v1"),
                List.of(new ChatCitationResponse(
                        1,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID(),
                        "notes.txt",
                        0,
                        null,
                        0.9,
                        "Relevant content"))));
        when(chatModelGateway.provider()).thenReturn("DEEPSEEK");
        when(chatModelGateway.configuredModel()).thenReturn("deepseek-v4-flash");
        when(persistenceService.beginModelCall(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(modelCallId);
        when(chatModelGateway.generate("system", "user")).thenThrow(new InternalServiceException(
                "CHAT_MODEL_CALL_FAILED", "Chat model call failed", new RuntimeException("provider failure")));

        assertThatThrownBy(() -> service.answer(request, "trace-3"))
                .isInstanceOf(InternalServiceException.class)
                .hasMessage("Chat model call failed");
        verify(persistenceService).completeFailure(
                eq(exchange.assistantMessageId()),
                eq(modelCallId),
                any(Long.class),
                eq("Chat model call failed"));
    }

    private PendingChatExchange exchange() {
        return new PendingChatExchange(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private CapabilityRoute knowledgeRoute(String traceId) {
        return CapabilityRoute.matched(
                CapabilityId.KNOWLEDGE_QA,
                CapabilityMatchReason.DEFAULT_KNOWLEDGE_QA,
                traceId);
    }

    private CapabilityRoute simpleRoute(String traceId) {
        return CapabilityRoute.matched(
                CapabilityId.SIMPLE_CHAT,
                CapabilityMatchReason.SIMPLE_INTERACTION_WHITELIST,
                traceId);
    }
}
