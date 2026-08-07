package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.model.ChatModelGateway;
import io.github.fanqiepi.contextpilot.model.ChatModelResult;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalResultResponse;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
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
    private RetrievalService retrievalService;
    @Mock
    private ChatPersistenceService persistenceService;
    @Mock
    private ChatPromptComposer promptComposer;
    @Mock
    private ChatModelGateway chatModelGateway;

    private ChatApplicationService service;

    @BeforeEach
    void setUp() {
        ChatProperties properties = new ChatProperties();
        properties.setMinSimilarity(0.5);
        properties.setRetrievalTopK(5);
        service = new ChatApplicationService(
                retrievalService,
                persistenceService,
                promptComposer,
                chatModelGateway,
                properties);
    }

    @Test
    void answersFromReliableEvidenceAndPersistsCitation() {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        RetrievalResultResponse evidence = new RetrievalResultResponse(
                UUID.randomUUID().toString(),
                documentId,
                "architecture.md",
                2,
                null,
                "ContextPilot stores vectors in PostgreSQL with pgvector.",
                0.91);
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, "Where are vectors stored?");
        UUID modelCallId = UUID.randomUUID();
        ChatModelResult modelResult = new ChatModelResult(
                "Vectors are stored in PostgreSQL with pgvector [1].",
                "deepseek-v4-flash",
                30,
                12,
                42);

        when(retrievalService.search(eq(knowledgeBaseId), any())).thenReturn(List.of(evidence));
        when(persistenceService.begin(null, knowledgeBaseId, request.question(), "trace-1"))
                .thenReturn(exchange);
        when(promptComposer.compose(request.question(), List.of(evidence)))
                .thenReturn(new ChatPrompt("system", "user", "v1"));
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
        RetrievalResultResponse weakResult = new RetrievalResultResponse(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "notes.txt",
                0,
                null,
                "Unrelated content",
                0.2);
        when(retrievalService.search(eq(knowledgeBaseId), any())).thenReturn(List.of(weakResult));
        when(persistenceService.begin(null, knowledgeBaseId, request.question(), "trace-2"))
                .thenReturn(exchange);

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
    void recordsModelFailureBeforeReturningSafeError() {
        UUID knowledgeBaseId = UUID.randomUUID();
        PendingChatExchange exchange = exchange();
        ChatRequest request = new ChatRequest(null, knowledgeBaseId, "Question");
        RetrievalResultResponse evidence = new RetrievalResultResponse(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "notes.txt",
                0,
                null,
                "Relevant content",
                0.9);
        UUID modelCallId = UUID.randomUUID();
        when(retrievalService.search(eq(knowledgeBaseId), any())).thenReturn(List.of(evidence));
        when(persistenceService.begin(null, knowledgeBaseId, request.question(), "trace-3"))
                .thenReturn(exchange);
        when(promptComposer.compose(request.question(), List.of(evidence)))
                .thenReturn(new ChatPrompt("system", "user", "v1"));
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
                eq(exchange.assistantMessageId()), eq(modelCallId), any(Long.class));
    }

    private PendingChatExchange exchange() {
        return new PendingChatExchange(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
}
