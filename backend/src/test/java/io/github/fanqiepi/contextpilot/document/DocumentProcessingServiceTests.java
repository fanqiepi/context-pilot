package io.github.fanqiepi.contextpilot.document;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.processing.DocumentChunk;
import io.github.fanqiepi.contextpilot.document.processing.DocumentChunker;
import io.github.fanqiepi.contextpilot.document.processing.DocumentParsingException;
import io.github.fanqiepi.contextpilot.document.processing.ParsedDocumentPart;
import io.github.fanqiepi.contextpilot.document.processing.SpringAiDocumentParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentProcessingServiceTests {

    @Mock
    private SourceDocumentMapper sourceDocumentMapper;

    @Mock
    private StorageService storageService;

    @Mock
    private SpringAiDocumentParser documentParser;

    @Mock
    private DocumentChunker documentChunker;

    @Mock
    private DocumentVectorIndex documentVectorIndex;

    private DocumentProcessingService processingService;

    @BeforeEach
    void setUp() {
        processingService = new DocumentProcessingService(
                sourceDocumentMapper,
                storageService,
                documentParser,
                documentChunker,
                documentVectorIndex);
    }

    @Test
    void parsesChunksIndexesAndMarksDocumentSucceeded() {
        UUID documentId = UUID.randomUUID();
        SourceDocumentEntity entity = document(documentId);
        ParsedDocumentPart part = new ParsedDocumentPart("knowledge content", Map.of());
        DocumentChunk chunk = new DocumentChunk(0, "knowledge content", Map.of());
        when(sourceDocumentMapper.claimForProcessing(documentId)).thenReturn(1);
        when(sourceDocumentMapper.selectById(documentId)).thenReturn(entity);
        when(storageService.open(entity.getStorageKey())).thenReturn(new ByteArrayInputStream(
                "knowledge content".getBytes(StandardCharsets.UTF_8)));
        when(documentParser.parse(
                org.mockito.ArgumentMatchers.eq(entity.getFileType()),
                org.mockito.ArgumentMatchers.eq(entity.getOriginalFilename()),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(part));
        when(documentChunker.chunk(List.of(part))).thenReturn(List.of(chunk));
        when(sourceDocumentMapper.markSucceeded(documentId)).thenReturn(1);

        processingService.process(documentId);

        verify(documentVectorIndex).replace(entity, List.of(chunk));
        verify(sourceDocumentMapper).markSucceeded(documentId);
        verify(sourceDocumentMapper, never()).markFailed(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordsSafeFailureAndCleansPartialVectors() {
        UUID documentId = UUID.randomUUID();
        SourceDocumentEntity entity = document(documentId);
        when(sourceDocumentMapper.claimForProcessing(documentId)).thenReturn(1);
        when(sourceDocumentMapper.selectById(documentId)).thenReturn(entity);
        when(storageService.open(entity.getStorageKey())).thenReturn(new ByteArrayInputStream(new byte[] {1}));
        when(documentParser.parse(
                org.mockito.ArgumentMatchers.eq(entity.getFileType()),
                org.mockito.ArgumentMatchers.eq(entity.getOriginalFilename()),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DocumentParsingException("Document does not contain extractable text"));

        processingService.process(documentId);

        verify(documentVectorIndex).deleteByDocumentId(documentId);
        verify(sourceDocumentMapper).markFailed(
                org.mockito.ArgumentMatchers.eq(documentId),
                contains("extractable text"));
        verify(sourceDocumentMapper, never()).markSucceeded(documentId);
    }

    @Test
    void ignoresDocumentThatWasAlreadyClaimed() {
        UUID documentId = UUID.randomUUID();

        processingService.process(documentId);

        verify(sourceDocumentMapper).claimForProcessing(documentId);
        verifyNoInteractions(storageService, documentParser, documentChunker, documentVectorIndex);
    }

    private SourceDocumentEntity document(UUID id) {
        SourceDocumentEntity entity = new SourceDocumentEntity();
        entity.setId(id);
        entity.setKnowledgeBaseId(UUID.randomUUID());
        entity.setOriginalFilename("notes.txt");
        entity.setFileType(DocumentFileType.TXT);
        entity.setStorageKey("documents/" + id + "/source.txt");
        entity.setStatus(DocumentStatus.PROCESSING);
        return entity;
    }
}
