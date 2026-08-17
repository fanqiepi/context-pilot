package io.github.fanqiepi.contextpilot.research;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.chat.ChatPersistenceService;
import io.github.fanqiepi.contextpilot.chat.ChatRequest;
import io.github.fanqiepi.contextpilot.chat.PendingChatExchange;
import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(prefix = "contextpilot.research", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResearchStartService {
    private final ResearchRunMapper runMapper;
    private final ResearchStepMapper stepMapper;
    private final ResearchJsonCodec jsonCodec;
    private final ResearchDocumentEligibilityService eligibilityService;
    private final DeterministicResearchPlanner planner;
    private final ChatPersistenceService chatPersistenceService;
    private final ResearchQueryService queryService;

    public ResearchStartService(
            ResearchRunMapper runMapper,
            ResearchStepMapper stepMapper,
            ResearchJsonCodec jsonCodec,
            ResearchDocumentEligibilityService eligibilityService,
            DeterministicResearchPlanner planner,
            ChatPersistenceService chatPersistenceService,
            ResearchQueryService queryService) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.jsonCodec = jsonCodec;
        this.eligibilityService = eligibilityService;
        this.planner = planner;
        this.chatPersistenceService = chatPersistenceService;
        this.queryService = queryService;
    }

    @Transactional
    public ResearchStart start(ChatRequest request, String traceId) {
        ResearchRequest research = request.research();
        if (research == null || research.taskType() != ResearchTaskType.DOCUMENT_COMPARISON) {
            throw new BadRequestException(
                    "RESEARCH_REQUEST_INVALID", "Only DOCUMENT_COMPARISON research requests are supported");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new BadRequestException("RESEARCH_REQUEST_INVALID", "Research trace ID must not be blank");
        }
        List<UUID> documentIds = List.copyOf(research.documentIds());
        String fingerprint = fingerprint(request, documentIds);
        runMapper.lockClientRequest(research.clientRequestId());
        ResearchRunEntity existing = runMapper.selectByClientRequestId(research.clientRequestId());
        if (existing != null) {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new ConflictException(
                        "RESEARCH_REQUEST_ID_CONFLICT",
                        "Research client request ID was already used for a different request");
            }
            return new ResearchStart(
                    exchange(existing), queryService.get(existing.getId()), true);
        }

        eligibilityService.requireEligible(request.knowledgeBaseId(), documentIds);
        validateRetry(research.retryOfRunId(), request.knowledgeBaseId(), documentIds);
        ResearchPlan plan = planner.plan(request.question(), documentIds);
        PendingChatExchange exchange = chatPersistenceService.beginResearch(
                request.conversationId(), request.knowledgeBaseId(), request.question().strip(), traceId);
        OffsetDateTime now = now();
        ResearchRunEntity run = run(
                request, research, documentIds, fingerprint, plan, exchange, traceId, now);
        runMapper.insert(run);
        for (ResearchPlanStep plannedStep : plan.steps()) {
            stepMapper.insert(step(run.getId(), plannedStep, now));
        }
        return new ResearchStart(exchange, queryService.get(run.getId()), false);
    }

    private void validateRetry(UUID retryOfRunId, UUID knowledgeBaseId, List<UUID> documentIds) {
        if (retryOfRunId == null) {
            return;
        }
        ResearchRunEntity original = runMapper.selectById(retryOfRunId);
        if (original == null || !original.getExecutionStatus().terminal()
                || !original.getKnowledgeBaseId().equals(knowledgeBaseId)
                || !jsonCodec.readDocumentIds(original.getSelectedDocumentIdsJson()).equals(documentIds)) {
            throw new BadRequestException(
                    "RESEARCH_REQUEST_INVALID", "retryOfRunId does not reference a compatible terminal run");
        }
    }

    private ResearchRunEntity run(
            ChatRequest request,
            ResearchRequest research,
            List<UUID> documentIds,
            String fingerprint,
            ResearchPlan plan,
            PendingChatExchange exchange,
            String traceId,
            OffsetDateTime now) {
        ResearchBudget budget = ResearchBudget.V1;
        ResearchRunEntity run = new ResearchRunEntity();
        run.setId(UUID.randomUUID());
        run.setKnowledgeBaseId(request.knowledgeBaseId());
        run.setConversationId(exchange.conversationId());
        run.setUserMessageId(exchange.userMessageId());
        run.setAssistantMessageId(exchange.assistantMessageId());
        run.setClientRequestId(research.clientRequestId());
        run.setRequestFingerprint(fingerprint);
        run.setTaskType(research.taskType());
        run.setPlanVersion(plan.planVersion());
        run.setSelectedDocumentIdsJson(jsonCodec.writeDocumentIds(documentIds));
        run.setExecutionStatus(ResearchExecutionStatus.PLANNING);
        run.setMaxPlanSteps(budget.maximumPlanSteps());
        run.setMaxRetrievalCalls(budget.maximumRetrievalCalls());
        run.setMaxRawHits(budget.maximumRawHits());
        run.setMaxEvidenceChunks(budget.maximumEvidenceChunks());
        run.setMaxEvidenceCharacters(budget.maximumEvidenceCharacters());
        run.setHardTimeoutMillis(budget.hardTimeoutMillis());
        run.setTraceId(traceId);
        run.setRetryOfRunId(research.retryOfRunId());
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        return run;
    }

    private ResearchStepEntity step(UUID runId, ResearchPlanStep plan, OffsetDateTime now) {
        ResearchStepEntity step = new ResearchStepEntity();
        step.setId(plan.stepId());
        step.setRunId(runId);
        step.setOrdinal(plan.ordinal());
        step.setGoal(plan.goal());
        step.setQuery(plan.query());
        step.setDocumentIdsJson(jsonCodec.writeDocumentIds(plan.documentIds()));
        step.setStatus(ResearchStepStatus.PENDING);
        step.setCreatedAt(now);
        step.setUpdatedAt(now);
        return step;
    }

    private PendingChatExchange exchange(ResearchRunEntity run) {
        return new PendingChatExchange(run.getConversationId(), run.getUserMessageId(), run.getAssistantMessageId());
    }

    private String fingerprint(ChatRequest request, List<UUID> documentIds) {
        String canonicalDocuments = documentIds.stream().sorted(Comparator.naturalOrder())
                .map(UUID::toString).reduce((left, right) -> left + "," + right).orElse("");
        String canonical = request.knowledgeBaseId() + "|"
                + (request.conversationId() == null ? "" : request.conversationId()) + "|"
                + request.question().strip().replaceAll("\\s+", " ") + "|"
                + ResearchTaskType.DOCUMENT_COMPARISON + "|" + canonicalDocuments + "|"
                + (request.research().retryOfRunId() == null ? "" : request.research().retryOfRunId());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
