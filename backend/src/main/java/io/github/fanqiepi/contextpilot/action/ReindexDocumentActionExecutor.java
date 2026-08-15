package io.github.fanqiepi.contextpilot.action;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.document.DocumentResponse;
import io.github.fanqiepi.contextpilot.document.DocumentService;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import org.springframework.stereotype.Component;

@Component
public class ReindexDocumentActionExecutor {

    private final DocumentService documentService;

    public ReindexDocumentActionExecutor(DocumentService documentService) {
        this.documentService = documentService;
    }

    public ActionExecutionResult execute(ReindexDocumentActionParameters parameters) {
        DocumentResponse document;
        try {
            document = documentService.reindex(parameters.documentId());
        } catch (ResourceNotFoundException exception) {
            throw targetStateChanged(parameters.documentId());
        } catch (ConflictException exception) {
            throw translateConflict(parameters.documentId(), exception);
        }
        if (document.status() != DocumentStatus.PENDING) {
            throw new IllegalStateException("Accepted reindex did not leave the document pending");
        }
        return new ActionExecutionResult(
                "已提交文档“%s”的索引重建任务，请通过文档状态查看最终结果。"
                        .formatted(parameters.originalFilenameSnapshot()));
    }

    private ConflictException translateConflict(UUID documentId, ConflictException exception) {
        return switch (exception.getCode()) {
            case "DOCUMENT_REINDEX_NOT_ALLOWED" -> targetStateChanged(documentId);
            case "DOCUMENT_REINDEX_NOT_REQUIRED" -> new ConflictException(
                    "DOCUMENT_REINDEX_NOT_REQUIRED",
                    "目标文档已具有当前索引，无需提交重建任务。");
            case "DOCUMENT_PROCESSING_DISABLED" -> new ConflictException(
                    "DOCUMENT_PROCESSING_DISABLED",
                    "文档处理当前未启用，未提交索引重建任务。");
            case "VECTOR_STORE_UNAVAILABLE" -> new ConflictException(
                    "VECTOR_STORE_UNAVAILABLE",
                    "向量存储当前不可用，未提交索引重建任务。");
            default -> exception;
        };
    }

    private ConflictException targetStateChanged(UUID documentId) {
        return new ConflictException(
                "ACTION_TARGET_STATE_CHANGED",
                "目标文档状态已变化，未提交索引重建任务：" + documentId);
    }
}
