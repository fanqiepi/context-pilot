package io.github.fanqiepi.contextpilot.knowledgebase;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Transactional
    public KnowledgeBaseResponse create(KnowledgeBaseCreateRequest request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(normalizeRequiredName(request.name()));
        entity.setDescription(normalizeDescription(request.description()));
        entity.setStatus(KnowledgeBaseStatus.ACTIVE);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        try {
            knowledgeBaseMapper.insert(entity);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName(entity.getName(), exception);
        }
        return KnowledgeBaseResponse.from(entity);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> list() {
        return knowledgeBaseMapper.selectList(
                        Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                                .orderByDesc(KnowledgeBaseEntity::getCreatedAt)
                                .orderByDesc(KnowledgeBaseEntity::getId))
                .stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseResponse get(UUID id) {
        return KnowledgeBaseResponse.from(requireEntity(id));
    }

    @Transactional
    public KnowledgeBaseResponse update(UUID id, KnowledgeBaseUpdateRequest request) {
        if (request.name() == null && request.description() == null) {
            throw new BadRequestException("EMPTY_UPDATE", "At least one field must be provided");
        }

        KnowledgeBaseEntity entity = requireEntity(id);
        if (request.name() != null) {
            entity.setName(normalizeRequiredName(request.name()));
        }
        if (request.description() != null) {
            entity.setDescription(normalizeDescription(request.description()));
        }
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        try {
            if (knowledgeBaseMapper.updateById(entity) == 0) {
                throw notFound(id);
            }
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName(entity.getName(), exception);
        }
        return KnowledgeBaseResponse.from(entity);
    }

    @Transactional
    public void delete(UUID id) {
        try {
            if (knowledgeBaseMapper.deleteById(id) == 0) {
                throw notFound(id);
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException(
                    "KNOWLEDGE_BASE_NOT_EMPTY",
                    "Delete all documents before deleting the knowledge base",
                    exception);
        }
    }

    private KnowledgeBaseEntity requireEntity(UUID id) {
        KnowledgeBaseEntity entity = knowledgeBaseMapper.selectById(id);
        if (entity == null) {
            throw notFound(id);
        }
        return entity;
    }

    private String normalizeRequiredName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new BadRequestException("INVALID_KNOWLEDGE_BASE_NAME", "Knowledge base name must not be blank");
        }
        return normalized;
    }

    private String normalizeDescription(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException(
                "KNOWLEDGE_BASE_NOT_FOUND",
                "Knowledge base " + id + " was not found");
    }

    private ConflictException duplicateName(String name, DataIntegrityViolationException cause) {
        return new ConflictException(
                "KNOWLEDGE_BASE_NAME_CONFLICT",
                "A knowledge base named '" + name + "' already exists",
                cause);
    }
}
