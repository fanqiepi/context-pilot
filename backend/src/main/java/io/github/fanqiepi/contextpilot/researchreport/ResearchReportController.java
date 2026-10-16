package io.github.fanqiepi.contextpilot.researchreport;

import java.util.*;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.upgraderesearch.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/research-reports")
public class ResearchReportController {
    private final ResearchReportMapper reports;
    private final CurrentSubjectProvider subjects;
    private final KnowledgeBaseService knowledgeBases;
    private final UpgradeRunMapper runs;
    private final UpgradeRunStateService state;
    public ResearchReportController(ResearchReportMapper reports,CurrentSubjectProvider subjects,KnowledgeBaseService knowledgeBases,UpgradeRunMapper runs,UpgradeRunStateService state){
        this.reports=reports;this.subjects=subjects;this.knowledgeBases=knowledgeBases;this.runs=runs;this.state=state;
    }
    @GetMapping public List<ResearchReport> list(@RequestParam UUID knowledgeBaseId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){
        UpgradeQueryService.page(page,size);knowledgeBases.get(knowledgeBaseId);return reports.list(subjects.currentSubject().id(),knowledgeBaseId,size,(long)(page-1)*size);
    }
    @GetMapping("/{id}") public Object get(@PathVariable UUID id){
        var report=reports.get(id,subjects.currentSubject().id());if(report==null)throw new ResourceNotFoundException("REPORT_NOT_FOUND","Research report unavailable");
        var run=runs.owned(subjects.currentSubject().id(),report.runId());
        return Map.of("report",report,"sourcesCurrent",run!=null && state.sourcesUnchanged(run));
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id){reports.delete(id,subjects.currentSubject().id());return ResponseEntity.noContent().build();}
}
