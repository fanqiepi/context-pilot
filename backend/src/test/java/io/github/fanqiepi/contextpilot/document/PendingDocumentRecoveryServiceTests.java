package io.github.fanqiepi.contextpilot.document;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PendingDocumentRecoveryServiceTests {

    @Mock
    private SourceDocumentMapper sourceDocumentMapper;

    @Mock
    private DocumentProcessingCoordinator processingCoordinator;

    private DocumentProcessingProperties properties;
    private PendingDocumentRecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        properties = new DocumentProcessingProperties();
        properties.setRecoveryBatchSize(2);
        recoveryService = new PendingDocumentRecoveryService(
                sourceDocumentMapper,
                processingCoordinator,
                properties);
    }

    @Test
    void skipsRecoveryWhenProcessingIsDisabled() {
        when(processingCoordinator.isEnabled()).thenReturn(false);

        assertThat(recoveryService.recoverPendingDocuments()).isZero();

        verifyNoInteractions(sourceDocumentMapper);
        verify(processingCoordinator, never()).submit(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void scansConfiguredBoundAndContinuesAfterQueueRejection() {
        UUID accepted = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        when(processingCoordinator.isEnabled()).thenReturn(true);
        when(sourceDocumentMapper.selectPendingIds(2)).thenReturn(List.of(accepted, rejected));
        when(processingCoordinator.submit(accepted)).thenReturn(true);
        when(processingCoordinator.submit(rejected)).thenReturn(false);

        assertThat(recoveryService.recoverPendingDocuments()).isEqualTo(1);

        verify(sourceDocumentMapper).selectPendingIds(2);
        verify(processingCoordinator).submit(accepted);
        verify(processingCoordinator).submit(rejected);
    }
}
