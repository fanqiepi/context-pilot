package io.github.fanqiepi.contextpilot.action;

import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionParametersCodecTests {

    private final ActionParametersCodec codec = new ActionParametersCodec(new ObjectMapper());

    @Test
    void roundTripsEveryStaticallyAllowedParameterType() {
        UUID documentId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        UUID retryIssueId = UUID.randomUUID();
        UUID reindexIssueId = UUID.randomUUID();
        List<ActionParameters> parameters = List.of(
                new CreateKnowledgeBaseActionParameters("Java 学习", null),
                new RetryDocumentProcessingActionParameters(
                        documentId,
                        " retry.md ",
                        DocumentStatus.FAILED,
                        reportId,
                        retryIssueId),
                new ReindexDocumentActionParameters(
                        documentId,
                        " index.md ",
                        DocumentStatus.SUCCEEDED,
                        " profile-v1 ",
                        reportId,
                        reindexIssueId));

        for (ActionParameters value : parameters) {
            String json = codec.write(value.actionType(), value);

            assertThat(codec.read(value.actionType(), json)).isEqualTo(value);
            assertThat(json).doesNotContain("actionType");
        }
        assertThat(codec.write(parameters.getFirst().actionType(), parameters.getFirst()))
                .doesNotContain("description");
    }

    @Test
    void rejectsMismatchedOrMalformedPersistedParameters() {
        ActionParameters create = new CreateKnowledgeBaseActionParameters("Java 学习", null);

        assertThatThrownBy(() -> codec.write(ActionType.REINDEX_DOCUMENT, create))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.read(
                ActionType.RETRY_DOCUMENT_PROCESSING,
                "{\"documentId\":\"not-a-uuid\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ActionType.RETRY_DOCUMENT_PROCESSING.name());
    }
}
