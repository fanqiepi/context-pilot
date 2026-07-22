package io.github.fanqiepi.contextpilot.document;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(
            path = "/knowledge-bases/{knowledgeBaseId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @PathVariable UUID knowledgeBaseId,
            @RequestPart("file") MultipartFile file) {
        DocumentResponse response = documentService.upload(knowledgeBaseId, file);
        return ResponseEntity.created(URI.create("/api/documents/" + response.id())).body(response);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public List<DocumentResponse> list(@PathVariable UUID knowledgeBaseId) {
        return documentService.list(knowledgeBaseId);
    }

    @GetMapping("/documents/{documentId}")
    public DocumentResponse get(@PathVariable UUID documentId) {
        return documentService.get(documentId);
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        documentService.delete(documentId);
        return ResponseEntity.noContent().build();
    }
}
