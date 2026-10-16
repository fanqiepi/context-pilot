package io.github.fanqiepi.contextpilot.action;

import io.github.fanqiepi.contextpilot.researchreport.ResearchReportWriter;
import org.springframework.stereotype.Component;

@Component
public class SaveResearchReportActionExecutor {
    private final ResearchReportWriter writer;
    public SaveResearchReportActionExecutor(ResearchReportWriter writer){this.writer=writer;}
    public ActionExecutionResult execute(SaveResearchReportActionParameters parameters,ActionExecutionContext context){return writer.save(parameters,context);}
}
