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

    public DocumentService(
            SourceDocumentMapper sourceDocumentMapper,
            KnowledgeBaseService knowledgeBaseService,
            StorageService storageService,
            StorageProperties storageProperties,
            TransactionTemplate transactionTemplate) {
        this.sourceDocumentMapper = sourceDocumentMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.storageService = storageService;
        this.storageProperties = storageProperties;
        this.transactionTemplate = transactionTemplate;
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
        return DocumentResponse.from(entity);
    }

    public List<DocumentResponse> list(UUID knowledgeBaseId) {
        knowledgeBaseService.get(knowledgeBaseId);
        return sourceDocumentMapper.selectList(
                        Wrappers.<SourceDocumentEntity>lambdaQuery()
                                .eq(SourceDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                                .orderByDesc(SourceDocumentEntity::getCreatedAt)
                                .orderByDesc(SourceDocumentEntity::getId))
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }

    public DocumentResponse get(UUID documentId) {
        return DocumentResponse.from(requireEntity(documentId));
    }

    public void delete(UUID documentId) {
        SourceDocumentEntity entity = requireEntity(documentId);
        entity.setStatus(DocumentStatus.DELETING);
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        transactionTemplate.executeWithoutResult(status -> {
            if (sourceDocumentMapper.updateById(entity) == 0) {
                throw notFound(documentId);
            }
        });

        try {
            storageService.delete(entity.getStorageKey());
        } catch (StorageException exception) {
            throw new InternalServiceException(
                    "DOCUMENT_STORAGE_DELETE_FAILED",
                    "Document file could not be deleted",
                    exception);
        }

        transactionTemplate.executeWithoutResult(status -> sourceDocumentMapper.deleteById(documentId));
    }

    private SourceDocumentEntity requireEntity(UUID documentId) {
        SourceDocumentEntity entity = sourceDocumentMapper.selectById(documentId);
        if (entity == null) {
            throw notFound(documentId);
        }
        return entity;
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
}
