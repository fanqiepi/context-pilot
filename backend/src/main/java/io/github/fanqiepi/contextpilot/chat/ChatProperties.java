package io.github.fanqiepi.contextpilot.chat;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "contextpilot.chat")
public class ChatProperties {

    @Min(1)
    @Max(20)
    private int retrievalTopK = 5;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minSimilarity = 0.5;

    @Min(1000)
    @Max(20000)
    private int maxEvidenceCharacters = 6000;

    public int getRetrievalTopK() { return retrievalTopK; }
    public void setRetrievalTopK(int retrievalTopK) { this.retrievalTopK = retrievalTopK; }
    public double getMinSimilarity() { return minSimilarity; }
    public void setMinSimilarity(double minSimilarity) { this.minSimilarity = minSimilarity; }
    public int getMaxEvidenceCharacters() { return maxEvidenceCharacters; }
    public void setMaxEvidenceCharacters(int maxEvidenceCharacters) {
        this.maxEvidenceCharacters = maxEvidenceCharacters;
    }
}
