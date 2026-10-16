package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.UUID;

/** Capability-specific execution seam; not a tool registry or general workflow interface. */
public interface UpgradeRunWorker {
    void execute(UUID runId);
}
