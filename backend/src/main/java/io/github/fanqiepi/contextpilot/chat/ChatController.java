package io.github.fanqiepi.contextpilot.chat;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatApplicationService chatApplicationService;

    public ChatController(ChatApplicationService chatApplicationService) {
        this.chatApplicationService = chatApplicationService;
    }

    @PostMapping
    public ChatAnswerResponse answer(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest) {
        Object requestId = httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String traceId = requestId == null ? UUID.randomUUID().toString() : requestId.toString();
        return chatApplicationService.answer(request, traceId);
    }
}
