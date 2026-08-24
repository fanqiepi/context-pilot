package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import io.github.fanqiepi.contextpilot.identity.UserSubject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryContextPersistenceService {

    static final int MAX_PREFERENCES = 10;
    static final int MAX_CHARACTERS = 2000;

    private final CurrentSubjectProvider currentSubjectProvider;
    private final UserMemoryMapper memoryMapper;
    private final MessageMemoryContextMapper contextMapper;

    public MemoryContextPersistenceService(
            CurrentSubjectProvider currentSubjectProvider,
            ObjectProvider<UserMemoryMapper> memoryMapper,
            ObjectProvider<MessageMemoryContextMapper> contextMapper) {
        this.currentSubjectProvider = currentSubjectProvider;
        this.memoryMapper = memoryMapper.getIfAvailable();
        this.contextMapper = contextMapper.getIfAvailable();
    }

    @Transactional
    public MemoryContext loadAndRecord(UUID assistantMessageId) {
        if (memoryMapper == null || contextMapper == null) {
            throw new IllegalStateException("Memory persistence infrastructure is unavailable");
        }
        UserSubject subject = currentSubjectProvider.currentSubject();
        List<UserMemoryContextCandidate> candidates = memoryMapper.selectActiveContextCandidates(
                subject.id(), OffsetDateTime.now(ZoneOffset.UTC));
        List<MemoryContextPreference> selected = new ArrayList<>();
        int characters = 0;
        boolean truncated = false;
        for (UserMemoryContextCandidate candidate : candidates) {
            String content = candidate.getContent();
            if (selected.size() >= MAX_PREFERENCES
                    || characters + content.length() > MAX_CHARACTERS) {
                truncated = true;
                break;
            }
            selected.add(new MemoryContextPreference(
                    candidate.getMemoryId(), candidate.getRevisionId(),
                    candidate.getRevisionNumber(), content));
            characters += content.length();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (int index = 0; index < selected.size(); index++) {
            MemoryContextPreference preference = selected.get(index);
            MessageMemoryContextEntity entity = new MessageMemoryContextEntity();
            entity.setId(UUID.randomUUID());
            entity.setAssistantMessageId(assistantMessageId);
            entity.setMemoryId(preference.memoryId());
            entity.setMemoryRevisionId(preference.revisionId());
            entity.setDisplayOrder(index + 1);
            entity.setContextCharacterCount(characters);
            entity.setContextTruncated(truncated);
            entity.setCreatedAt(now);
            if (contextMapper.insert(entity) != 1) {
                throw new IllegalStateException("Message memory context could not be recorded");
            }
        }
        return new MemoryContext(selected, characters, truncated);
    }
}
