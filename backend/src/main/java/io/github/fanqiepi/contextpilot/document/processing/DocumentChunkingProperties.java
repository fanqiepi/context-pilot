package io.github.fanqiepi.contextpilot.document.processing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "contextpilot.document.chunking")
public class DocumentChunkingProperties {

    private int maxCharacters = 1200;
    private int overlapCharacters = 150;

    public int getMaxCharacters() {
        return maxCharacters;
    }

    public void setMaxCharacters(int maxCharacters) {
        this.maxCharacters = maxCharacters;
    }

    public int getOverlapCharacters() {
        return overlapCharacters;
    }

    public void setOverlapCharacters(int overlapCharacters) {
        this.overlapCharacters = overlapCharacters;
    }

    void validate() {
        if (maxCharacters <= 0) {
            throw new IllegalStateException("contextpilot.document.chunking.max-characters must be positive");
        }
        if (overlapCharacters < 0 || overlapCharacters >= maxCharacters) {
            throw new IllegalStateException(
                    "contextpilot.document.chunking.overlap-characters must be non-negative and smaller than max-characters");
        }
    }
}
