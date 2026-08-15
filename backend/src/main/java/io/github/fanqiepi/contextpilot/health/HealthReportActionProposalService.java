package io.github.fanqiepi.contextpilot.health;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.action.ActionRequestResponse;
import io.github.fanqiepi.contextpilot.action.ActionRequestService;
import io.github.fanqiepi.contextpilot.action.ActionType;
import io.github.fanqiepi.contextpilot.action.RetryDocumentProcessingActionParameters;
import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.chat.CapabilityMatchReason;
import io.github.fanqiepi.contextpilot.chat.ChatMessageEntity;
import io.github.fanqiepi.contextpilot.chat.ChatMessageMapper;
import io.github.fanqiepi.contextpilot.chat.ChatMessageRole;
import io.github.fanqiepi.contextpilot.chat.ChatMessageStatus;
import io.github.fanqiepi.contextpilot.chat.ConversationEntity;
import io.github.fanqiepi.contextpilot.chat.ConversationMapper;
import io.github.fanqiepi.contextpilot.chat.ConversationMessageResponse;
import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.document.DocumentProcessingCoordinator;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import io.github.fanqiepi.contextpilot.document.SourceDocumentEntity;
import io.github.fanqiepi.contextpilot.document.SourceDocumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HealthReportActionProposalService {

    static final String CAPABILITY_VERSION = "v2";
    static final String ASSISTANT_PROPOSAL_MESSAGE =
            "已生成文档重试提案。请核对目标和影响，并通过操作卡片明确确认或取消。";

    private final KnowledgeBaseHealthReportMapper reportMapper;
    private final KnowledgeBaseHealthIssueMapper issueMapper;
    private final ConversationMapper conversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final SourceDocumentMapper sourceDocumentMapper;
    private final DocumentProcessingCoordinator processingCoordinator;
    private final ActionRequestService actionRequestService;

    public HealthReportActionProposalService(
            KnowledgeBaseHealthReportMapper reportMapper,
            KnowledgeBaseHealthIssueMapper issueMapper,
            ConversationMapper conversationMapper,
            ChatMessageMapper chatMessageMapper,
            SourceDocumentMapper sourceDocumentMapper,
            DocumentProcessingCoordinator processingCoordinator,
            ActionRequestService actionRequestService) {
        this.reportMapper = reportMapper;
        this.issueMapper = issueMapper;
        this.conversationMapper = conversationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.sourceDocumentMapper = sourceDocumentMapper;
        this.processingCoordinator = processingCoordinator;
        this.actionRequestService = actionRequestService;
    }

    @Transactional
    public HealthReportActionProposalResponse propose(UUID reportId, UUID issueId, String traceId) {
        KnowledgeBaseHealthReportEntity report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new ResourceNotFoundException(
                    "HEALTH_REPORT_NOT_FOUND",
                    "Knowledge base health report " + reportId + " was not found");
        }
        KnowledgeBaseHealthIssueEntity issue = issueMapper.selectForActionProposal(reportId, issueId);
        if (issue == null) {
            throw new ResourceNotFoundException(
                    "HEALTH_REPORT_ISSUE_NOT_FOUND",
                    "Health report issue " + issueId + " was not found in report " + reportId);
        }

        ConversationEntity conversation = verifyContext(report, issue);
        ActionRequestResponse existing = actionRequestService.findByHealthIssueId(issueId);
        if (existing != null) {
            return response(report, issue, existing, true);
        }

        SourceDocumentEntity document = requireRetryableDocument(report, issue);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        ChatMessageEntity userMessage = message(
                conversation.getId(),
                ChatMessageRole.USER,
                "为健康报告中的文档“%s”生成重试处理提案。".formatted(issue.getOriginalFilename()),
                traceId,
                now);
        ChatMessageEntity assistantMessage = message(
                conversation.getId(),
                ChatMessageRole.ASSISTANT,
                ASSISTANT_PROPOSAL_MESSAGE,
                traceId,
                now);
        if (chatMessageMapper.insert(userMessage) != 1 || chatMessageMapper.insert(assistantMessage) != 1) {
            throw new IllegalStateException("Health report action proposal messages could not be created");
        }

        conversation.setUpdatedAt(now);
        if (conversationMapper.updateById(conversation) != 1) {
            throw new IllegalStateException("Health report action proposal conversation could not be updated");
        }

        RetryDocumentProcessingActionParameters parameters =
                new RetryDocumentProcessingActionParameters(
                        document.getId(),
                        issue.getOriginalFilename(),
                        DocumentStatus.FAILED,
                        report.getId(),
                        issue.getId());
        ActionRequestResponse actionRequest = actionRequestService.proposeRetryDocumentProcessing(
                conversation.getId(),
                userMessage.getId(),
                assistantMessage.getId(),
                CAPABILITY_VERSION,
                traceId,
                parameters);
        return new HealthReportActionProposalResponse(
                false,
                messageResponse(userMessage, null),
                messageResponse(assistantMessage, actionRequest),
                actionRequest);
    }

    private ConversationEntity verifyContext(
            KnowledgeBaseHealthReportEntity report,
            KnowledgeBaseHealthIssueEntity issue) {
        ConversationEntity conversation = conversationMapper.selectById(report.getConversationId());
        if (conversation == null
                || !conversation.getKnowledgeBaseId().equals(report.getKnowledgeBaseId())
                || !issue.getReportId().equals(report.getId())) {
            throw contextMismatch();
        }
        if (!issue.isActionEligible()
                || issue.getIssueType() != KnowledgeBaseHealthIssueType.DOCUMENT_PROCESSING_FAILED
                || issue.getRecommendedActionType() != HealthRecommendedActionType.RETRY_DOCUMENT_PROCESSING
                || issue.getObservedDocumentStatus() != DocumentStatus.FAILED) {
            throw new ConflictException(
                    "HEALTH_REPORT_ISSUE_NOT_ACTIONABLE",
                    "The selected health report issue does not allow document retry");
        }
        return conversation;
    }

    private SourceDocumentEntity requireRetryableDocument(
            KnowledgeBaseHealthReportEntity report,
            KnowledgeBaseHealthIssueEntity issue) {
        SourceDocumentEntity document = sourceDocumentMapper.selectById(issue.getDocumentId());
        if (document == null || !document.getKnowledgeBaseId().equals(report.getKnowledgeBaseId())) {
            throw contextMismatch();
        }
        if (document.getStatus() != DocumentStatus.FAILED) {
            throw new ConflictException(
                    "ACTION_TARGET_STATE_CHANGED",
                    "目标文档状态已变化，无法生成重试提案。");
        }
        if (!processingCoordinator.isEnabled()) {
            throw new ConflictException(
                    "DOCUMENT_PROCESSING_DISABLED",
                    "文档处理当前未启用，无法生成重试提案。");
        }
        if (!processingCoordinator.canRetry(document.getProcessingAttempts())) {
            throw new ConflictException(
                    "DOCUMENT_RETRY_LIMIT_REACHED",
                    "目标文档已达到最大处理次数，无法生成重试提案。");
        }
        return document;
    }

    private HealthReportActionProposalResponse response(
            KnowledgeBaseHealthReportEntity report,
            KnowledgeBaseHealthIssueEntity issue,
            ActionRequestResponse actionRequest,
            boolean reusedExistingProposal) {
        if (actionRequest.actionType() != ActionType.RETRY_DOCUMENT_PROCESSING
                || !actionRequest.conversationId().equals(report.getConversationId())
                || !(actionRequest.parameters() instanceof RetryDocumentProcessingActionParameters parameters)
                || !parameters.documentId().equals(issue.getDocumentId())
                || !parameters.healthReportId().equals(report.getId())
                || !parameters.healthIssueId().equals(issue.getId())) {
            throw contextMismatch();
        }
        ChatMessageEntity userMessage = chatMessageMapper.selectById(actionRequest.userMessageId());
        ChatMessageEntity assistantMessage = chatMessageMapper.selectById(actionRequest.assistantMessageId());
        if (userMessage == null
                || assistantMessage == null
                || !userMessage.getConversationId().equals(report.getConversationId())
                || !assistantMessage.getConversationId().equals(report.getConversationId())
                || userMessage.getRole() != ChatMessageRole.USER
                || assistantMessage.getRole() != ChatMessageRole.ASSISTANT) {
            throw contextMismatch();
        }
        return new HealthReportActionProposalResponse(
                reusedExistingProposal,
                messageResponse(userMessage, null),
                messageResponse(assistantMessage, actionRequest),
                actionRequest);
    }

    private ChatMessageEntity message(
            UUID conversationId,
            ChatMessageRole role,
            String content,
            String traceId,
            OffsetDateTime now) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(UUID.randomUUID());
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setStatus(ChatMessageStatus.COMPLETED);
        message.setTraceId(traceId);
        message.setCapabilityId(CapabilityId.BUSINESS_ACTION);
        message.setCapabilityVersion(CAPABILITY_VERSION);
        message.setCapabilityMatchReason(CapabilityMatchReason.HEALTH_REPORT_ISSUE_SELECTED);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        return message;
    }

    private ConversationMessageResponse messageResponse(
            ChatMessageEntity message,
            ActionRequestResponse actionRequest) {
        return ConversationMessageResponse.from(
                message, null, actionRequest, List.of(), false);
    }

    private BadRequestException contextMismatch() {
        return new BadRequestException(
                "HEALTH_REPORT_CONTEXT_MISMATCH",
                "Health report, issue, conversation, knowledge base, or document context does not match");
    }
}
