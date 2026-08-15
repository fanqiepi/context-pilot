package io.github.fanqiepi.contextpilot.health;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-base-health-reports")
public class KnowledgeBaseHealthReportController {

    private final KnowledgeBaseHealthReportService reportService;

    public KnowledgeBaseHealthReportController(KnowledgeBaseHealthReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/{id}")
    public KnowledgeBaseHealthReportResponse get(@PathVariable UUID id) {
        return reportService.get(id);
    }
}
