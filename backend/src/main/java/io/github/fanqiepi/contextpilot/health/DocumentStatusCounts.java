package io.github.fanqiepi.contextpilot.health;

public record DocumentStatusCounts(
        long total,
        long pending,
        long processing,
        long succeeded,
        long failed,
        long deleting) {

    public DocumentStatusCounts {
        if (total < 0 || pending < 0 || processing < 0 || succeeded < 0 || failed < 0 || deleting < 0) {
            throw new IllegalArgumentException("Document status counts must not be negative");
        }
        if (pending + processing + succeeded + failed + deleting != total) {
            throw new IllegalArgumentException("Document status counts must add up to the total");
        }
    }

    public long inProgress() {
        return pending + processing + deleting;
    }
}
