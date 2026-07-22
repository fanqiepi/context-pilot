package io.github.fanqiepi.contextpilot.document;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ApiExceptionHandler;
import io.github.fanqiepi.contextpilot.common.PayloadTooLargeException;
import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DocumentControllerTests {

    @Mock
    private DocumentService documentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DocumentController(documentService))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void uploadsDocument() throws Exception {
        UUID knowledgeBaseId = UUID.randomUUID();
        DocumentResponse response = response(UUID.randomUUID(), knowledgeBaseId);
        when(documentService.upload(any(), any())).thenReturn(response);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/knowledge-bases/{id}/documents", knowledgeBaseId).file(file))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/documents/" + response.id()))
                .andExpect(jsonPath("$.originalFilename").value("notes.txt"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void listsDocuments() throws Exception {
        UUID knowledgeBaseId = UUID.randomUUID();
        when(documentService.list(knowledgeBaseId)).thenReturn(List.of(response(UUID.randomUUID(), knowledgeBaseId)));

        mockMvc.perform(get("/api/knowledge-bases/{id}/documents", knowledgeBaseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].originalFilename").value("notes.txt"));
    }

    @Test
    void deletesDocument() throws Exception {
        UUID documentId = UUID.randomUUID();

        mockMvc.perform(delete("/api/documents/{id}", documentId))
                .andExpect(status().isNoContent());

        verify(documentService).delete(documentId);
    }

    @Test
    void returnsPayloadTooLarge() throws Exception {
        UUID knowledgeBaseId = UUID.randomUUID();
        when(documentService.upload(any(), any())).thenThrow(new PayloadTooLargeException(
                "DOCUMENT_FILE_TOO_LARGE",
                "Uploaded file exceeds the configured size limit"));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/knowledge-bases/{id}/documents", knowledgeBaseId).file(file))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("DOCUMENT_FILE_TOO_LARGE"));
    }

    private DocumentResponse response(UUID documentId, UUID knowledgeBaseId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new DocumentResponse(
                documentId,
                knowledgeBaseId,
                "notes.txt",
                DocumentFileType.TXT,
                "text/plain",
                5,
                "0".repeat(64),
                DocumentStatus.PENDING,
                null,
                now,
                now);
    }
}
