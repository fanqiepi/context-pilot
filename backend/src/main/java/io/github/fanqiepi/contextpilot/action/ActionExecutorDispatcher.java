package io.github.fanqiepi.contextpilot.action;

import org.springframework.stereotype.Component;

@Component
public class ActionExecutorDispatcher {

    private final CreateKnowledgeBaseActionExecutor createKnowledgeBaseExecutor;
    private final RetryDocumentProcessingActionExecutor retryDocumentProcessingExecutor;
    private final ReindexDocumentActionExecutor reindexDocumentExecutor;
    private final CreateUserMemoryActionExecutor createUserMemoryExecutor;

    public ActionExecutorDispatcher(
            CreateKnowledgeBaseActionExecutor createKnowledgeBaseExecutor,
            RetryDocumentProcessingActionExecutor retryDocumentProcessingExecutor,
            ReindexDocumentActionExecutor reindexDocumentExecutor,
            CreateUserMemoryActionExecutor createUserMemoryExecutor) {
        this.createKnowledgeBaseExecutor = createKnowledgeBaseExecutor;
        this.retryDocumentProcessingExecutor = retryDocumentProcessingExecutor;
        this.reindexDocumentExecutor = reindexDocumentExecutor;
        this.createUserMemoryExecutor = createUserMemoryExecutor;
    }

    public ActionExecutionResult execute(ActionType actionType, ActionParameters parameters) {
        return execute(actionType, parameters, null);
    }

    public ActionExecutionResult execute(
            ActionType actionType,
            ActionParameters parameters,
            ActionExecutionContext context) {
        if (parameters == null || parameters.actionType() != actionType) {
            throw new IllegalArgumentException("Action type and parameter type must match");
        }
        return switch (actionType) {
            case CREATE_KNOWLEDGE_BASE -> createKnowledgeBaseExecutor.execute(
                    (CreateKnowledgeBaseActionParameters) parameters);
            case RETRY_DOCUMENT_PROCESSING -> retryDocumentProcessingExecutor.execute(
                    (RetryDocumentProcessingActionParameters) parameters);
            case REINDEX_DOCUMENT -> reindexDocumentExecutor.execute(
                    (ReindexDocumentActionParameters) parameters);
            case CREATE_USER_MEMORY -> {
                if (context == null) {
                    throw new IllegalArgumentException("Create user memory requires execution context");
                }
                yield createUserMemoryExecutor.execute((CreateUserMemoryActionParameters) parameters, context);
            }
        };
    }
}
