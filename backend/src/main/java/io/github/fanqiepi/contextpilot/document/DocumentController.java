package io.github.fanqiepi.contextpilot.document;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "文档管理", description = "上传、查询和逻辑删除知识库文档")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(
            path = "/knowledge-bases/{knowledgeBaseId}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档")
    public ResponseEntity<DocumentResponse> upload(
            @PathVariable UUID knowledgeBaseId,
            @RequestPart("file") MultipartFile file) {
        DocumentResponse response = documentService.upload(knowledgeBaseId, file);
        return ResponseEntity.created(URI.create("/api/documents/" + response.id())).body(response);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    @Operation(summary = "查询知识库下的文档列表")
    public List<DocumentResponse> list(@PathVariable UUID knowledgeBaseId) {
        return documentService.list(knowledgeBaseId);
    }

    @GetMapping("/documents/{documentId}")
    @Operation(summary = "查询单个文档")
    public DocumentResponse get(@PathVariable UUID documentId) {
        return documentService.get(documentId);
    }

    @PostMapping("/documents/{documentId}/retry")
    @Operation(summary = "重试处理失败的文档")
    public ResponseEntity<DocumentResponse> retry(@PathVariable UUID documentId) {
        return ResponseEntity.accepted().body(documentService.retry(documentId));
    }

    @DeleteMapping("/documents/{documentId}")
    @Operation(summary = "逻辑删除文档")
    public ResponseEntity<Void> delete(@PathVariable UUID documentId) {
        documentService.delete(documentId);
        return ResponseEntity.noContent().build();
    }
}
