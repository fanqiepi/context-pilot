package io.github.fanqiepi.contextpilot.feedback;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ApiExceptionHandler;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AnswerFeedbackControllerTests {

    @Mock
    private AnswerFeedbackService answerFeedbackService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AnswerFeedbackController(answerFeedbackService))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void marksMessageHelpful() throws Exception {
        UUID feedbackId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-08-12T08:00:00Z");
        when(answerFeedbackService.markHelpful(messageId)).thenReturn(new AnswerFeedbackResponse(
                feedbackId,
                messageId,
                knowledgeBaseId,
                "trace-feedback",
                true,
                createdAt,
                createdAt));

        mockMvc.perform(put("/api/messages/{messageId}/feedback", messageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(feedbackId.toString()))
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.knowledgeBaseId").value(knowledgeBaseId.toString()))
                .andExpect(jsonPath("$.traceId").value("trace-feedback"))
                .andExpect(jsonPath("$.helpful").value(true));
    }

    @Test
    void removesHelpfulMark() throws Exception {
        UUID messageId = UUID.randomUUID();

        mockMvc.perform(delete("/api/messages/{messageId}/feedback", messageId))
                .andExpect(status().isNoContent());

        verify(answerFeedbackService).removeHelpful(messageId);
    }

    @Test
    void rejectsFeedbackForIneligibleMessage() throws Exception {
        UUID messageId = UUID.randomUUID();
        when(answerFeedbackService.markHelpful(messageId)).thenThrow(new ConflictException(
                "MESSAGE_FEEDBACK_NOT_ALLOWED",
                "Only completed assistant messages can be marked as helpful"));

        mockMvc.perform(put("/api/messages/{messageId}/feedback", messageId)
                        .header("X-Request-Id", "feedback-conflict-test"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MESSAGE_FEEDBACK_NOT_ALLOWED"))
                .andExpect(jsonPath("$.requestId").value("feedback-conflict-test"));
    }
}
