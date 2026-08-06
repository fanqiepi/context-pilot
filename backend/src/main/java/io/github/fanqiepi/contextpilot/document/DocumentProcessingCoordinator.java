package io.github.fanqiepi.contextpilot.document;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

@Component
public class DocumentProcessingCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentProcessingCoordinator.class);

    private final DocumentProcessingProperties properties;
    private final TaskExecutor taskExecutor;
    private final DocumentProcessingService processingService;

    public DocumentProcessingCoordinator(
            DocumentProcessingProperties properties,
            @Qualifier("documentTaskExecutor") TaskExecutor taskExecutor,
            DocumentProcessingService processingService) {
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.processingService = processingService;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public boolean canRetry(int processingAttempts) {
        return processingAttempts < properties.getMaxAttempts();
    }

    public void submit(UUID documentId) {
        if (!isEnabled()) {
            return;
        }
        try {
            taskExecutor.execute(() -> processingService.process(documentId));
        } catch (TaskRejectedException exception) {
            LOGGER.warn("Document processing queue rejected documentId={}", documentId);
            processingService.markSubmissionFailed(documentId);
        }
    }
}
