package io.github.fanqiepi.contextpilot.research;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.ChatPersistenceService;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(prefix = "contextpilot.research", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResearchRunCommandService {
    private final ResearchRunMapper runMapper;
    private final ResearchStepMapper stepMapper;
    private final ResearchQueryService queryService;
    private final ChatPersistenceService chatPersistenceService;

    public ResearchRunCommandService(
            ResearchRunMapper runMapper,
            ResearchStepMapper stepMapper,
            ResearchQueryService queryService,
            ChatPersistenceService chatPersistenceService) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.queryService = queryService;
        this.chatPersistenceService = chatPersistenceService;
    }

    @Transactional
    public ResearchRunResponse cancel(UUID runId) {
        ResearchRunResponse current = queryService.get(runId);
        if (current.executionStatus() == ResearchExecutionStatus.CANCELLED) {
            return current;
        }
        if (current.executionStatus().terminal()) {
            throw new ConflictException(
                    "RESEARCH_RUN_NOT_CANCELLABLE", "Terminal research run cannot be cancelled");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (runMapper.cancel(runId, now) == 0) {
            ResearchRunResponse latest = queryService.get(runId);
            if (latest.executionStatus() == ResearchExecutionStatus.CANCELLED) {
                return latest;
            }
            throw new ConflictException(
                    "RESEARCH_RUN_NOT_CANCELLABLE", "Research run is no longer cancellable");
        }
        stepMapper.cancelRemaining(runId, now);
        chatPersistenceService.cancelResearchMessage(current.assistantMessageId());
        return queryService.get(runId);
    }
}
