package io.github.fanqiepi.contextpilot.document;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class PendingDocumentRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PendingDocumentRecoveryService.class);

    private final SourceDocumentMapper sourceDocumentMapper;
    private final DocumentProcessingCoordinator processingCoordinator;
    private final DocumentProcessingProperties properties;

    public PendingDocumentRecoveryService(
            SourceDocumentMapper sourceDocumentMapper,
            DocumentProcessingCoordinator processingCoordinator,
            DocumentProcessingProperties properties) {
        this.sourceDocumentMapper = sourceDocumentMapper;
        this.processingCoordinator = processingCoordinator;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverPendingDocuments();
    }

    @Scheduled(
            fixedDelayString = "${contextpilot.document.processing.recovery-interval-millis:60000}",
            initialDelayString = "${contextpilot.document.processing.recovery-interval-millis:60000}")
    public void recoverOnSchedule() {
        recoverPendingDocuments();
    }

    public int recoverPendingDocuments() {
        if (!processingCoordinator.isEnabled()) {
            return 0;
        }
        List<UUID> documentIds = sourceDocumentMapper.selectPendingIds(properties.getRecoveryBatchSize());
        int submitted = 0;
        for (UUID documentId : documentIds) {
            if (processingCoordinator.submit(documentId)) {
                submitted++;
            }
        }
        if (!documentIds.isEmpty()) {
            LOGGER.info(
                    "Pending document recovery scanned={}, submitted={}",
                    documentIds.size(),
                    submitted);
        }
        return submitted;
    }
}
