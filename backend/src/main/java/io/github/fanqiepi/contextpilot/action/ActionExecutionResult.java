package io.github.fanqiepi.contextpilot.action;

import java.util.UUID;

public record ActionExecutionResult(String resultSummary, UUID resultResourceId) {

    public ActionExecutionResult(String resultSummary) {
        this(resultSummary, null);
    }

    public ActionExecutionResult {
        if (resultSummary == null || resultSummary.isBlank()) {
            throw new IllegalArgumentException("Action result summary must not be blank");
        }
        resultSummary = resultSummary.strip();
    }
}
