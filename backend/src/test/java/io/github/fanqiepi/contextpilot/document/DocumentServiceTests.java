package io.github.fanqiepi.contextpilot.document;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.common.PayloadTooLargeException;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseResponse;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTests {

    private static final String SHA256 = "0".repeat(64);

    @Mock
    private SourceDocumentMapper sourceDocumentMapper;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private StorageService storageService;

    @Mock
    private TransactionTemplate transactionTemplate;

    private StorageProperties storageProperties;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        storageProperties.setMaxFileSize(DataSize.ofMegabytes(20));
        documentService = new DocumentService(
                sourceDocumentMapper,
                knowledgeBaseService,
                storageService,
                storageProperties,
                transactionTemplate);
    }

    @Test
    void uploadsUtf8TextAsPendingDocument() {
        enableTransactions();
        UUID knowledgeBaseId = UUID.randomUUID();
        byte[] content = "hello knowledge base".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", content);
        when(knowledgeBaseService.get(knowledgeBaseId)).thenReturn(knowledgeBase(knowledgeBaseId));
        when(storageService.store(any(), any())).thenAnswer(invocation ->
                new StoredObject(invocation.getArgument(0), content.length, SHA256));
        when(sourceDocumentMapper.insert(any(SourceDocumentEntity.class))).thenReturn(1);

        DocumentResponse response = documentService.upload(knowledgeBaseId, file);

        ArgumentCaptor<SourceDocumentEntity> captor = ArgumentCaptor.forClass(SourceDocumentEntity.class);
        verify(sourceDocumentMapper).insert(captor.capture());
        SourceDocumentEntity entity = captor.getValue();
        assertThat(entity.getOriginalFilename()).isEqualTo("notes.txt");
        assertThat(entity.getFileType()).isEqualTo(DocumentFileType.TXT);
        assertThat(entity.getStatus()).isEqualTo(DocumentStatus.PENDING);
        assertThat(entity.getStorageKey()).startsWith("knowledge-bases/" + knowledgeBaseId + "/documents/");
        assertThat(response.status()).isEqualTo(DocumentStatus.PENDING);
    }

    @Test
    void removesStoredFileWhenMetadataInsertFails() {
        enableTransactions();
        UUID knowledgeBaseId = UUID.randomUUID();
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/markdown", content);
        when(knowledgeBaseService.get(knowledgeBaseId)).thenReturn(knowledgeBase(knowledgeBaseId));
        when(storageService.store(any(), any())).thenAnswer(invocation ->
                new StoredObject(invocation.getArgument(0), content.length, SHA256));
        when(sourceDocumentMapper.insert(any(SourceDocumentEntity.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> documentService.upload(knowledgeBaseId, file))
                .isInstanceOf(InternalServiceException.class)
                .hasMessageContaining("metadata");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storageService).delete(key.capture());
        assertThat(key.getValue()).startsWith("knowledge-bases/" + knowledgeBaseId);
    }

    @Test
    void rejectsUnsupportedFileBeforeStorage() {
        UUID knowledgeBaseId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "notes.docx", null, new byte[] {1});
        when(knowledgeBaseService.get(knowledgeBaseId)).thenReturn(knowledgeBase(knowledgeBaseId));

        assertThatThrownBy(() -> documentService.upload(knowledgeBaseId, file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Only TXT");
        verifyNoInteractions(storageService);
    }

    @Test
    void rejectsInvalidPdfHeader() {
        UUID knowledgeBaseId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.pdf",
                "application/pdf",
                "not a pdf".getBytes(StandardCharsets.UTF_8));
        when(knowledgeBaseService.get(knowledgeBaseId)).thenReturn(knowledgeBase(knowledgeBaseId));

        assertThatThrownBy(() -> documentService.upload(knowledgeBaseId, file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("PDF file header");
        verifyNoInteractions(storageService);
    }

    @Test
    void rejectsInvalidUtf8Text() {
        UUID knowledgeBaseId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                new byte[] {(byte) 0xc3, 0x28});
        when(knowledgeBaseService.get(knowledgeBaseId)).thenReturn(knowledgeBase(knowledgeBaseId));

        assertThatThrownBy(() -> documentService.upload(knowledgeBaseId, file))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("UTF-8");
        verifyNoInteractions(storageService);
    }

    @Test
    void rejectsFileOverConfiguredLimit() {
        storageProperties.setMaxFileSize(DataSize.ofBytes(4));
        UUID knowledgeBaseId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));
        when(knowledgeBaseService.get(knowledgeBaseId)).thenReturn(knowledgeBase(knowledgeBaseId));

        assertThatThrownBy(() -> documentService.upload(knowledgeBaseId, file))
                .isInstanceOf(PayloadTooLargeException.class);
        verifyNoInteractions(storageService);
    }

    @Test
    void marksDocumentDeletingBeforeRemovingFileAndMetadata() {
        enableTransactions();
        UUID documentId = UUID.randomUUID();
        SourceDocumentEntity entity = document(documentId);
        when(sourceDocumentMapper.selectById(documentId)).thenReturn(entity);
        when(sourceDocumentMapper.updateById(entity)).thenReturn(1);

        documentService.delete(documentId);

        assertThat(entity.getStatus()).isEqualTo(DocumentStatus.DELETING);
        verify(storageService).delete(entity.getStorageKey());
        verify(sourceDocumentMapper).deleteById(documentId);
    }

    private void enableTransactions() {
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private KnowledgeBaseResponse knowledgeBase(UUID id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new KnowledgeBaseResponse(id, "Notes", null, KnowledgeBaseStatus.ACTIVE, now, now);
    }

    private SourceDocumentEntity document(UUID id) {
        OffsetDateTime now = OffsetDateTime.now();
        SourceDocumentEntity entity = new SourceDocumentEntity();
        entity.setId(id);
        entity.setKnowledgeBaseId(UUID.randomUUID());
        entity.setOriginalFilename("notes.txt");
        entity.setFileType(DocumentFileType.TXT);
        entity.setMediaType("text/plain");
        entity.setSizeBytes(5);
        entity.setStorageKey("documents/" + id + "/source.txt");
        entity.setSha256(SHA256);
        entity.setStatus(DocumentStatus.PENDING);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }
}
