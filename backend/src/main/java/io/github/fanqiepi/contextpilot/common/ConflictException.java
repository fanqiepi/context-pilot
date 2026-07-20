package io.github.fanqiepi.contextpilot.common;

public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
