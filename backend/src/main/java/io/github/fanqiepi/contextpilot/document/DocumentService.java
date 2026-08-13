package io.github.fanqiepi.contextpilot.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.common.PayloadTooLargeException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

    private static final int PDF_HEADER_LENGTH = 5;

    private final SourceDocumentMapper sourceDocumentMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final StorageService storageService;
    private final StorageProperties storageProperties;
    private final TransactionTemplate transactionTemplate;
    private final DocumentProcessingCoordinator processingCoordinator;
    private final DocumentVectorIndex documentVectorIndex;
    private final EmbeddingIndexProperties embeddingIndexProperties;

    public DocumentService(
            SourceDocumentMapper sourceDocumentMapper,
            KnowledgeBaseService knowledgeBaseService,
            StorageService storageService,
            StorageProperties storageProperties,
            TransactionTemplate transactionTemplate,
            DocumentProcessingCoordinator processingCoordinator,
            DocumentVectorIndex documentVectorIndex,
            EmbeddingIndexProperties embeddingIndexProperties) {
        this.sourceDocumentMapper = sourceDocumentMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.storageService = storageService;
        this.storageProperties = storageProperties;
        this.transactionTemplate = transactionTemplate;
        this.processingCoordinator = processingCoordinator;
        this.documentVectorIndex = documentVectorIndex;
        this.embeddingIndexProperties = embeddingIndexProperties;
    }

    public DocumentResponse upload(UUID knowledgeBaseId, MultipartFile file) {
        knowledgeBaseService.get(knowledgeBaseId);
        String filename = validateFilename(file.getOriginalFilename());
        validateSize(file);
        DocumentFileType fileType = resolveFileType(filename);
        validateContent(file, fileType);

        UUID documentId = UUID.randomUUID();
        String storageKey = storageKey(knowledgeBaseId, documentId, fileType);
        StoredObject storedObject = store(storageKey, file);
        if (storedObject.sizeBytes() <= 0) {
            BadRequestException exception = invalidFile("Uploaded file must not be empty");
            compensateStoredFile(storageKey, exception);
            throw exception;
        }
        if (storedObject.sizeBytes() > storageProperties.getMaxFileSize().toBytes()) {
            PayloadTooLargeException exception = new PayloadTooLargeException(
                    "DOCUMENT_FILE_TOO_LARGE",
                    "Uploaded file exceeds the configured size limit");
            compensateStoredFile(storageKey, exception);
            throw exception;
        }

        SourceDocumentEntity entity = new SourceDocumentEntity();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.setId(documentId);
        entity.setKnowledgeBaseId(knowledgeBaseId);
        entity.setOriginalFilename(filename);
        entity.setFileType(fileType);
        entity.setMediaType(fileType.getMediaType());
        entity.setSizeBytes(storedObject.sizeBytes());
        entity.setStorageKey(storedObject.storageKey());
        entity.setSha256(storedObject.sha256());
        entity.setStatus(DocumentStatus.PENDING);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (sourceDocumentMapper.insert(entity) == 0) {
                    throw new IllegalStateException("Document metadata insert did not affect a row");
                }
            });
        } catch (RuntimeException exception) {
            compensateStoredFile(storageKey, exception);
            throw new InternalServiceException(
                    "DOCUMENT_METADATA_SAVE_FAILED",
                    "Document metadata could not be saved",
                    exception);
        }
        processingCoordinator.submit(documentId);
        return response(entity);
    }

    public List<DocumentResponse> list(UUID knowledgeBaseId) {
        knowledgeBaseService.get(knowledgeBaseId);
        return sourceDocumentMapper.selectList(
                        Wrappers.<SourceDocumentEntity>lambdaQuery()
                                .eq(SourceDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                                .orderByDesc(SourceDocumentEntity::getCreatedAt)
                                .orderByDesc(SourceDocumentEntity::getId))
                .stream()
                .map(this::response)
                .toList();
    }

    public DocumentResponse get(UUID documentId) {
        return response(requireEntity(documentId));
    }

    public DocumentResponse retry(UUID documentId) {
        if (!processingCoordinator.isEnabled()) {
            throw new ConflictException(
                    "DOCUMENT_PROCESSING_DISABLED",
                    "Document processing is not enabled");
        }
        SourceDocumentEntity current = requireEntity(documentId);
        if (current.getStatus() != DocumentStatus.FAILED) {
            throw retryNotAllowed(documentId, current);
        }
        if (!processingCoordinator.canRetry(current.getProcessingAttempts())) {
            throw new ConflictException(
                    "DOCUMENT_RETRY_LIMIT_REACHED",
                    "Document " + documentId + " has reached the processing retry limit");
        }
        if (sourceDocumentMapper.prepareRetry(documentId) == 0) {
            SourceDocumentEntity entity = requireEntity(documentId);
            throw retryNotAllowed(documentId, entity);
        }
        SourceDocumentEntity entity = requireEntity(documentId);
        processingCoordinator.submit(documentId);
        return response(entity);
    }

    public DocumentResponse reindex(UUID documentId) {
        SourceDocumentEntity current = requireEntity(documentId);
        if (current.getStatus() != DocumentStatus.SUCCEEDED) {
            throw reindexNotAllowed(documentId, current);
        }
        EmbeddingIndexProfile currentProfile = embeddingIndexProperties.currentProfile();
        if (currentProfile.id().equals(current.getEmbeddingProfileId())) {
            throw reindexNotRequired(documentId);
        }
        if (!processingCoordinator.isEnabled()) {
            throw new ConflictException(
                    "DOCUMENT_PROCESSING_DISABLED",
                    "Document processing is not enabled");
        }
        if (!documentVectorIndex.isAvailable()) {
            throw new ConflictException(
                    "VECTOR_STORE_UNAVAILABLE",
                    "Document index cannot be rebuilt while vector storage is disabled");
        }
        if (sourceDocumentMapper.prepareReindex(documentId, currentProfile.id()) == 0) {
            SourceDocumentEntity latest = requireEntity(documentId);
            if (latest.getStatus() == DocumentStatus.SUCCEEDED
                    && currentProfile.id().equals(latest.getEmbeddingProfileId())) {
                throw reindexNotRequired(documentId);
            }
            throw reindexNotAllowed(documentId, latest);
        }
        SourceDocumentEntity pending = requireEntity(documentId);
        processingCoordinator.submit(documentId);
        return response(pending);
    }

    public void delete(UUID documentId) {
        SourceDocumentEntity entity = requireEntity(documentId);
        if (entity.getProcessingAttempts() > 0 && !documentVectorIndex.isAvailable()) {
            throw new InternalServiceException(
                    "DOCUMENT_DELETE_FAILED",
                    "Document index cannot be removed while vector storage is disabled",
                    new IllegalStateException("VectorStore bean is not available"));
        }
        if (sourceDocumentMapper.markDeleting(documentId) == 0) {
            throw notFound(documentId);
        }
        try {
            documentVectorIndex.deleteByDocumentId(documentId);
        } catch (RuntimeException exception) {
            throw new InternalServiceException(
                    "DOCUMENT_DELETE_FAILED",
                    "Document index could not be removed",
                    exception);
        }
        if (sourceDocumentMapper.deleteById(documentId) == 0) {
            throw notFound(documentId);
        }
    }

    private SourceDocumentEntity requireEntity(UUID documentId) {
        SourceDocumentEntity entity = sourceDocumentMapper.selectById(documentId);
        if (entity == null) {
            throw notFound(documentId);
        }
        return entity;
    }

    private DocumentResponse response(SourceDocumentEntity entity) {
        return DocumentResponse.from(entity, embeddingIndexProperties.currentProfile());
    }

    private String validateFilename(String originalFilename) {
        if (originalFilename == null) {
            throw invalidFile("Uploaded file must have a filename");
        }
        String normalized = originalFilename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.isEmpty() || normalized.length() > 255 || normalized.chars().anyMatch(value -> value < 32)) {
            throw invalidFile("Uploaded filename is invalid");
        }
        return normalized;
    }

    private void validateSize(MultipartFile file) {
        if (file.isEmpty() || file.getSize() <= 0) {
            throw invalidFile("Uploaded file must not be empty");
        }
        if (file.getSize() > storageProperties.getMaxFileSize().toBytes()) {
            throw new PayloadTooLargeException(
                    "DOCUMENT_FILE_TOO_LARGE",
                    "Uploaded file exceeds the configured size limit");
        }
    }

    private DocumentFileType resolveFileType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) {
            return DocumentFileType.TXT;
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return DocumentFileType.MARKDOWN;
        }
        if (lower.endsWith(".pdf")) {
            return DocumentFileType.PDF;
        }
        throw new BadRequestException(
                "UNSUPPORTED_DOCUMENT_TYPE",
                "Only TXT, Markdown, and PDF files are supported");
    }

    private void validateContent(MultipartFile file, DocumentFileType fileType) {
        if (fileType == DocumentFileType.PDF) {
            validatePdf(file);
        } else {
            validateUtf8Text(file);
        }
    }

    private void validatePdf(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(PDF_HEADER_LENGTH);
            if (header.length != PDF_HEADER_LENGTH
                    || header[0] != '%'
                    || header[1] != 'P'
                    || header[2] != 'D'
                    || header[3] != 'F'
                    || header[4] != '-') {
                throw invalidFile("PDF file header is invalid");
            }
        } catch (IOException exception) {
            throw fileReadFailure(exception);
        }
    }

    private void validateUtf8Text(MultipartFile file) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(file.getInputStream(), decoder)) {
            char[] buffer = new char[8192];
            while (reader.read(buffer) != -1) {
                // Reading the complete stream validates all encoded input.
            }
        } catch (CharacterCodingException exception) {
            throw invalidFile("TXT and Markdown files must use UTF-8 encoding");
        } catch (IOException exception) {
            throw fileReadFailure(exception);
        }
    }

    private StoredObject store(String storageKey, MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return storageService.store(storageKey, input);
        } catch (IOException | StorageException exception) {
            throw fileReadFailure(exception);
        }
    }

    private void compensateStoredFile(String storageKey, RuntimeException original) {
        try {
            storageService.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private String storageKey(UUID knowledgeBaseId, UUID documentId, DocumentFileType fileType) {
        return "knowledge-bases/" + knowledgeBaseId
                + "/documents/" + documentId
                + "/source." + fileType.getStorageExtension();
    }

    private BadRequestException invalidFile(String message) {
        return new BadRequestException("INVALID_DOCUMENT_FILE", message);
    }

    private InternalServiceException fileReadFailure(Exception cause) {
        return new InternalServiceException(
                "DOCUMENT_FILE_READ_FAILED",
                "Uploaded file could not be read",
                cause);
    }

    private ResourceNotFoundException notFound(UUID documentId) {
        return new ResourceNotFoundException(
                "DOCUMENT_NOT_FOUND",
                "Document " + documentId + " was not found");
    }

    private ConflictException retryNotAllowed(UUID documentId, SourceDocumentEntity entity) {
        return new ConflictException(
                "DOCUMENT_RETRY_NOT_ALLOWED",
                "Document " + documentId + " cannot be retried from status " + entity.getStatus());
    }

    private ConflictException reindexNotAllowed(UUID documentId, SourceDocumentEntity entity) {
        return new ConflictException(
                "DOCUMENT_REINDEX_NOT_ALLOWED",
                "Document " + documentId + " cannot be reindexed from status " + entity.getStatus());
    }

    private ConflictException reindexNotRequired(UUID documentId) {
        return new ConflictException(
                "DOCUMENT_REINDEX_NOT_REQUIRED",
                "Document " + documentId + " already uses the current embedding profile");
    }
}
