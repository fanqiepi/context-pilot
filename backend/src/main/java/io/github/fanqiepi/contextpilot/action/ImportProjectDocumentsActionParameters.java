package io.github.fanqiepi.contextpilot.action;

import java.util.UUID;
public record ImportProjectDocumentsActionParameters(UUID previewId, UUID knowledgeBaseId) implements ActionParameters {
    public ImportProjectDocumentsActionParameters {
        if(previewId==null || knowledgeBaseId==null) throw new IllegalArgumentException("Project import IDs are required");
    }
    @Override public ActionType actionType() { return ActionType.IMPORT_PROJECT_DOCUMENTS; }
}
