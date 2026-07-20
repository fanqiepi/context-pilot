package io.github.fanqiepi.contextpilot.knowledgebase;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTests {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    private KnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = new KnowledgeBaseService(knowledgeBaseMapper);
    }

    @Test
    void createsKnowledgeBaseWithNormalizedValues() {
        when(knowledgeBaseMapper.insert(any(KnowledgeBaseEntity.class))).thenReturn(1);

        KnowledgeBaseResponse response = knowledgeBaseService.create(
                new KnowledgeBaseCreateRequest("  Project Notes  ", "  Team documentation  "));

        ArgumentCaptor<KnowledgeBaseEntity> captor = ArgumentCaptor.forClass(KnowledgeBaseEntity.class);
        verify(knowledgeBaseMapper).insert(captor.capture());
        KnowledgeBaseEntity entity = captor.getValue();
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getName()).isEqualTo("Project Notes");
        assertThat(entity.getDescription()).isEqualTo("Team documentation");
        assertThat(entity.getStatus()).isEqualTo(KnowledgeBaseStatus.ACTIVE);
        assertThat(response.id()).isEqualTo(entity.getId());
    }

    @Test
    void mapsDuplicateNameToConflict() {
        when(knowledgeBaseMapper.insert(any(KnowledgeBaseEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> knowledgeBaseService.create(new KnowledgeBaseCreateRequest("Notes", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Notes");
    }

    @Test
    void returnsStableMapperOrderAsResponses() {
        KnowledgeBaseEntity first = entity(UUID.randomUUID(), "First");
        KnowledgeBaseEntity second = entity(UUID.randomUUID(), "Second");
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(first, second));

        List<KnowledgeBaseResponse> responses = knowledgeBaseService.list();

        assertThat(responses).extracting(KnowledgeBaseResponse::name).containsExactly("First", "Second");
    }

    @Test
    void rejectsEmptyPatch() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> knowledgeBaseService.update(id, new KnowledgeBaseUpdateRequest(null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("At least one field");
    }

    @Test
    void updatesNameAndClearsBlankDescription() {
        UUID id = UUID.randomUUID();
        KnowledgeBaseEntity entity = entity(id, "Old name");
        entity.setDescription("Old description");
        when(knowledgeBaseMapper.selectById(id)).thenReturn(entity);
        when(knowledgeBaseMapper.updateById(entity)).thenReturn(1);

        KnowledgeBaseResponse response = knowledgeBaseService.update(
                id,
                new KnowledgeBaseUpdateRequest(" New name ", "   "));

        assertThat(response.name()).isEqualTo("New name");
        assertThat(response.description()).isNull();
        assertThat(response.updatedAt()).isAfterOrEqualTo(entity.getCreatedAt());
    }

    @Test
    void reportsMissingKnowledgeBase() {
        UUID id = UUID.randomUUID();
        when(knowledgeBaseMapper.selectById(id)).thenReturn(null);

        assertThatThrownBy(() -> knowledgeBaseService.get(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void deletesExistingKnowledgeBase() {
        UUID id = UUID.randomUUID();
        when(knowledgeBaseMapper.deleteById(id)).thenReturn(1);

        knowledgeBaseService.delete(id);

        verify(knowledgeBaseMapper).deleteById(id);
    }

    private KnowledgeBaseEntity entity(UUID id, String name) {
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setStatus(KnowledgeBaseStatus.ACTIVE);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }
}
