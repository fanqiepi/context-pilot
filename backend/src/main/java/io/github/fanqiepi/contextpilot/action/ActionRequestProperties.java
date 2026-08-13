package io.github.fanqiepi.contextpilot.action;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "contextpilot.action-request")
public class ActionRequestProperties {

    @Min(1)
    @Max(10080)
    private long confirmationTimeoutMinutes = 30;

    public long getConfirmationTimeoutMinutes() {
        return confirmationTimeoutMinutes;
    }

    public void setConfirmationTimeoutMinutes(long confirmationTimeoutMinutes) {
        this.confirmationTimeoutMinutes = confirmationTimeoutMinutes;
    }
}
