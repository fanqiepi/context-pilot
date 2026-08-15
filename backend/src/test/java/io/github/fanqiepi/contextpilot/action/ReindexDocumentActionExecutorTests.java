package io.github.fanqiepi.contextpilot.action;

import java.time.OffsetDateTime;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.document.DocumentFileType;
import io.github.fanqiepi.contextpilot.document.DocumentResponse;
import io.github.fanqiepi.contextpilot.document.DocumentService;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexCompatibility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReindexDocumentActionExecutorTests {

    private final DocumentService documentService = mock(DocumentService.class);
    private final ReindexDocumentActionExecutor executor = new ReindexDocumentActionExecutor(documentService);

    @Test
    void returnsSubmittedSummaryAfterDocumentBecomesPending() {
        ReindexDocumentActionParameters parameters = parameters();
        when(documentService.reindex(parameters.documentId())).thenReturn(document(
                parameters.documentId(), DocumentStatus.PENDING));

        ActionExecutionResult result = executor.execute(parameters);

        assertThat(result.resultSummary()).contains("已提交").contains("最终结果");
    }

    @Test
    void translatesMissingOrChangedTargetsToStableStateChangedError() {
        ReindexDocumentActionParameters missing = parameters();
        when(documentService.reindex(missing.documentId())).thenThrow(new ResourceNotFoundException(
                "DOCUMENT_NOT_FOUND", "missing"));
        assertThatThrownBy(() -> executor.execute(missing))
                .isInstanceOf(ConflictException.class)
                .extracting("code")
                .isEqualTo("ACTION_TARGET_STATE_CHANGED");

        ReindexDocumentActionParameters changed = parameters();
        when(documentService.reindex(changed.documentId())).thenThrow(new ConflictException(
                "DOCUMENT_REINDEX_NOT_ALLOWED", "unsafe internal detail"));
        assertThatThrownBy(() -> executor.execute(changed))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("状态已变化")
                .extracting("code")
                .isEqualTo("ACTION_TARGET_STATE_CHANGED");
    }

    @Test
    void keepsSafeCodesForNoLongerRequiredOrUnavailableReindex() {
        ReindexDocumentActionParameters notRequired = parameters();
        when(documentService.reindex(notRequired.documentId())).thenThrow(new ConflictException(
                "DOCUMENT_REINDEX_NOT_REQUIRED", "database detail"));
        assertThatThrownBy(() -> executor.execute(notRequired))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("无需提交")
                .extracting("code")
                .isEqualTo("DOCUMENT_REINDEX_NOT_REQUIRED");

        ReindexDocumentActionParameters unavailable = parameters();
        when(documentService.reindex(unavailable.documentId())).thenThrow(new ConflictException(
                "VECTOR_STORE_UNAVAILABLE", "connection refused"));
        assertThatThrownBy(() -> executor.execute(unavailable))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("向量存储当前不可用")
                .hasMessageNotContaining("connection refused")
                .extracting("code")
                .isEqualTo("VECTOR_STORE_UNAVAILABLE");
    }

    private ReindexDocumentActionParameters parameters() {
        return new ReindexDocumentActionParameters(
                UUID.randomUUID(),
                "outdated.md",
                DocumentStatus.SUCCEEDED,
                "profile-v0",
                UUID.randomUUID(),
                UUID.randomUUID());
    }

    private DocumentResponse document(UUID id, DocumentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new DocumentResponse(
                id,
                UUID.randomUUID(),
                "outdated.md",
                DocumentFileType.MARKDOWN,
                "text/markdown",
                1,
                "0".repeat(64),
                status,
                null,
                0,
                null,
                EmbeddingIndexCompatibility.NOT_INDEXED,
                now,
                now);
    }
}
