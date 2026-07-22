package io.github.fanqiepi.contextpilot.common;

public class PayloadTooLargeException extends RuntimeException {

    private final String code;

    public PayloadTooLargeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
