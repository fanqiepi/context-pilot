package io.github.fanqiepi.contextpilot.common;

public class InternalServiceException extends RuntimeException {

    private final String code;

    public InternalServiceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
