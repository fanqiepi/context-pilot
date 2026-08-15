package io.github.fanqiepi.contextpilot.action;

public record ActionExecutionResult(String resultSummary) {

    public ActionExecutionResult {
        if (resultSummary == null || resultSummary.isBlank()) {
            throw new IllegalArgumentException("Action result summary must not be blank");
        }
        resultSummary = resultSummary.strip();
    }
}
