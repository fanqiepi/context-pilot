package io.github.fanqiepi.contextpilot.action;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ActionExecutorDispatcherTests {

    private final CreateKnowledgeBaseActionExecutor createExecutor =
            mock(CreateKnowledgeBaseActionExecutor.class);
    private final RetryDocumentProcessingActionExecutor retryExecutor =
            mock(RetryDocumentProcessingActionExecutor.class);
    private final ActionExecutorDispatcher dispatcher =
            new ActionExecutorDispatcher(createExecutor, retryExecutor);

    @Test
    void dispatchesCreateKnowledgeBaseWithItsStrongParameterType() {
        CreateKnowledgeBaseActionParameters parameters =
                new CreateKnowledgeBaseActionParameters("Java 学习", null);
        ActionExecutionResult expected = new ActionExecutionResult("知识库已创建");
        when(createExecutor.execute(parameters)).thenReturn(expected);

        assertThat(dispatcher.execute(ActionType.CREATE_KNOWLEDGE_BASE, parameters))
                .isSameAs(expected);
        verify(createExecutor).execute(parameters);
    }

    @Test
    void dispatchesRetryAndRefusesMismatchedParameters() {
        RetryDocumentProcessingActionParameters retry = new RetryDocumentProcessingActionParameters(
                UUID.randomUUID(),
                "failed.md",
                DocumentStatus.FAILED,
                UUID.randomUUID(),
                UUID.randomUUID());
        ActionExecutionResult expected = new ActionExecutionResult("重试任务已提交");
        when(retryExecutor.execute(retry)).thenReturn(expected);

        assertThatThrownBy(() -> dispatcher.execute(ActionType.CREATE_KNOWLEDGE_BASE, retry))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dispatcher.execute(ActionType.RETRY_DOCUMENT_PROCESSING, retry)).isSameAs(expected);
        verify(retryExecutor).execute(retry);
        verifyNoInteractions(createExecutor);
    }
}
