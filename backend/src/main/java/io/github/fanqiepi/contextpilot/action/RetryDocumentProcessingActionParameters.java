package io.github.fanqiepi.contextpilot.action;

import java.util.UUID;

import io.github.fanqiepi.contextpilot.document.DocumentStatus;

public record RetryDocumentProcessingActionParameters(
        UUID documentId,
        String originalFilenameSnapshot,
        DocumentStatus observedDocumentStatus,
        UUID healthReportId,
        UUID healthIssueId) implements ActionParameters {

    public RetryDocumentProcessingActionParameters {
        documentId = DocumentActionParameterValidation.requireId(
                documentId, "INVALID_ACTION_DOCUMENT_ID", "Document id");
        originalFilenameSnapshot = DocumentActionParameterValidation.filename(originalFilenameSnapshot);
        observedDocumentStatus = DocumentActionParameterValidation.requireStatus(
                observedDocumentStatus, DocumentStatus.FAILED, ActionType.RETRY_DOCUMENT_PROCESSING);
        healthReportId = DocumentActionParameterValidation.requireId(
                healthReportId, "INVALID_ACTION_HEALTH_REPORT_ID", "Health report id");
        healthIssueId = DocumentActionParameterValidation.requireId(
                healthIssueId, "INVALID_ACTION_HEALTH_ISSUE_ID", "Health issue id");
    }

    @Override
    public ActionType actionType() {
        return ActionType.RETRY_DOCUMENT_PROCESSING;
    }
}
