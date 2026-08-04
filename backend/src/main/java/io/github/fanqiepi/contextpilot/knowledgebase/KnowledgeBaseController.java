package io.github.fanqiepi.contextpilot.knowledgebase;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
@Tag(name = "知识库管理", description = "创建、查询、更新和逻辑删除知识库")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    @Operation(summary = "创建知识库")
    public ResponseEntity<KnowledgeBaseResponse> create(@Valid @RequestBody KnowledgeBaseCreateRequest request) {
        KnowledgeBaseResponse response = knowledgeBaseService.create(request);
        return ResponseEntity.created(URI.create("/api/knowledge-bases/" + response.id())).body(response);
    }

    @GetMapping
    @Operation(summary = "查询知识库列表")
    public List<KnowledgeBaseResponse> list() {
        return knowledgeBaseService.list();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询单个知识库")
    public KnowledgeBaseResponse get(@PathVariable UUID id) {
        return knowledgeBaseService.get(id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "更新知识库")
    public KnowledgeBaseResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody KnowledgeBaseUpdateRequest request) {
        return knowledgeBaseService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "逻辑删除知识库")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        knowledgeBaseService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
