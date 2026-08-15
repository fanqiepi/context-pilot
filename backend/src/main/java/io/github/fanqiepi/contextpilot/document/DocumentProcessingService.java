package io.github.fanqiepi.contextpilot.document;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.processing.DocumentChunk;
import io.github.fanqiepi.contextpilot.document.processing.DocumentChunker;
import io.github.fanqiepi.contextpilot.document.processing.DocumentParsingException;
import io.github.fanqiepi.contextpilot.document.processing.ParsedDocumentPart;
import io.github.fanqiepi.contextpilot.document.processing.SpringAiDocumentParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentProcessingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentProcessingService.class);
    private static final int MAX_ERROR_SUMMARY_LENGTH = 1000;

    private final SourceDocumentMapper sourceDocumentMapper;
    private final StorageService storageService;
    private final SpringAiDocumentParser documentParser;
    private final DocumentChunker documentChunker;
    private final DocumentVectorIndex documentVectorIndex;
    private final EmbeddingIndexProperties embeddingIndexProperties;

    public DocumentProcessingService(
            SourceDocumentMapper sourceDocumentMapper,
            StorageService storageService,
            SpringAiDocumentParser documentParser,
            DocumentChunker documentChunker,
            DocumentVectorIndex documentVectorIndex,
            EmbeddingIndexProperties embeddingIndexProperties) {
        this.sourceDocumentMapper = sourceDocumentMapper;
        this.storageService = storageService;
        this.documentParser = documentParser;
        this.documentChunker = documentChunker;
        this.documentVectorIndex = documentVectorIndex;
        this.embeddingIndexProperties = embeddingIndexProperties;
    }

    public void process(UUID documentId) {
        if (sourceDocumentMapper.claimForProcessing(documentId) == 0) {
            return;
        }

        SourceDocumentEntity entity = sourceDocumentMapper.selectById(documentId);
        if (entity == null) {
            return;
        }

        try (InputStream content = storageService.open(entity.getStorageKey())) {
            List<ParsedDocumentPart> parts = documentParser.parse(
                    entity.getFileType(),
                    entity.getOriginalFilename(),
                    content);
            List<DocumentChunk> chunks = documentChunker.chunk(parts);
            documentVectorIndex.replace(entity, chunks);
            if (sourceDocumentMapper.markSucceeded(documentId, embeddingIndexProperties.currentProfile()) == 0) {
                documentVectorIndex.deleteByDocumentId(documentId);
            }
        } catch (Exception exception) {
            cleanupAfterFailure(documentId, exception);
            sourceDocumentMapper.markFailed(documentId, errorSummary(exception));
            LOGGER.warn("Document processing failed, documentId={}", documentId, exception);
        }
    }

    private void cleanupAfterFailure(UUID documentId, Exception original) {
        try {
            documentVectorIndex.deleteByDocumentId(documentId);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private String errorSummary(Exception exception) {
        String summary = exception instanceof DocumentParsingException
                ? exception.getMessage()
                : "Document processing failed: " + exception.getClass().getSimpleName();
        if (summary == null || summary.isBlank()) {
            summary = "Document processing failed";
        }
        return summary.length() <= MAX_ERROR_SUMMARY_LENGTH
                ? summary
                : summary.substring(0, MAX_ERROR_SUMMARY_LENGTH);
    }
}
