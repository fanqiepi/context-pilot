package io.github.fanqiepi.contextpilot.chat;

public enum CapabilityId {
    SIMPLE_CHAT("v1"),
    KNOWLEDGE_QA("v1"),
    BUSINESS_ACTION("v1");

    private final String version;

    CapabilityId(String version) {
        this.version = version;
    }

    public String version() {
        return version;
    }
}
