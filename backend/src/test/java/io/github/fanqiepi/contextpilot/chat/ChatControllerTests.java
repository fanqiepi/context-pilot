package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ApiExceptionHandler;
import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatControllerTests {

    @Mock
    private ChatApplicationService chatApplicationService;
    @Mock
    private ChatStreamingService chatStreamingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(
                        chatApplicationService, chatStreamingService))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void answersWithRequestIdAsTraceId() throws Exception {
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        String traceId = "chat-test-123";
        when(chatApplicationService.answer(any(), eq(traceId))).thenReturn(new ChatAnswerResponse(
                conversationId,
                userMessageId,
                assistantMessageId,
                "PostgreSQL is used [1].",
                false,
                List.of(),
                "deepseek-v4-flash",
                new ChatUsageResponse(10, 5, 15, 20),
                traceId,
                CapabilityId.KNOWLEDGE_QA,
                "v1",
                CapabilityMatchReason.DEFAULT_KNOWLEDGE_QA));

        mockMvc.perform(post("/api/chat")
                        .header("X-Request-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"knowledgeBaseId":"%s","question":"Which database is used?"}
                                """.formatted(knowledgeBaseId)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", traceId))
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.assistantMessageId").value(assistantMessageId.toString()))
                .andExpect(jsonPath("$.answer").value("PostgreSQL is used [1]."))
                .andExpect(jsonPath("$.refused").value(false))
                .andExpect(jsonPath("$.model").value("deepseek-v4-flash"))
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.capabilityId").value("KNOWLEDGE_QA"))
                .andExpect(jsonPath("$.capabilityVersion").value("v1"))
                .andExpect(jsonPath("$.capabilityMatchReason").value("DEFAULT_KNOWLEDGE_QA"));
    }

    @Test
    void validatesChatRequest() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void streamsServerSentEventsWithRequestId() throws Exception {
        UUID knowledgeBaseId = UUID.randomUUID();
        String traceId = "stream-test-123";
        when(chatStreamingService.stream(any(), eq(traceId))).thenReturn(Flux.just(
                ServerSentEvent.<ChatStreamPayload>builder(
                                new ChatStreamPayload.Done("COMPLETED", traceId))
                        .event("done")
                        .build()));

        var pending = mockMvc.perform(post("/api/chat/stream")
                        .header("X-Request-Id", traceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"knowledgeBaseId":"%s","question":"Question"}
                                """.formatted(knowledgeBaseId)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", traceId))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(traceId)));
    }
}
