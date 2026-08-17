package io.github.fanqiepi.contextpilot.research;

public enum ResearchExecutionStatus {
    PLANNING,
    EXECUTING,
    SYNTHESIZING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == SUCCEEDED || this == PARTIAL || this == FAILED || this == CANCELLED;
    }
}
