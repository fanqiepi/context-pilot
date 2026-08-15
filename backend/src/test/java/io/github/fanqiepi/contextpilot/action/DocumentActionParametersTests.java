package io.github.fanqiepi.contextpilot.action;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentActionParametersTests {

    @Test
    void normalizesDocumentSnapshots() {
        RetryDocumentProcessingActionParameters retry = new RetryDocumentProcessingActionParameters(
                UUID.randomUUID(),
                "  failed   document.md  ",
                DocumentStatus.FAILED,
                UUID.randomUUID(),
                UUID.randomUUID());
        ReindexDocumentActionParameters reindex = new ReindexDocumentActionParameters(
                UUID.randomUUID(),
                "  indexed.md  ",
                DocumentStatus.SUCCEEDED,
                "  dashscope:qwen  ",
                UUID.randomUUID(),
                UUID.randomUUID());

        assertThat(retry.originalFilenameSnapshot()).isEqualTo("failed document.md");
        assertThat(reindex.originalFilenameSnapshot()).isEqualTo("indexed.md");
        assertThat(reindex.observedEmbeddingProfileId()).isEqualTo("dashscope:qwen");
    }

    @Test
    void enforcesActionSpecificObservedStatusAndTrustedIds() {
        assertThatThrownBy(() -> new RetryDocumentProcessingActionParameters(
                UUID.randomUUID(),
                "failed.md",
                DocumentStatus.SUCCEEDED,
                UUID.randomUUID(),
                UUID.randomUUID()))
                .isInstanceOfSatisfying(BadRequestException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INVALID_ACTION_DOCUMENT_STATUS"));

        assertThatThrownBy(() -> new ReindexDocumentActionParameters(
                UUID.randomUUID(),
                "indexed.md",
                DocumentStatus.SUCCEEDED,
                null,
                UUID.randomUUID(),
                null))
                .isInstanceOfSatisfying(BadRequestException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INVALID_ACTION_HEALTH_ISSUE_ID"));
    }
}
