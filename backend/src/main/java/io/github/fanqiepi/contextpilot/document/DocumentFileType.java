package io.github.fanqiepi.contextpilot.document;

public enum DocumentFileType {
    TXT("txt", "text/plain"),
    MARKDOWN("md", "text/markdown"),
    PDF("pdf", "application/pdf");

    private final String storageExtension;
    private final String mediaType;

    DocumentFileType(String storageExtension, String mediaType) {
        this.storageExtension = storageExtension;
        this.mediaType = mediaType;
    }

    public String getStorageExtension() {
        return storageExtension;
    }

    public String getMediaType() {
        return mediaType;
    }
}
