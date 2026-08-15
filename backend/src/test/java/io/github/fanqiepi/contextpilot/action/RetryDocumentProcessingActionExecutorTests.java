package io.github.fanqiepi.contextpilot.action;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.document.DocumentResponse;
import io.github.fanqiepi.contextpilot.document.DocumentService;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetryDocumentProcessingActionExecutorTests {

    private final DocumentService documentService = mock(DocumentService.class);
    private final RetryDocumentProcessingActionExecutor executor =
            new RetryDocumentProcessingActionExecutor(documentService);

    @Test
    void acceptsOnePendingRetryAndReturnsTaskSubmissionSummary() {
        RetryDocumentProcessingActionParameters parameters = parameters();
        when(documentService.retry(parameters.documentId())).thenReturn(document(
                parameters.documentId(), DocumentStatus.PENDING));

        assertThat(executor.execute(parameters).resultSummary())
                .contains("failed.md")
                .contains("已提交")
                .contains("最终结果");
    }

    @Test
    void translatesMissingOrChangedTargetToStableStaleStateFailure() {
        RetryDocumentProcessingActionParameters missing = parameters();
        when(documentService.retry(missing.documentId())).thenThrow(new ResourceNotFoundException(
                "DOCUMENT_NOT_FOUND", "Document was not found"));

        assertThatThrownBy(() -> executor.execute(missing))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("ACTION_TARGET_STATE_CHANGED");

        RetryDocumentProcessingActionParameters changed = parameters();
        when(documentService.retry(changed.documentId())).thenThrow(new ConflictException(
                "DOCUMENT_RETRY_NOT_ALLOWED", "Document cannot be retried"));
        assertThatThrownBy(() -> executor.execute(changed))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("ACTION_TARGET_STATE_CHANGED");
    }

    @Test
    void preservesStableRetryLimitCodeWithSafeSummary() {
        RetryDocumentProcessingActionParameters parameters = parameters();
        when(documentService.retry(parameters.documentId())).thenThrow(new ConflictException(
                "DOCUMENT_RETRY_LIMIT_REACHED", "internal detail"));

        assertThatThrownBy(() -> executor.execute(parameters))
                .isInstanceOf(ConflictException.class)
                .hasMessage("目标文档已达到最大处理次数，未提交重试任务。")
                .extracting("code")
                .isEqualTo("DOCUMENT_RETRY_LIMIT_REACHED");
    }

    private RetryDocumentProcessingActionParameters parameters() {
        return new RetryDocumentProcessingActionParameters(
                UUID.randomUUID(),
                "failed.md",
                DocumentStatus.FAILED,
                UUID.randomUUID(),
                UUID.randomUUID());
    }

    private DocumentResponse document(UUID documentId, DocumentStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-15T07:00:00Z");
        return new DocumentResponse(
                documentId,
                UUID.randomUUID(),
                "failed.md",
                null,
                "text/markdown",
                1,
                "0".repeat(64),
                status,
                null,
                1,
                null,
                null,
                now,
                now);
    }
}
