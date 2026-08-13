package io.github.fanqiepi.contextpilot.document;

import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

@Service
public class DocumentIndexStatusService {

    private final SourceDocumentMapper sourceDocumentMapper;
    private final EmbeddingIndexProperties embeddingIndexProperties;

    public DocumentIndexStatusService(
            SourceDocumentMapper sourceDocumentMapper,
            EmbeddingIndexProperties embeddingIndexProperties) {
        this.sourceDocumentMapper = sourceDocumentMapper;
        this.embeddingIndexProperties = embeddingIndexProperties;
    }

    public boolean requiresReindex(UUID knowledgeBaseId) {
        long succeededCount = sourceDocumentMapper.selectCount(
                Wrappers.<SourceDocumentEntity>lambdaQuery()
                        .eq(SourceDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(SourceDocumentEntity::getStatus, DocumentStatus.SUCCEEDED));
        if (succeededCount == 0) {
            return false;
        }
        long currentCount = sourceDocumentMapper.selectCount(
                Wrappers.<SourceDocumentEntity>lambdaQuery()
                        .eq(SourceDocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(SourceDocumentEntity::getStatus, DocumentStatus.SUCCEEDED)
                        .eq(SourceDocumentEntity::getEmbeddingProfileId,
                                embeddingIndexProperties.currentProfile().id()));
        return currentCount == 0;
    }
}
