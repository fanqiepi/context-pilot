package io.github.fanqiepi.contextpilot.upgraderesearch;

/** Safe code only; never carries a remote response or model parameters in its message. */
public class UpgradeToolException extends RuntimeException {
    private final String code;
    public UpgradeToolException(String code) { super(code);this.code=code; }
    public String code() { return code; }
}
