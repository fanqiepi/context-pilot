package io.github.fanqiepi.contextpilot.upgraderesearch;

public enum UpgradeRunStatus {
    QUEUED, RUNNING, SUCCEEDED, PARTIAL, REFUSED, FAILED, CANCELLED;
    public boolean terminal() { return this != QUEUED && this != RUNNING; }
}
