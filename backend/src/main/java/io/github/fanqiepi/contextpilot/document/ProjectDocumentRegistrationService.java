package io.github.fanqiepi.contextpilot.document;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Internal registration seam. Caller validates frozen bytes and owns the batch transaction. */
@Service
public class ProjectDocumentRegistrationService {
    private final SourceDocumentMapper documents;
    private final DocumentProcessingCoordinator coordinator;
    public ProjectDocumentRegistrationService(SourceDocumentMapper documents,DocumentProcessingCoordinator coordinator) {
        this.documents=documents;this.coordinator=coordinator;
    }
    public void register(UUID id,UUID base,String path,String mediaType,int size,String storageKey,String hash) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Import requires a transaction");
        var value=new SourceDocumentEntity();var now=OffsetDateTime.now(ZoneOffset.UTC);
        value.setId(id);value.setKnowledgeBaseId(base);value.setOriginalFilename(path.substring(path.lastIndexOf('/')+1));
        value.setFileType("text/plain".equals(mediaType)?DocumentFileType.TXT:DocumentFileType.MARKDOWN);
        value.setMediaType(mediaType);value.setSizeBytes(size);value.setStorageKey(storageKey);value.setSha256(hash);
        value.setStatus(DocumentStatus.PENDING);value.setCreatedAt(now);value.setUpdatedAt(now);
        if(documents.insert(value)!=1)throw new IllegalStateException("Document registration failed");
        coordinator.submitAfterCommit(id);
    }
}
