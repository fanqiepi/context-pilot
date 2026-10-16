package io.github.fanqiepi.contextpilot.researchreport;

import java.time.OffsetDateTime;
import io.github.fanqiepi.contextpilot.action.*;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import io.github.fanqiepi.contextpilot.upgraderesearch.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ResearchReportWriter {
    private final UpgradeRunMapper runs;
    private final UpgradeRunStateService state;
    private final UpgradeExecutionMapper evidence;
    private final ResearchReportMapper reports;
    private final CurrentSubjectProvider subjects;
    private final ActionRequestMapper actions;
    private final tools.jackson.databind.ObjectMapper json;
    public ResearchReportWriter(UpgradeRunMapper runs,UpgradeRunStateService state,UpgradeExecutionMapper evidence,
            ResearchReportMapper reports,CurrentSubjectProvider subjects,ActionRequestMapper actions,tools.jackson.databind.ObjectMapper json){
        this.runs=runs;this.state=state;this.evidence=evidence;this.reports=reports;this.subjects=subjects;this.actions=actions;this.json=json;
    }
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=ConflictException.class)
    public ActionExecutionResult save(SaveResearchReportActionParameters parameters,ActionExecutionContext context){
        var owner=subjects.currentSubject().id();
        var action=actions.selectById(context.actionRequestId());
        if(action==null || action.getStatus()!=ActionRequestStatus.EXECUTING || action.getActionType()!=ActionType.SAVE_RESEARCH_REPORT
                || !parameters.runId().equals(action.getUpgradeRunId()))throw conflict("REPORT_CONFIRMATION_REQUIRED");
        var run=runs.lockOwned(owner,parameters.runId());
        if(run==null || !(run.getStatus()==UpgradeRunStatus.SUCCEEDED || run.getStatus()==UpgradeRunStatus.PARTIAL)
                || !parameters.draftHash().equals(run.getDraftHash()))throw conflict("REPORT_DRAFT_UNAVAILABLE");
        var prior=reports.byRun(run.getId(),owner);
        if(prior!=null){if(prior.deleted()!=0)throw conflict("REPORT_ALREADY_DELETED");return new ActionExecutionResult("研究报告已保存",prior.id());}
        var snapshots=json.readTree(run.getDocumentSnapshots());
        for(var node:snapshots)if(runs.lockDocument(java.util.UUID.fromString(node.path("id").asText()))==null)throw conflict("REPORT_SOURCE_CHANGED");
        if(!state.sourcesUnchanged(run))throw conflict("REPORT_SOURCE_CHANGED");
        var report=new ResearchReport(java.util.UUID.randomUUID(),owner,run.getKnowledgeBaseId(),run.getId(),context.actionRequestId(),
                parameters.title(),run.getDraft(),run.getDraftHash(),json.writeValueAsString(evidence.sources(run.getId()).stream().map(UpgradeEvidence::toolData).toList()),
                run.getGaps(),OffsetDateTime.now(java.time.ZoneOffset.UTC),0);
        reports.insert(report);
        return new ActionExecutionResult("研究报告已保存",report.id());
    }
    private static ConflictException conflict(String code){return new ConflictException(code,"Research report cannot be saved in its current state");}
}
