package io.github.fanqiepi.contextpilot.book;

public class ReadingException extends RuntimeException {
    private final int status;
    private final String code;
    private final Long currentRevision;

    public ReadingException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ReadingException(int status, String code, String message, Long revision) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentRevision = revision;
    }

    public int status() { return status; }
    public String code() { return code; }
    public Long currentRevision() { return currentRevision; }

    public static ReadingException invalid(String code, String message) { return new ReadingException(400, code, message); }
    public static ReadingException conflict(String code, String message) { return new ReadingException(409, code, message); }
    public static ReadingException missing() { return new ReadingException(404, "READING_RESOURCE_NOT_FOUND", "阅读资源不存在"); }
}
