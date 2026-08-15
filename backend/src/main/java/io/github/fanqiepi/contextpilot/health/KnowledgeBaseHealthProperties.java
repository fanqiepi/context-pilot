package io.github.fanqiepi.contextpilot.health;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "contextpilot.health")
public class KnowledgeBaseHealthProperties {

    public static final int HARD_MAXIMUM_ISSUES = 500;

    private int issueLimit = 100;

    public int getIssueLimit() {
        return issueLimit;
    }

    public void setIssueLimit(int issueLimit) {
        this.issueLimit = issueLimit;
    }

    @PostConstruct
    void validate() {
        if (issueLimit <= 0 || issueLimit > HARD_MAXIMUM_ISSUES) {
            throw new IllegalStateException("Health issue limit must be between 1 and " + HARD_MAXIMUM_ISSUES);
        }
    }
}
