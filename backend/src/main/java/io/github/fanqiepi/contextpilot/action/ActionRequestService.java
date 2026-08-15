package io.github.fanqiepi.contextpilot.action;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.fanqiepi.contextpilot.chat.CapabilityId;
import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActionRequestService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActionRequestService.class);
    private static final String INTERNAL_FAILURE_SUMMARY = "操作执行失败，请稍后重试";
    private static final String CREATE_KNOWLEDGE_BASE_FAILURE_SUMMARY = "创建知识库失败，请稍后重试";

    private final ActionRequestMapper actionRequestMapper;
    private final ActionRequestProperties properties;
    private final ActionParametersCodec parametersCodec;
    private final ActionExecutorDispatcher executorDispatcher;

    public ActionRequestService(
            ActionRequestMapper actionRequestMapper,
            ActionRequestProperties properties,
            ActionParametersCodec parametersCodec,
            ActionExecutorDispatcher executorDispatcher) {
        this.actionRequestMapper = actionRequestMapper;
        this.properties = properties;
        this.parametersCodec = parametersCodec;
        this.executorDispatcher = executorDispatcher;
    }

    @Transactional
    public ActionRequestResponse proposeCreateKnowledgeBase(
            UUID conversationId,
            UUID userMessageId,
            UUID assistantMessageId,
            CapabilityId capabilityId,
            String capabilityVersion,
            String traceId,
            CreateKnowledgeBaseActionParameters parameters) {
        if (capabilityId != CapabilityId.BUSINESS_ACTION) {
            throw new IllegalArgumentException("Action proposals require the BUSINESS_ACTION capability");
        }
        OffsetDateTime now = now();
        ActionRequestEntity entity = new ActionRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setConversationId(conversationId);
        entity.setUserMessageId(userMessageId);
        entity.setAssistantMessageId(assistantMessageId);
        entity.setCapabilityId(capabilityId);
        entity.setCapabilityVersion(capabilityVersion);
        entity.setActionType(ActionType.CREATE_KNOWLEDGE_BASE);
        entity.setParametersJson(parametersCodec.write(ActionType.CREATE_KNOWLEDGE_BASE, parameters));
        entity.setDisplaySummary(displaySummary(parameters));
        entity.setStatus(ActionRequestStatus.PENDING_CONFIRMATION);
        entity.setTraceId(traceId);
        entity.setExpiresAt(now.plusMinutes(properties.getConfirmationTimeoutMinutes()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        actionRequestMapper.insert(entity);
        return response(entity);
    }

    @Transactional
    public ActionRequestResponse proposeRetryDocumentProcessing(
            UUID conversationId,
            UUID userMessageId,
            UUID assistantMessageId,
            String capabilityVersion,
            String traceId,
            RetryDocumentProcessingActionParameters parameters) {
        OffsetDateTime now = now();
        ActionRequestEntity entity = new ActionRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setConversationId(conversationId);
        entity.setUserMessageId(userMessageId);
        entity.setAssistantMessageId(assistantMessageId);
        entity.setCapabilityId(CapabilityId.BUSINESS_ACTION);
        entity.setCapabilityVersion(capabilityVersion);
        entity.setActionType(ActionType.RETRY_DOCUMENT_PROCESSING);
        entity.setParametersJson(parametersCodec.write(ActionType.RETRY_DOCUMENT_PROCESSING, parameters));
        entity.setTargetDocumentId(parameters.documentId());
        entity.setHealthIssueId(parameters.healthIssueId());
        entity.setDisplaySummary("确认后将为文档“%s”提交单次重试处理任务，最终结果以文档状态为准。"
                .formatted(parameters.originalFilenameSnapshot()));
        entity.setStatus(ActionRequestStatus.PENDING_CONFIRMATION);
        entity.setTraceId(traceId);
        entity.setExpiresAt(now.plusMinutes(properties.getConfirmationTimeoutMinutes()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (actionRequestMapper.insert(entity) != 1) {
            throw new IllegalStateException("Retry document action proposal could not be created");
        }
        return response(entity);
    }

    @Transactional
    public ActionRequestResponse proposeReindexDocument(
            UUID conversationId,
            UUID userMessageId,
            UUID assistantMessageId,
            String capabilityVersion,
            String traceId,
            ReindexDocumentActionParameters parameters) {
        OffsetDateTime now = now();
        ActionRequestEntity entity = new ActionRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setConversationId(conversationId);
        entity.setUserMessageId(userMessageId);
        entity.setAssistantMessageId(assistantMessageId);
        entity.setCapabilityId(CapabilityId.BUSINESS_ACTION);
        entity.setCapabilityVersion(capabilityVersion);
        entity.setActionType(ActionType.REINDEX_DOCUMENT);
        entity.setParametersJson(parametersCodec.write(ActionType.REINDEX_DOCUMENT, parameters));
        entity.setTargetDocumentId(parameters.documentId());
        entity.setHealthIssueId(parameters.healthIssueId());
        entity.setDisplaySummary("确认后将为文档“%s”提交单次索引重建任务，最终结果以文档状态为准。"
                .formatted(parameters.originalFilenameSnapshot()));
        entity.setStatus(ActionRequestStatus.PENDING_CONFIRMATION);
        entity.setTraceId(traceId);
        entity.setExpiresAt(now.plusMinutes(properties.getConfirmationTimeoutMinutes()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        if (actionRequestMapper.insert(entity) != 1) {
            throw new IllegalStateException("Reindex document action proposal could not be created");
        }
        return response(entity);
    }

    @Transactional
    public ActionRequestResponse findByHealthIssueId(UUID healthIssueId) {
        ActionRequestEntity entity = actionRequestMapper.selectByHealthIssueId(healthIssueId);
        if (entity == null) {
            return null;
        }
        OffsetDateTime now = now();
        if (isExpiredPending(entity, now) && actionRequestMapper.expire(entity.getId(), now) == 1) {
            entity.setStatus(ActionRequestStatus.EXPIRED);
            entity.setUpdatedAt(now);
        }
        return response(entity);
    }

    @Transactional
    public ActionRequestResponse get(UUID id) {
        OffsetDateTime now = now();
        expire(id, now);
        return response(requireEntity(id));
    }

    @Transactional
    public Map<UUID, ActionRequestResponse> findByAssistantMessageIds(List<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        OffsetDateTime now = now();
        List<ActionRequestEntity> entities = actionRequestMapper.selectByAssistantMessageIds(messageIds);
        List<ActionRequestEntity> currentEntities = entities.stream().map(entity -> {
            if (!isExpiredPending(entity, now)) {
                return entity;
            }
            if (actionRequestMapper.expire(entity.getId(), now) == 1) {
                entity.setStatus(ActionRequestStatus.EXPIRED);
                entity.setUpdatedAt(now);
                return entity;
            }
            return requireEntity(entity.getId());
        }).toList();
        return currentEntities.stream().collect(Collectors.toMap(
                ActionRequestEntity::getAssistantMessageId,
                this::response,
                (left, right) -> left,
                LinkedHashMap::new));
    }

    @Transactional
    public ActionRequestResponse confirm(UUID id) {
        OffsetDateTime now = now();
        expire(id, now);
        ActionRequestEntity current = requireEntity(id);
        if (current.getStatus() != ActionRequestStatus.PENDING_CONFIRMATION) {
            return response(current);
        }
        if (actionRequestMapper.claimExecution(id, now) == 0) {
            return response(requireEntity(id));
        }

        ActionParameters parameters = parametersCodec.read(
                current.getActionType(), current.getParametersJson());
        ActionExecutionResult result;
        try {
            result = executorDispatcher.execute(current.getActionType(), parameters);
        } catch (BadRequestException | ConflictException exception) {
            completeFailure(id, exception.getMessage());
            return response(requireEntity(id));
        } catch (RuntimeException exception) {
            LOGGER.error("Action execution failed, actionRequestId={}, traceId={}",
                    id, current.getTraceId(), exception);
            completeFailure(id, internalFailureSummary(current.getActionType()));
            return response(requireEntity(id));
        }
        if (actionRequestMapper.completeSuccess(id, safeSummary(result.resultSummary()), now()) != 1) {
            throw new IllegalStateException("Claimed action request could not be completed");
        }
        return response(requireEntity(id));
    }

    @Transactional
    public ActionRequestResponse reject(UUID id) {
        OffsetDateTime now = now();
        expire(id, now);
        ActionRequestEntity current = requireEntity(id);
        if (current.getStatus() == ActionRequestStatus.REJECTED) {
            return response(current);
        }
        if (current.getStatus() != ActionRequestStatus.PENDING_CONFIRMATION) {
            throw new ConflictException(
                    "ACTION_REQUEST_STATUS_CONFLICT",
                    "Only a pending action request can be rejected");
        }
        if (actionRequestMapper.reject(id, now) != 1) {
            throw new ConflictException(
                    "ACTION_REQUEST_STATUS_CONFLICT",
                    "Action request status changed before it could be rejected");
        }
        return response(requireEntity(id));
    }

    private void completeFailure(UUID id, String summary) {
        if (actionRequestMapper.completeFailure(id, safeSummary(summary), now()) != 1) {
            throw new IllegalStateException("Claimed action request could not be marked failed");
        }
    }

    private void expire(UUID id, OffsetDateTime now) {
        actionRequestMapper.expire(id, now);
    }

    private ActionRequestEntity requireEntity(UUID id) {
        ActionRequestEntity entity = actionRequestMapper.selectById(id);
        if (entity == null) {
            throw new ResourceNotFoundException(
                    "ACTION_REQUEST_NOT_FOUND",
                    "Action request " + id + " was not found");
        }
        return entity;
    }

    private ActionRequestResponse response(ActionRequestEntity entity) {
        return ActionRequestResponse.from(entity, parametersCodec);
    }

    private boolean isExpiredPending(ActionRequestEntity entity, OffsetDateTime now) {
        return entity.getStatus() == ActionRequestStatus.PENDING_CONFIRMATION
                && !entity.getExpiresAt().isAfter(now);
    }

    private String displaySummary(CreateKnowledgeBaseActionParameters parameters) {
        if (parameters.description() == null) {
            return "确认后将创建知识库“%s”。".formatted(parameters.name());
        }
        return "确认后将创建知识库“%s”，描述为“%s”。"
                .formatted(parameters.name(), parameters.description());
    }

    private String safeSummary(String value) {
        String normalized = value == null ? INTERNAL_FAILURE_SUMMARY : value.strip();
        if (normalized.isEmpty()) {
            return INTERNAL_FAILURE_SUMMARY;
        }
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private String internalFailureSummary(ActionType actionType) {
        return switch (actionType) {
            case CREATE_KNOWLEDGE_BASE -> CREATE_KNOWLEDGE_BASE_FAILURE_SUMMARY;
            case RETRY_DOCUMENT_PROCESSING -> "提交文档重试任务失败，请稍后重试";
            case REINDEX_DOCUMENT -> "提交索引重建任务失败，请稍后重试";
        };
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
