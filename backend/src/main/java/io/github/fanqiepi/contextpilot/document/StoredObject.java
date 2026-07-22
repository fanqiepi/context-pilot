package io.github.fanqiepi.contextpilot.document;

public record StoredObject(String storageKey, long sizeBytes, String sha256) {
}
