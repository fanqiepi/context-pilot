package io.github.fanqiepi.contextpilot.action;

import java.util.UUID;

public record SaveResearchReportActionParameters(UUID runId,String draftHash,String title) implements ActionParameters {
    public SaveResearchReportActionParameters {
        if(runId==null || draftHash==null || !draftHash.matches("[0-9a-f]{64}") || title==null || title.isBlank() || title.strip().length()>120)
            throw new IllegalArgumentException("Invalid report save parameters");
        title=title.strip();
    }
    @Override public ActionType actionType(){return ActionType.SAVE_RESEARCH_REPORT;}
}
