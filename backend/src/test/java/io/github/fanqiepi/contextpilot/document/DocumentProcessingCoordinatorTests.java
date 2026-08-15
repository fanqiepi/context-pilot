package io.github.fanqiepi.contextpilot.document;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DocumentProcessingCoordinatorTests {

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private DocumentProcessingService processingService;

    private DocumentProcessingProperties properties;
    private DocumentProcessingCoordinator coordinator;

    @BeforeEach
    void setUp() {
        properties = new DocumentProcessingProperties();
        properties.setEnabled(true);
        coordinator = new DocumentProcessingCoordinator(properties, taskExecutor, processingService);
    }

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void dispatchesOnlyAfterActiveTransactionCommits() {
        UUID documentId = UUID.randomUUID();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        coordinator.submitAfterCommit(documentId);

        verifyNoInteractions(taskExecutor);
        TransactionSynchronization synchronization = TransactionSynchronizationManager
                .getSynchronizations()
                .getFirst();
        synchronization.afterCommit();
        verify(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void doesNotDispatchWhenActiveTransactionRollsBack() {
        UUID documentId = UUID.randomUUID();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        coordinator.submitAfterCommit(documentId);
        TransactionSynchronizationManager.getSynchronizations().getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(taskExecutor);
    }

    @Test
    void dispatchesImmediatelyOutsideTransaction() {
        coordinator.submitAfterCommit(UUID.randomUUID());

        verify(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void keepsPendingWorkRecoverableWhenExecutorRejectsIt() {
        doThrow(new TaskRejectedException("queue full"))
                .when(taskExecutor)
                .execute(any(Runnable.class));

        boolean submitted = coordinator.submit(UUID.randomUUID());

        assertThat(submitted).isFalse();
        verifyNoInteractions(processingService);
    }

    @Test
    void doesNotRegisterOrDispatchWhenProcessingIsDisabled() {
        properties.setEnabled(false);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        coordinator.submitAfterCommit(UUID.randomUUID());

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(taskExecutor, never()).execute(any(Runnable.class));
    }
}
