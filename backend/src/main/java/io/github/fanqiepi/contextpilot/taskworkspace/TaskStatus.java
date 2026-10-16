package io.github.fanqiepi.contextpilot.taskworkspace;

public enum TaskStatus {
    PLANNING, READY, WAITING_INPUT, RUNNING, PAUSED, FAILED, CANCELLED, COMPLETED, INSUFFICIENT_EVIDENCE;

    public boolean active() { return this == PLANNING || this == RUNNING; }
}
