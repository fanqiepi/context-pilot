package io.github.fanqiepi.contextpilot.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "contextpilot.document.processing")
public class DocumentProcessingProperties {

    private boolean enabled;
    private int corePoolSize = 1;
    private int maxPoolSize = 2;
    private int queueCapacity = 50;
    private int maxAttempts = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    void validate() {
        if (corePoolSize <= 0) {
            throw new IllegalStateException("Document processing core pool size must be positive");
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalStateException("Document processing max pool size must not be smaller than core pool size");
        }
        if (queueCapacity < 0) {
            throw new IllegalStateException("Document processing queue capacity must not be negative");
        }
        if (maxAttempts <= 0) {
            throw new IllegalStateException("Document processing max attempts must be positive");
        }
    }
}
