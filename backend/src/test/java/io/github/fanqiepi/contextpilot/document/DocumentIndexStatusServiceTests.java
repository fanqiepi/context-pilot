package io.github.fanqiepi.contextpilot.document;

import java.util.UUID;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIndexStatusServiceTests {

    @Mock
    private SourceDocumentMapper sourceDocumentMapper;

    private DocumentIndexStatusService statusService;

    @BeforeEach
    void setUp() {
        statusService = new DocumentIndexStatusService(sourceDocumentMapper, new EmbeddingIndexProperties());
    }

    @Test
    void requiresReindexWhenSucceededDocumentsHaveNoCurrentProfile() {
        when(sourceDocumentMapper.selectCount(anyWrapper())).thenReturn(2L, 0L);

        assertThat(statusService.requiresReindex(UUID.randomUUID())).isTrue();
    }

    @Test
    void acceptsKnowledgeBaseWithAtLeastOneCurrentIndex() {
        when(sourceDocumentMapper.selectCount(anyWrapper())).thenReturn(2L, 1L);

        assertThat(statusService.requiresReindex(UUID.randomUUID())).isFalse();
    }

    @Test
    void doesNotRequireReindexForEmptyKnowledgeBase() {
        when(sourceDocumentMapper.selectCount(anyWrapper())).thenReturn(0L);

        assertThat(statusService.requiresReindex(UUID.randomUUID())).isFalse();
    }

    private Wrapper<SourceDocumentEntity> anyWrapper() {
        return any();
    }
}
