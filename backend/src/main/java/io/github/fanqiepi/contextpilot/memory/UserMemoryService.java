package io.github.fanqiepi.contextpilot.memory;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.InternalServiceException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.identity.AppUserEntity;
import io.github.fanqiepi.contextpilot.identity.AppUserMapper;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import io.github.fanqiepi.contextpilot.identity.UserSubject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

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

    @Transactional(readOnly = true)
    public List<UserMemoryResponse> list(UserMemoryStatus status, Boolean expired) {
        UserSubject subject = currentSubjectProvider.currentSubject();
        OffsetDateTime now = now();
        return memoryMapper.selectOwnedAll(subject.id()).stream()
                .filter(memory -> status == null || memory.getStatus() == status)
                .filter(memory -> expired == null || isExpired(memory, now) == expired)
                .map(memory -> toResponse(subject.id(), memory, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public UserMemoryDetailResponse get(UUID id) {
        UserSubject subject = currentSubjectProvider.currentSubject();
        UserMemoryEntity memory = requireOwned(subject.id(), id);
        return toDetail(subject.id(), memory, now());
    }

    @Transactional
    public UserMemoryDetailResponse update(UUID id, UserMemoryUpdateRequest request) {
        if (request.getVersion() == null || request.getVersion() < 1) {
            throw new BadRequestException(
                    "USER_MEMORY_VERSION_INVALID",
                    "A positive current response preference version is required");
        }
        if (!request.isContentProvided() && !request.isStatusProvided() && !request.isExpiresAtProvided()) {
            throw new BadRequestException(
                    "USER_MEMORY_UPDATE_EMPTY",
                    "At least one response preference field must be provided");
        }

        UserSubject subject = currentSubjectProvider.currentSubject();
        requireLockedOwner(subject);
        UserMemoryEntity memory = requireOwned(subject.id(), id);
        if (!Objects.equals(memory.getVersion(), request.getVersion())) {
            throw versionConflict(id);
        }

        OffsetDateTime now = now();
        UserMemoryRevisionEntity currentRevision = requireRevision(subject.id(), memory);
        String content = request.isContentProvided()
                ? UserMemoryContent.normalize(request.getContent())
                : currentRevision.getContent();
        String contentHash = UserMemoryContent.hash(content);
        UserMemoryStatus status = resolveStatus(request, memory);
        OffsetDateTime expiresAt = request.isExpiresAtProvided()
                ? request.getExpiresAt()
                : memory.getExpiresAt();
        if (request.isExpiresAtProvided() && expiresAt != null && !expiresAt.isAfter(now)) {
            throw new ConflictException(
                    "USER_MEMORY_EXPIRY_INVALID",
                    "Response preference expiry must be in the future");
        }

        boolean contentChanged = !content.equals(currentRevision.getContent());
        boolean statusChanged = status != memory.getStatus();
        boolean expiryChanged = !Objects.equals(expiresAt, memory.getExpiresAt());
        if (!contentChanged && !statusChanged && !expiryChanged) {
            throw new BadRequestException(
                    "USER_MEMORY_UPDATE_EMPTY",
                    "The response preference update does not change any field");
        }

        boolean wasEffective = isEffective(memory.getStatus(), memory.getExpiresAt(), now);
        boolean willBeEffective = isEffective(status, expiresAt, now);
        if (willBeEffective) {
            UserMemoryEntity duplicate = memoryMapper.selectStatusActiveByHashExcludingId(
                    subject.id(), contentHash, id);
            if (duplicate != null) {
                throw duplicate();
            }
            if (!wasEffective && memoryMapper.countActive(subject.id(), now) >= MAX_ACTIVE_MEMORIES) {
                throw limitReached();
            }
        }

        int nextRevisionNumber = memory.getCurrentRevision() + 1;
        UserMemoryRevisionEntity revision = new UserMemoryRevisionEntity();
        revision.setId(UUID.randomUUID());
        revision.setMemoryId(id);
        revision.setRevisionNumber(nextRevisionNumber);
        revision.setContent(content);
        revision.setContentHash(contentHash);
        revision.setSourceType(UserMemorySourceType.MANAGEMENT);
        revision.setChangeReason(changeReason(contentChanged, statusChanged, expiryChanged));
        revision.setCreatedAt(now);

        try {
            if (revisionMapper.insert(revision) != 1) {
                throw new IllegalStateException("Response preference revision could not be created");
            }
            if (memoryMapper.updateOwnedWithVersion(
                    subject.id(),
                    id,
                    request.getVersion(),
                    status,
                    nextRevisionNumber,
                    contentHash,
                    expiresAt,
                    now) != 1) {
                throw versionConflict(id);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(
                    "USER_MEMORY_DUPLICATE",
                    "An active response preference with the same content already exists",
                    exception);
        }

        memory.setStatus(status);
        memory.setCurrentRevision(nextRevisionNumber);
        memory.setContentHash(contentHash);
        memory.setExpiresAt(expiresAt);
        memory.setVersion(memory.getVersion() + 1);
        memory.setUpdatedAt(now);
        return toDetail(subject.id(), memory, now);
    }

    @Transactional
    public void delete(UUID id) {
        UserSubject subject = currentSubjectProvider.currentSubject();
        requireLockedOwner(subject);
        UserMemoryEntity memory = memoryMapper.selectOwnedByIdIncludingDeleted(subject.id(), id);
        if (memory == null) {
            throw notFound(id);
        }
        if (memory.getDeleted() != null && memory.getDeleted() == 1) {
            return;
        }
        if (memoryMapper.logicalDeleteOwned(subject.id(), id, now()) != 1) {
            UserMemoryEntity latest = memoryMapper.selectOwnedByIdIncludingDeleted(subject.id(), id);
            if (latest == null) {
                throw notFound(id);
            }
        }
    }

    private UserMemoryStatus resolveStatus(UserMemoryUpdateRequest request, UserMemoryEntity memory) {
        if (!request.isStatusProvided()) {
            return memory.getStatus();
        }
        if (request.getStatus() == null) {
            throw new BadRequestException(
                    "INVALID_REQUEST",
                    "Response preference status must be ACTIVE or DISABLED");
        }
        return request.getStatus();
    }

    private String changeReason(boolean contentChanged, boolean statusChanged, boolean expiryChanged) {
        List<String> changes = new ArrayList<>();
        if (contentChanged) {
            changes.add("content corrected");
        }
        if (statusChanged) {
            changes.add("status changed");
        }
        if (expiryChanged) {
            changes.add("expiry changed");
        }
        return "Management update: " + String.join(", ", changes);
    }

    private UserMemoryResponse toResponse(UUID ownerUserId, UserMemoryEntity memory, OffsetDateTime now) {
        UserMemoryRevisionEntity revision = requireRevision(ownerUserId, memory);
        return new UserMemoryResponse(
                memory.getId(),
                memory.getMemoryType(),
                memory.getScopeType(),
                memory.getStatus(),
                revision.getContent(),
                memory.getCurrentRevision(),
                revision.getSourceType(),
                memory.getExpiresAt(),
                isExpired(memory, now),
                memory.getVersion(),
                memory.getCreatedAt(),
                memory.getUpdatedAt());
    }

    private UserMemoryDetailResponse toDetail(UUID ownerUserId, UserMemoryEntity memory, OffsetDateTime now) {
        UserMemoryRevisionEntity revision = requireRevision(ownerUserId, memory);
        List<UserMemoryRevisionSummaryResponse> revisions = revisionMapper
                .selectOwnedRevisionHistory(ownerUserId, memory.getId()).stream()
                .map(UserMemoryRevisionSummaryResponse::from)
                .toList();
        return new UserMemoryDetailResponse(
                memory.getId(),
                memory.getMemoryType(),
                memory.getScopeType(),
                memory.getStatus(),
                revision.getContent(),
                memory.getCurrentRevision(),
                revision.getSourceType(),
                memory.getExpiresAt(),
                isExpired(memory, now),
                memory.getVersion(),
                memory.getCreatedAt(),
                memory.getUpdatedAt(),
                revisions);
    }

    private UserMemoryRevisionEntity requireRevision(UUID ownerUserId, UserMemoryEntity memory) {
        UserMemoryRevisionEntity revision = revisionMapper.selectOwnedRevision(
                ownerUserId, memory.getId(), memory.getCurrentRevision());
        if (revision == null) {
            throw new InternalServiceException(
                    "USER_MEMORY_REVISION_UNAVAILABLE",
                    "The current response preference revision is unavailable",
                    null);
        }
        return revision;
    }

    private UserMemoryEntity requireOwned(UUID ownerUserId, UUID id) {
        UserMemoryEntity memory = memoryMapper.selectOwnedById(ownerUserId, id);
        if (memory == null) {
            throw notFound(id);
        }
        return memory;
    }

    private void requireLockedOwner(UserSubject subject) {
        AppUserEntity lockedOwner = appUserMapper.lockActiveById(subject.id());
        if (lockedOwner == null || !lockedOwner.getUserKey().equals(subject.key())) {
            throw new InternalServiceException(
                    "CURRENT_SUBJECT_UNAVAILABLE",
                    "The current subject is unavailable",
                    null);
        }
    }

    private boolean isExpired(UserMemoryEntity memory, OffsetDateTime now) {
        return memory.getExpiresAt() != null && !memory.getExpiresAt().isAfter(now);
    }

    private boolean isEffective(UserMemoryStatus status, OffsetDateTime expiresAt, OffsetDateTime now) {
        return status == UserMemoryStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }

    private ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException(
                "USER_MEMORY_NOT_FOUND",
                "Response preference " + id + " was not found");
    }

    private ConflictException versionConflict(UUID id) {
        return new ConflictException(
                "USER_MEMORY_VERSION_CONFLICT",
                "Response preference " + id + " was changed; refresh before updating it again");
    }

    private ConflictException duplicate() {
        return new ConflictException(
                "USER_MEMORY_DUPLICATE",
                "An active response preference with the same content already exists");
    }

    private ConflictException limitReached() {
        return new ConflictException(
                "USER_MEMORY_LIMIT_REACHED",
                "Response preference limit reached; manage existing preferences before creating or enabling another");
    }

    private void enforceLimits(UUID ownerUserId, OffsetDateTime now) {
        if (memoryMapper.countUndeleted(ownerUserId) >= MAX_UNDELETED_MEMORIES
                || memoryMapper.countActive(ownerUserId, now) >= MAX_ACTIVE_MEMORIES) {
            throw limitReached();
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
