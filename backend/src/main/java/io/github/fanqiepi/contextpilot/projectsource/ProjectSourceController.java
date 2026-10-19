package io.github.fanqiepi.contextpilot.projectsource;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProjectSourceController {
    private final ProjectSourcePreviewService service;
    private final ProjectImportProposalService proposals;
    private final ProjectImportMapper imports;
    private final io.github.fanqiepi.contextpilot.document.DocumentService documents;
    private final io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider subjects;
    public ProjectSourceController(ProjectSourcePreviewService service,ProjectImportProposalService proposals,ProjectImportMapper imports,
            io.github.fanqiepi.contextpilot.document.DocumentService documents,io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider subjects) {
        this.service=service;this.proposals=proposals;this.imports=imports;this.documents=documents;this.subjects=subjects;
    }
    @GetMapping("/project-sources") public Object sources() {
        return Map.of("enabled",service.enabled(),"sources",java.util.List.of(Map.of("id",ProjectSourcePolicy.SOURCE_ID,
                "repository",ProjectSourcePolicy.REPOSITORY)),"maxFiles",5,"maxFileBytes",ProjectSourcePolicy.FILE_BYTES,
                "maxBatchBytes",ProjectSourcePolicy.BATCH_BYTES);
    }
    @PostMapping("/project-source-previews") @ResponseStatus(HttpStatus.ACCEPTED)
    public ProjectSourcePreviewService.PreviewResponse create(@RequestBody ProjectSourcePreviewService.CreateRequest request) { return service.create(request); }
    @GetMapping("/project-source-previews/{id}")
    public ProjectSourcePreviewService.PreviewResponse get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/project-source-previews/{id}/import-proposals")
    public io.github.fanqiepi.contextpilot.action.ActionRequestResponse propose(@PathVariable UUID id,@RequestBody ProjectImportProposalService.Request request) {
        return proposals.propose(id,request);
    }
    @GetMapping("/project-imports/{actionId}/documents") public Object results(@PathVariable UUID actionId) {
        var request=imports.forAction(actionId);
        if(request==null || !subjects.currentSubject().id().equals(request.ownerId()))
            throw new io.github.fanqiepi.contextpilot.common.ResourceNotFoundException("SOURCE_IMPORT_NOT_FOUND","导入记录不存在");
        return imports.results(actionId);
    }
    @GetMapping("/documents/{id}/origin") public Object origin(@PathVariable UUID id) {
        documents.get(id);
        return java.util.Optional.ofNullable(imports.origin(id));
    }
}
