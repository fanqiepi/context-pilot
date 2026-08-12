package io.github.fanqiepi.contextpilot.feedback;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/messages")
@Tag(name = "回答反馈", description = "标记或取消已完成助手回答的有用反馈")
public class AnswerFeedbackController {

    private final AnswerFeedbackService answerFeedbackService;

    public AnswerFeedbackController(AnswerFeedbackService answerFeedbackService) {
        this.answerFeedbackService = answerFeedbackService;
    }

    @PutMapping("/{messageId}/feedback")
    @Operation(summary = "标记助手回答有用")
    public AnswerFeedbackResponse markHelpful(@PathVariable UUID messageId) {
        return answerFeedbackService.markHelpful(messageId);
    }

    @DeleteMapping("/{messageId}/feedback")
    @Operation(summary = "取消助手回答的有用标记")
    public ResponseEntity<Void> removeHelpful(@PathVariable UUID messageId) {
        answerFeedbackService.removeHelpful(messageId);
        return ResponseEntity.noContent().build();
    }
}
