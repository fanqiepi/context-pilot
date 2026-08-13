package io.github.fanqiepi.contextpilot.document;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "contextpilot.embedding.index")
public class EmbeddingIndexProperties {

    private String profileId = "dashscope_qwen3_7_1024_v1";
    private String provider = "DASHSCOPE";
    private String model = "qwen3.7-text-embedding";
    private int dimensions = 1024;
    private String version = "v1";

    @PostConstruct
    void validate() {
        currentProfile();
    }

    public EmbeddingIndexProfile currentProfile() {
        return new EmbeddingIndexProfile(profileId, provider, model, dimensions, version);
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}
