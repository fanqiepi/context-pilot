package io.github.fanqiepi.contextpilot.knowledgebase;

import java.net.URI;
import java.util.List;
import java.util.UUID;

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
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(@Valid @RequestBody KnowledgeBaseCreateRequest request) {
        KnowledgeBaseResponse response = knowledgeBaseService.create(request);
        return ResponseEntity.created(URI.create("/api/knowledge-bases/" + response.id())).body(response);
    }

    @GetMapping
    public List<KnowledgeBaseResponse> list() {
        return knowledgeBaseService.list();
    }

    @GetMapping("/{id}")
    public KnowledgeBaseResponse get(@PathVariable UUID id) {
        return knowledgeBaseService.get(id);
    }

    @PatchMapping("/{id}")
    public KnowledgeBaseResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody KnowledgeBaseUpdateRequest request) {
        return knowledgeBaseService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        knowledgeBaseService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
