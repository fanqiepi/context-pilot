package io.github.fanqiepi.contextpilot.health;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-base-health-reports")
public class KnowledgeBaseHealthReportController {

    private final KnowledgeBaseHealthReportService reportService;
    private final HealthReportActionProposalService actionProposalService;

    public KnowledgeBaseHealthReportController(
            KnowledgeBaseHealthReportService reportService,
            HealthReportActionProposalService actionProposalService) {
        this.reportService = reportService;
        this.actionProposalService = actionProposalService;
    }

    @GetMapping("/{id}")
    public KnowledgeBaseHealthReportResponse get(@PathVariable UUID id) {
        return reportService.get(id);
    }

    @PostMapping("/{reportId}/issues/{issueId}/action-request")
    @Operation(summary = "从健康报告明细生成或恢复单文档维护提案")
    public HealthReportActionProposalResponse proposeAction(
            @PathVariable UUID reportId,
            @PathVariable UUID issueId,
            HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String traceId = requestId == null ? UUID.randomUUID().toString() : requestId.toString();
        return actionProposalService.propose(reportId, issueId, traceId);
    }
}
