package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageRole;
import io.github.fanqiepi.contextpilot.chat.ChatMessageStatus;
import io.github.fanqiepi.contextpilot.chat.ConversationEntity;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProfile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseHealthReportService {

    private static final Comparator<KnowledgeBaseHealthIssueEntity> ISSUE_ORDER = Comparator
            .comparingInt((KnowledgeBaseHealthIssueEntity issue) -> severityOrder(issue.getSeverity()))
            .thenComparingInt(issue -> issueTypeOrder(issue.getIssueType()))
            .thenComparing(KnowledgeBaseHealthIssueEntity::getSourceDocumentUpdatedAt)
            .thenComparing(KnowledgeBaseHealthIssueEntity::getDocumentId)
            .thenComparing(KnowledgeBaseHealthIssueEntity::getId);

    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final KnowledgeBaseHealthReportMapper reportMapper;
    private final KnowledgeBaseHealthIssueMapper issueMapper;

    public KnowledgeBaseHealthReportService(
            ConversationMapper conversationMapper,
            ChatMessageMapper chatMessageMapper,
            KnowledgeBaseHealthReportMapper reportMapper,
            KnowledgeBaseHealthIssueMapper issueMapper) {
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.reportMapper = reportMapper;
        this.issueMapper = issueMapper;
    }

    @Transactional
    public KnowledgeBaseHealthReportResponse create(
            UUID conversationId,
            UUID userMessageId,
            UUID assistantMessageId,
            KnowledgeBaseHealthAssessment assessment) {
        Objects.requireNonNull(assessment, "Health assessment must not be null");
        if (findEntityByAssistantMessageId(assistantMessageId) != null) {
            throw reportAlreadyExists(assistantMessageId);
        }
        VerifiedMessageContext context = verifyMessageContext(
                conversationId, userMessageId, assistantMessageId, assessment.knowledgeBaseId());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseHealthReportEntity report = reportEntity(
                assessment, context, conversationId, userMessageId, assistantMessageId, now);
        try {
            reportMapper.insert(report);
            for (KnowledgeBaseHealthIssue issue : assessment.issues()) {
                issueMapper.insert(issueEntity(report.getId(), issue, now));
            }
        } catch (DuplicateKeyException exception) {
            throw new ConflictException(
                    "KNOWLEDGE_BASE_HEALTH_REPORT_ALREADY_EXISTS",
                    "A health report already exists for the assistant message",
                    exception);
        }

        ChatMessageEntity assistantMessage = context.assistantMessage();
        assistantMessage.setContent(assessment.summary());
        assistantMessage.setStatus(ChatMessageStatus.COMPLETED);
        assistantMessage.setErrorSummary(null);
        assistantMessage.setUpdatedAt(now);
        if (chatMessageMapper.updateById(assistantMessage) != 1) {
            throw new IllegalStateException("Health report assistant message could not be completed");
        }

        return KnowledgeBaseHealthReportResponse.from(
                report,
                issuesForReport(report.getId()));
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseHealthReportResponse get(UUID reportId) {
        KnowledgeBaseHealthReportEntity report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new ResourceNotFoundException(
                    "HEALTH_REPORT_NOT_FOUND",
                    "Knowledge base health report " + reportId + " was not found");
        }
        return KnowledgeBaseHealthReportResponse.from(report, issuesForReport(reportId));
    }

    @Transactional(readOnly = true)
    public Map<UUID, KnowledgeBaseHealthReportResponse> findByAssistantMessageIds(
            Collection<UUID> assistantMessageIds) {
        if (assistantMessageIds.isEmpty()) {
            return Map.of();
        }
        List<KnowledgeBaseHealthReportEntity> reports = reportMapper.selectList(
                Wrappers.<KnowledgeBaseHealthReportEntity>lambdaQuery()
                        .in(KnowledgeBaseHealthReportEntity::getAssistantMessageId, assistantMessageIds));
        if (reports.isEmpty()) {
            return Map.of();
        }

        List<UUID> reportIds = reports.stream().map(KnowledgeBaseHealthReportEntity::getId).toList();
        Map<UUID, List<KnowledgeBaseHealthIssueEntity>> issuesByReport = issueMapper.selectList(
                        Wrappers.<KnowledgeBaseHealthIssueEntity>lambdaQuery()
                                .in(KnowledgeBaseHealthIssueEntity::getReportId, reportIds))
                .stream()
                .sorted(ISSUE_ORDER)
                .collect(Collectors.groupingBy(KnowledgeBaseHealthIssueEntity::getReportId));

        Map<UUID, KnowledgeBaseHealthReportResponse> result = new HashMap<>();
        for (KnowledgeBaseHealthReportEntity report : reports) {
            result.put(
                    report.getAssistantMessageId(),
                    KnowledgeBaseHealthReportResponse.from(
                            report,
                            issuesByReport.getOrDefault(report.getId(), List.of())));
        }
        return Map.copyOf(result);
    }

    private VerifiedMessageContext verifyMessageContext(
            UUID conversationId,
            UUID userMessageId,
            UUID assistantMessageId,
            UUID knowledgeBaseId) {
        ConversationEntity conversation = conversationMapper.selectById(conversationId);
        ChatMessageEntity userMessage = chatMessageMapper.selectById(userMessageId);
        ChatMessageEntity assistantMessage = chatMessageMapper.selectById(assistantMessageId);
        boolean valid = conversation != null
                && conversation.getKnowledgeBaseId().equals(knowledgeBaseId)
                && validMessage(userMessage, conversationId, ChatMessageRole.USER, ChatMessageStatus.COMPLETED)
                && validMessage(assistantMessage, conversationId, ChatMessageRole.ASSISTANT, ChatMessageStatus.PENDING)
                && userMessage.getCapabilityId() == CapabilityId.KNOWLEDGE_QA
                && assistantMessage.getCapabilityId() == CapabilityId.KNOWLEDGE_QA
                && Objects.equals(userMessage.getCapabilityVersion(), assistantMessage.getCapabilityVersion())
                && Objects.equals(userMessage.getTraceId(), assistantMessage.getTraceId());
        if (!valid) {
            throw new BadRequestException(
                    "HEALTH_REPORT_CONTEXT_MISMATCH",
                    "Health report messages do not belong to the expected knowledge base conversation");
        }
        return new VerifiedMessageContext(assistantMessage);
    }

    private boolean validMessage(
            ChatMessageEntity message,
            UUID conversationId,
            ChatMessageRole role,
            ChatMessageStatus status) {
        return message != null
                && message.getConversationId().equals(conversationId)
                && message.getRole() == role
                && message.getStatus() == status
                && message.getCapabilityVersion() != null
                && !message.getCapabilityVersion().isBlank()
                && message.getTraceId() != null
                && !message.getTraceId().isBlank();
    }

    private KnowledgeBaseHealthReportEntity findEntityByAssistantMessageId(UUID assistantMessageId) {
        return reportMapper.selectOne(
                Wrappers.<KnowledgeBaseHealthReportEntity>lambdaQuery()
                        .eq(KnowledgeBaseHealthReportEntity::getAssistantMessageId, assistantMessageId));
    }

    private List<KnowledgeBaseHealthIssueEntity> issuesForReport(UUID reportId) {
        return issueMapper.selectList(
                        Wrappers.<KnowledgeBaseHealthIssueEntity>lambdaQuery()
                                .eq(KnowledgeBaseHealthIssueEntity::getReportId, reportId))
                .stream()
                .sorted(ISSUE_ORDER)
                .toList();
    }

    private KnowledgeBaseHealthReportEntity reportEntity(
            KnowledgeBaseHealthAssessment assessment,
            VerifiedMessageContext context,
            UUID conversationId,
            UUID userMessageId,
            UUID assistantMessageId,
            OffsetDateTime now) {
        DocumentStatusCounts counts = assessment.documentCounts();
        EmbeddingIndexProfile profile = assessment.currentEmbeddingProfile();
        KnowledgeBaseHealthReportEntity entity = new KnowledgeBaseHealthReportEntity();
        entity.setId(UUID.randomUUID());
        entity.setKnowledgeBaseId(assessment.knowledgeBaseId());
        entity.setConversationId(conversationId);
        entity.setUserMessageId(userMessageId);
        entity.setAssistantMessageId(assistantMessageId);
        entity.setCapabilityId(CapabilityId.KNOWLEDGE_QA);
        entity.setCapabilityVersion(context.assistantMessage().getCapabilityVersion());
        entity.setHealthStatus(assessment.healthStatus());
        entity.setCompleteness(assessment.completeness());
        entity.setCompletenessReason(assessment.completenessReason());
        entity.setDataAsOf(assessment.dataAsOf());
        entity.setEmbeddingProfileId(profile.id());
        entity.setEmbeddingProvider(profile.provider());
        entity.setEmbeddingModel(profile.model());
        entity.setEmbeddingDimensions(profile.dimensions());
        entity.setEmbeddingProfileVersion(profile.version());
        entity.setDocumentTotalCount(counts.total());
        entity.setDocumentPendingCount(counts.pending());
        entity.setDocumentProcessingCount(counts.processing());
        entity.setDocumentSucceededCount(counts.succeeded());
        entity.setDocumentFailedCount(counts.failed());
        entity.setDocumentDeletingCount(counts.deleting());
        entity.setIssueCount(assessment.issueCount());
        entity.setReturnedIssueCount(assessment.returnedIssueCount());
        entity.setSummary(assessment.summary());
        entity.setTraceId(context.assistantMessage().getTraceId());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private KnowledgeBaseHealthIssueEntity issueEntity(
            UUID reportId,
            KnowledgeBaseHealthIssue issue,
            OffsetDateTime now) {
        KnowledgeBaseHealthIssueEntity entity = new KnowledgeBaseHealthIssueEntity();
        entity.setId(UUID.randomUUID());
        entity.setReportId(reportId);
        entity.setDocumentId(issue.documentId());
        entity.setOriginalFilename(issue.originalFilename());
        entity.setIssueType(issue.issueType());
        entity.setSeverity(issue.severity());
        entity.setObservedDocumentStatus(issue.observedDocumentStatus());
        entity.setObservedProcessingAttempts(issue.observedProcessingAttempts());
        entity.setObservedErrorSummary(issue.observedErrorSummary());
        entity.setObservedEmbeddingProfileId(issue.observedEmbeddingProfileId());
        entity.setObservedVectorCount(issue.observedVectorCount());
        entity.setSourceDocumentUpdatedAt(issue.sourceDocumentUpdatedAt());
        entity.setRecommendedActionType(issue.recommendedActionType());
        entity.setActionEligible(issue.actionEligible());
        entity.setIneligibilityReasonCode(issue.ineligibilityReasonCode());
        entity.setIneligibilitySummary(issue.ineligibilitySummary());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private ConflictException reportAlreadyExists(UUID assistantMessageId) {
        return new ConflictException(
                "KNOWLEDGE_BASE_HEALTH_REPORT_ALREADY_EXISTS",
                "A health report already exists for assistant message " + assistantMessageId);
    }

    private static int severityOrder(HealthIssueSeverity severity) {
        return switch (severity) {
            case ERROR -> 0;
            case WARNING -> 1;
        };
    }

    private static int issueTypeOrder(KnowledgeBaseHealthIssueType issueType) {
        return switch (issueType) {
            case DOCUMENT_PROCESSING_FAILED -> 0;
            case EMBEDDING_PROFILE_UNKNOWN -> 1;
            case EMBEDDING_PROFILE_OUTDATED -> 2;
            case VECTOR_INDEX_MISSING -> 3;
        };
    }

    private record VerifiedMessageContext(ChatMessageEntity assistantMessage) {
    }
}
