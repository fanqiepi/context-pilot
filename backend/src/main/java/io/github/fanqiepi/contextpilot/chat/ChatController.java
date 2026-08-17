package io.github.fanqiepi.contextpilot.chat;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import io.github.fanqiepi.contextpilot.research.ResearchChatService;
import io.github.fanqiepi.contextpilot.research.ResearchStreamingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatApplicationService chatApplicationService;
    private final ChatStreamingService chatStreamingService;
    private final ResearchChatService researchChatService;
    private final ResearchStreamingService researchStreamingService;

    public ChatController(
            ChatApplicationService chatApplicationService,
            ChatStreamingService chatStreamingService) {
        this.chatApplicationService = chatApplicationService;
        this.chatStreamingService = chatStreamingService;
        this.researchChatService = null;
        this.researchStreamingService = null;
    }

    @Autowired
    public ChatController(
            ChatApplicationService chatApplicationService,
            ChatStreamingService chatStreamingService,
            ObjectProvider<ResearchChatService> researchChatService,
            ObjectProvider<ResearchStreamingService> researchStreamingService) {
        this.chatApplicationService = chatApplicationService;
        this.chatStreamingService = chatStreamingService;
        this.researchChatService = researchChatService.getIfAvailable();
        this.researchStreamingService = researchStreamingService.getIfAvailable();
    }

    @PostMapping
    public ChatAnswerResponse answer(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest) {
        Object requestId = httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String traceId = requestId == null ? UUID.randomUUID().toString() : requestId.toString();
        if (request.research() != null) {
            return requireResearch(researchChatService).answer(request, traceId);
        }
        return chatApplicationService.answer(request, traceId);
    }

    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamPayload>> stream(
            @Valid @RequestBody ChatRequest request,
            HttpServletRequest httpRequest) {
        Object requestId = httpRequest.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String traceId = requestId == null ? UUID.randomUUID().toString() : requestId.toString();
        if (request.research() != null) {
            return requireResearch(researchStreamingService).stream(request, traceId);
        }
        return chatStreamingService.stream(request, traceId);
    }

    private <T> T requireResearch(T service) {
        if (service == null) {
            throw new InternalServiceException(
                    "RESEARCH_DISABLED",
                    "Document comparison research is disabled",
                    new IllegalStateException("contextpilot.research.enabled=false"));
        }
        return service;
    }
}
