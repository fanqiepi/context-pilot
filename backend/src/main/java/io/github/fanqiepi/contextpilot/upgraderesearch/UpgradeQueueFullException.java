package io.github.fanqiepi.contextpilot.upgraderesearch;

public class UpgradeQueueFullException extends RuntimeException {
    public UpgradeQueueFullException() {super("Upgrade research queue is full");}
}
