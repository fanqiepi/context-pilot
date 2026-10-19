package io.github.fanqiepi.contextpilot.researchreview;

import java.util.*;
import io.github.fanqiepi.contextpilot.taskworkspace.*;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import org.springframework.stereotype.Component;

/** Runs under the review transaction, retaining source locks through checkpoint persistence. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="contextpilot.task-workspace",name="enabled",havingValue="true")
public class ReviewSources {
    private final TaskMapper tasks;
    private final EmbeddingIndexProperties profiles;
    public ReviewSources(TaskMapper tasks,EmbeddingIndexProperties profiles) { this.tasks=tasks;this.profiles=profiles; }
    boolean unchanged(ReviewData.Snapshot snapshot) {
        if(tasks.lockBase(snapshot.knowledgeBaseId())==null) return false;
        String profile=profiles.currentProfile().id();
        for(var expected:snapshot.sources()) {
            var actual=tasks.source(expected.id(),profile);
            if(actual==null || !snapshot.knowledgeBaseId().equals(actual.knowledgeBaseId())
                    || !"SUCCEEDED".equals(actual.status()) || !profile.equals(actual.profileId()) || !actual.vectorPresent()
                    || actual.indexedAt()==null || !Objects.equals(expected.sha256(),actual.sha256())
                    || !Objects.equals(expected.profileId(),actual.profileId())
                    || !Objects.equals(expected.filename(),actual.filename())
                    || !expected.indexedAt().equals(actual.indexedAt().toInstant().toString())) return false;
        }
        return true;
    }
}
