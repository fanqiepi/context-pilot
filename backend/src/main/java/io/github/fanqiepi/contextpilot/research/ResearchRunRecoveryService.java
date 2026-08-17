package io.github.fanqiepi.contextpilot.research;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.github.fanqiepi.contextpilot.chat.ChatPersistenceService;

@Service
@ConditionalOnProperty(prefix = "contextpilot.research", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResearchRunRecoveryService {
    private final ResearchRunMapper runMapper;
    private final ChatPersistenceService chatPersistenceService;

    public ResearchRunRecoveryService(
            ResearchRunMapper runMapper,
            ChatPersistenceService chatPersistenceService) {
        this.runMapper = runMapper;
        this.chatPersistenceService = chatPersistenceService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns() {
        var interrupted = runMapper.selectActive();
        runMapper.failInterrupted(OffsetDateTime.now(ZoneOffset.UTC));
        interrupted.forEach(run -> chatPersistenceService.failResearchMessage(
                run.getAssistantMessageId(), "Research run was interrupted by application restart"));
    }
}
