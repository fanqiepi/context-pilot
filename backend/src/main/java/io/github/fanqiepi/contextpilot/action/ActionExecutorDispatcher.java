package io.github.fanqiepi.contextpilot.action;

import org.springframework.stereotype.Component;

@Component
public class ActionExecutorDispatcher {

    private final CreateKnowledgeBaseActionExecutor createKnowledgeBaseExecutor;

    public ActionExecutorDispatcher(CreateKnowledgeBaseActionExecutor createKnowledgeBaseExecutor) {
        this.createKnowledgeBaseExecutor = createKnowledgeBaseExecutor;
    }

    public ActionExecutionResult execute(ActionType actionType, ActionParameters parameters) {
        if (parameters == null || parameters.actionType() != actionType) {
            throw new IllegalArgumentException("Action type and parameter type must match");
        }
        return switch (actionType) {
            case CREATE_KNOWLEDGE_BASE -> createKnowledgeBaseExecutor.execute(
                    (CreateKnowledgeBaseActionParameters) parameters);
            case RETRY_DOCUMENT_PROCESSING, REINDEX_DOCUMENT -> throw new IllegalStateException(
                    "Action executor is not implemented in V3 Slice 4: " + actionType);
        };
    }
}
