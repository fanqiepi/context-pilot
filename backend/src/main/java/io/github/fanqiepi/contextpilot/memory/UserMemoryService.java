package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.identity.AppUserEntity;
import io.github.fanqiepi.contextpilot.identity.AppUserMapper;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import io.github.fanqiepi.contextpilot.identity.UserSubject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserMemoryService {

    static final int MAX_UNDELETED_MEMORIES = 100;
    static final int MAX_ACTIVE_MEMORIES = 20;

    private final CurrentSubjectProvider currentSubjectProvider;
    private final AppUserMapper appUserMapper;
    private final UserMemoryMapper memoryMapper;
    private final UserMemoryRevisionMapper revisionMapper;

    public UserMemoryService(
            CurrentSubjectProvider currentSubjectProvider,
            AppUserMapper appUserMapper,
            UserMemoryMapper memoryMapper,
            UserMemoryRevisionMapper revisionMapper) {
        this.currentSubjectProvider = currentSubjectProvider;
        this.appUserMapper = appUserMapper;
        this.memoryMapper = memoryMapper;
        this.revisionMapper = revisionMapper;
    }

    @Transactional
    public UserMemoryCreationResult createConfirmedResponsePreference(
            String content,
            OffsetDateTime expiresAt,
            UUID sourceConversationId,
            UUID sourceMessageId) {
        Objects.requireNonNull(sourceConversationId, "Source conversation ID must not be null");
        Objects.requireNonNull(sourceMessageId, "Source message ID must not be null");
        UserSubject subject = currentSubjectProvider.currentSubject();
        String normalized = UserMemoryContent.normalize(content);
        String contentHash = UserMemoryContent.hash(normalized);
        OffsetDateTime now = now();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new ConflictException(
                    "USER_MEMORY_EXPIRY_INVALID",
                    "Response preference expiry must be in the future");
        }

        AppUserEntity lockedOwner = appUserMapper.lockActiveById(subject.id());
        if (lockedOwner == null || !lockedOwner.getUserKey().equals(subject.key())) {
            throw new InternalServiceException(
                    "CURRENT_SUBJECT_UNAVAILABLE",
                    "The current subject is unavailable",
                    null);
        }

        UserMemoryEntity duplicate = memoryMapper.selectActiveByHash(subject.id(), contentHash, now);
        if (duplicate != null) {
            UserMemoryRevisionEntity revision = revisionMapper.selectOwnedRevision(
                    subject.id(), duplicate.getId(), duplicate.getCurrentRevision());
            if (revision == null) {
                throw new InternalServiceException(
                        "USER_MEMORY_REVISION_UNAVAILABLE",
                        "The current response preference revision is unavailable",
                        null);
            }
            return new UserMemoryCreationResult(
                    duplicate.getId(), duplicate.getCurrentRevision(), revision.getContent(), false);
        }

        enforceLimits(subject.id(), now);
        UUID memoryId = UUID.randomUUID();
        UserMemoryEntity memory = new UserMemoryEntity();
        memory.setId(memoryId);
        memory.setOwnerUserId(subject.id());
        memory.setMemoryType(UserMemoryType.RESPONSE_PREFERENCE);
        memory.setScopeType(UserMemoryScope.GLOBAL);
        memory.setStatus(UserMemoryStatus.ACTIVE);
        memory.setCurrentRevision(1);
        memory.setContentHash(contentHash);
        memory.setExpiresAt(expiresAt);
        memory.setVersion(1L);
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        if (memoryMapper.insert(memory) != 1) {
            throw new IllegalStateException("Response preference could not be created");
        }

        UserMemoryRevisionEntity revision = new UserMemoryRevisionEntity();
        revision.setId(UUID.randomUUID());
        revision.setMemoryId(memoryId);
        revision.setRevisionNumber(1);
        revision.setContent(normalized);
        revision.setContentHash(contentHash);
        revision.setSourceType(UserMemorySourceType.CHAT_CONFIRMED);
        revision.setSourceConversationId(sourceConversationId);
        revision.setSourceMessageId(sourceMessageId);
        revision.setChangeReason("Created from confirmed chat action");
        revision.setCreatedAt(now);
        if (revisionMapper.insert(revision) != 1) {
            throw new IllegalStateException("Response preference revision could not be created");
        }
        return new UserMemoryCreationResult(memoryId, 1, normalized, true);
    }

    private void enforceLimits(UUID ownerUserId, OffsetDateTime now) {
        if (memoryMapper.countUndeleted(ownerUserId) >= MAX_UNDELETED_MEMORIES
                || memoryMapper.countActive(ownerUserId, now) >= MAX_ACTIVE_MEMORIES) {
            throw new ConflictException(
                    "USER_MEMORY_LIMIT_REACHED",
                    "Response preference limit reached; manage existing preferences before creating another");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
