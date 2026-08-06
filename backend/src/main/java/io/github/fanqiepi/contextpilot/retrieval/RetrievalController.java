package io.github.fanqiepi.contextpilot.retrieval;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/search")
@Tag(name = "知识库检索", description = "在指定知识库中检索相关文档片段")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping
    @Operation(summary = "检索知识库文档片段")
    public List<RetrievalResultResponse> search(
            @PathVariable UUID knowledgeBaseId,
            @Valid @RequestBody RetrievalSearchRequest request) {
        return retrievalService.search(knowledgeBaseId, request);
    }
}
