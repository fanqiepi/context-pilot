package io.github.fanqiepi.contextpilot.chat;

import java.util.Objects;

record CapabilityRoute(
        CapabilityId capabilityId,
        String capabilityVersion,
        CapabilityMatchReason matchReason,
        String traceId) {

    CapabilityRoute {
        Objects.requireNonNull(capabilityId, "capabilityId must not be null");
        Objects.requireNonNull(matchReason, "matchReason must not be null");
        if (capabilityVersion == null || capabilityVersion.isBlank()) {
            throw new IllegalArgumentException("capabilityVersion must not be blank");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
    }

    static CapabilityRoute matched(
            CapabilityId capabilityId,
            CapabilityMatchReason matchReason,
            String traceId) {
        return new CapabilityRoute(capabilityId, capabilityId.version(), matchReason, traceId);
    }
}
