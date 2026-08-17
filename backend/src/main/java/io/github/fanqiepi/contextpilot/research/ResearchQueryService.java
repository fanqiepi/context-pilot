package io.github.fanqiepi.contextpilot.research;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(prefix = "contextpilot.research", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResearchQueryService {
    private final ResearchRunMapper runMapper;
    private final ResearchStepMapper stepMapper;
    private final ResearchJsonCodec jsonCodec;

    public ResearchQueryService(
            ResearchRunMapper runMapper,
            ResearchStepMapper stepMapper,
            ResearchJsonCodec jsonCodec) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.jsonCodec = jsonCodec;
    }

    @Transactional(readOnly = true)
    public ResearchRunResponse get(UUID runId) {
        ResearchRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new ResourceNotFoundException("RESEARCH_RUN_NOT_FOUND", "Research run was not found");
        }
        return response(run, stepMapper.selectByRunId(runId));
    }

    @Transactional(readOnly = true)
    public Map<UUID, ResearchRunSummaryResponse> summariesByAssistantMessageIds(List<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ResearchRunSummaryResponse> result = new LinkedHashMap<>();
        for (ResearchRunEntity run : runMapper.selectByAssistantMessageIds(messageIds)) {
            List<ResearchStepEntity> steps = stepMapper.selectByRunId(run.getId());
            long completed = steps.stream().filter(step -> switch (step.getStatus()) {
                case SUCCEEDED, PARTIAL, FAILED, CANCELLED -> true;
                default -> false;
            }).count();
            result.put(run.getAssistantMessageId(), new ResearchRunSummaryResponse(
                    run.getId(), run.getTaskType(), run.getExecutionStatus(), run.getAnswerStatus(),
                    steps.size(), Math.toIntExact(completed), run.getErrorCode(), run.getErrorSummary()));
        }
        return Map.copyOf(result);
    }

    ResearchRunResponse response(ResearchRunEntity run, List<ResearchStepEntity> steps) {
        List<ResearchStepResponse> stepResponses = steps.stream().map(step -> new ResearchStepResponse(
                step.getId(), step.getOrdinal(), step.getGoal(), step.getQuery(),
                jsonCodec.readDocumentIds(step.getDocumentIdsJson()), step.getStatus(),
                step.getHitCount(), step.getRetainedEvidenceCount(), step.getLatencyMs(),
                step.getErrorSummary())).toList();
        return new ResearchRunResponse(
                run.getId(), run.getKnowledgeBaseId(), run.getConversationId(), run.getUserMessageId(),
                run.getAssistantMessageId(), run.getClientRequestId(), run.getTaskType(), run.getPlanVersion(),
                jsonCodec.readDocumentIds(run.getSelectedDocumentIdsJson()), run.getExecutionStatus(),
                run.getAnswerStatus(), stepResponses,
                new ResearchBudget(
                        run.getMaxPlanSteps(), run.getMaxRetrievalCalls(), ResearchBudget.V1.perDocumentTopK(),
                        run.getMaxRawHits(), run.getMaxEvidenceChunks(), run.getMaxEvidenceCharacters(),
                        ResearchBudget.V1.maximumExcerptCharacters(),
                        ResearchBudget.V1.maximumStepEvidenceCharacters(), run.getHardTimeoutMillis()),
                new ResearchUsageResponse(
                        run.getActualRetrievalCalls(), run.getActualRawHits(), run.getActualEvidenceChunks(),
                        run.getActualEvidenceCharacters(), run.getPromptTokens(), run.getCompletionTokens(),
                        run.getTotalTokens()),
                run.getErrorCode(), run.getErrorSummary(), run.getTraceId(), run.getRetryOfRunId(),
                run.getStartedAt(), run.getCancelledAt(), run.getCompletedAt(),
                run.getCreatedAt(), run.getUpdatedAt());
    }
}
