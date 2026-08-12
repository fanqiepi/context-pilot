package io.github.fanqiepi.contextpilot.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ApiExceptionHandler;
import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTests {

    @Mock
    private ConversationHistoryService conversationHistoryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ConversationController(conversationHistoryService))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void listsConversationsByKnowledgeBase() throws Exception {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-10T08:00:00Z");
        when(conversationHistoryService.list(knowledgeBaseId)).thenReturn(List.of(
                new ConversationSummaryResponse(
                        conversationId,
                        knowledgeBaseId,
                        "Which database is used?",
                        createdAt,
                        createdAt.plusMinutes(1))));

        mockMvc.perform(get("/api/conversations")
                        .queryParam("knowledgeBaseId", knowledgeBaseId.toString())
                        .header("X-Request-Id", "conversation-list-test"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "conversation-list-test"))
                .andExpect(jsonPath("$[0].id").value(conversationId.toString()))
                .andExpect(jsonPath("$[0].knowledgeBaseId").value(knowledgeBaseId.toString()))
                .andExpect(jsonPath("$[0].title").value("Which database is used?"));
    }

    @Test
    void returnsConversationMessagesWithCitations() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-10T08:00:00Z");
        when(conversationHistoryService.messages(conversationId)).thenReturn(List.of(
                new ConversationMessageResponse(
                        messageId,
                        conversationId,
                        ChatMessageRole.ASSISTANT,
                        "PostgreSQL is used [1].",
                        ChatMessageStatus.COMPLETED,
                        null,
                        "history-trace",
                        List.of(new ChatCitationResponse(
                                1,
                                "chunk-1",
                                documentId,
                                "architecture.md",
                                0,
                                null,
                                0.91,
                                "PostgreSQL with pgvector")),
                        true,
                        createdAt,
                        createdAt.plusSeconds(1))));

        mockMvc.perform(get("/api/conversations/{conversationId}/messages", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(messageId.toString()))
                .andExpect(jsonPath("$[0].role").value("ASSISTANT"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].traceId").value("history-trace"))
                .andExpect(jsonPath("$[0].helpful").value(true))
                .andExpect(jsonPath("$[0].citations[0].rank").value(1))
                .andExpect(jsonPath("$[0].citations[0].documentId").value(documentId.toString()));
    }

    @Test
    void returnsNotFoundForMissingConversation() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(conversationHistoryService.messages(conversationId)).thenThrow(
                new ResourceNotFoundException(
                        "CONVERSATION_NOT_FOUND",
                        "Conversation " + conversationId + " was not found"));

        mockMvc.perform(get("/api/conversations/{conversationId}/messages", conversationId)
                        .header("X-Request-Id", "missing-conversation-test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").value("missing-conversation-test"));
    }
}
