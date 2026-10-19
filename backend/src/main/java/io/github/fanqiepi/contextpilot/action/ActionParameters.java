package io.github.fanqiepi.contextpilot.action;

import com.fasterxml.jackson.annotation.JsonIgnore;

public sealed interface ActionParameters permits
        CreateKnowledgeBaseActionParameters,
        RetryDocumentProcessingActionParameters,
        ReindexDocumentActionParameters,
        CreateUserMemoryActionParameters,
        SaveResearchReportActionParameters,
        ImportProjectDocumentsActionParameters {

    @JsonIgnore
    ActionType actionType();
}
