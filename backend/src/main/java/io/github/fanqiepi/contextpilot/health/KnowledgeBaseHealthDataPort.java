package io.github.fanqiepi.contextpilot.health;

import java.util.UUID;

public interface KnowledgeBaseHealthDataPort {

    KnowledgeBaseHealthFacts inspect(UUID knowledgeBaseId, String currentEmbeddingProfileId, int issueLimit);
}
