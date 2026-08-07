package io.github.fanqiepi.contextpilot.chat;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatApplicationService chatApplicationService;
    private final ChatStreamingService chatStreamingService;

    public ChatController(
            ChatApplicationService chatApplicationService,
            ChatStreamingService chatStreamingService) {
        this.chatApplicationService = chatApplicationService;
        this.chatStreamingService = chatStreamingService;
    }

    @PostMapping
    public ChatAnswerResponse answer(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest) {
        Object requestId = httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String traceId = requestId == null ? UUID.randomUUID().toString() : requestId.toString();
        return chatApplicationService.answer(request, traceId);
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamPayload>> stream(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest) {
        Object requestId = httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String traceId = requestId == null ? UUID.randomUUID().toString() : requestId.toString();
        return chatStreamingService.stream(request, traceId);
    }
}
