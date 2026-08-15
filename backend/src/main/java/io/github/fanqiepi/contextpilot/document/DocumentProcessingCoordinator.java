package io.github.fanqiepi.contextpilot.document;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    public void submitAfterCommit(UUID documentId) {
        if (!isEnabled()) {
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            submit(documentId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(documentId);
            }
        });
    }

    public boolean submit(UUID documentId) {
        if (!isEnabled()) {
            return false;
        }
        try {
            taskExecutor.execute(() -> processingService.process(documentId));
            return true;
        } catch (TaskRejectedException exception) {
            LOGGER.warn("Document processing queue rejected documentId={}", documentId);
            return false;
        }
    }
}
