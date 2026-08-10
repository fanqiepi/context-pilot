package io.github.fanqiepi.contextpilot.chat;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
@Tag(name = "会话历史", description = "查询知识库会话及其历史消息")
public class ConversationController {

    private final ConversationHistoryService conversationHistoryService;

    public ConversationController(ConversationHistoryService conversationHistoryService) {
        this.conversationHistoryService = conversationHistoryService;
    }

    @GetMapping
    @Operation(summary = "查询知识库下的会话列表")
    public List<ConversationSummaryResponse> list(@RequestParam UUID knowledgeBaseId) {
        return conversationHistoryService.list(knowledgeBaseId);
    }

    @GetMapping("/{conversationId}/messages")
    @Operation(summary = "查询会话历史消息")
    public List<ConversationMessageResponse> messages(@PathVariable UUID conversationId) {
        return conversationHistoryService.messages(conversationId);
    }
}
