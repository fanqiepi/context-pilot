package io.github.fanqiepi.contextpilot.taskworkspace;

import java.util.*;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(prefix="contextpilot.task-workspace",name="enabled",havingValue="true")
public class TaskSources {
    private final TaskMapper mapper;
    private final EmbeddingIndexProperties properties;
    private final KnowledgeBaseService knowledgeBases;
    public TaskSources(TaskMapper mapper, EmbeddingIndexProperties properties, KnowledgeBaseService knowledgeBases) {
        this.mapper=mapper; this.properties=properties; this.knowledgeBases=knowledgeBases;
    }
    @org.springframework.transaction.annotation.Transactional
    public List<TaskState.Source> snapshot(UUID kb, List<UUID> ids) {
        knowledgeBases.get(kb);
        if(mapper.lockBase(kb)==null) throw new ResourceNotFoundException("TASK_KNOWLEDGE_BASE_NOT_FOUND","Knowledge base not found");
        if (ids==null || ids.isEmpty() || ids.size()>5 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size()!=ids.size())
            throw new BadRequestException("TASK_DOCUMENTS_INVALID", "Select 1 to 5 distinct documents");
        String profile=properties.currentProfile().id();
        List<TaskState.Source> result=new ArrayList<>();
        for (UUID id:ids) {
            var source=mapper.source(id,profile);
            if(source==null || !kb.equals(source.knowledgeBaseId()))
                throw new ResourceNotFoundException("TASK_DOCUMENT_NOT_FOUND", "Selected document was not found");
            if(!"SUCCEEDED".equals(source.status()) || !profile.equals(source.profileId())
                    || !source.vectorPresent() || source.indexedAt()==null)
                throw new ConflictException("TASK_DOCUMENT_NOT_READY", "Selected document requires processing or reindexing");
            result.add(new TaskState.Source(id,source.filename(),source.sha256(),profile,source.indexedAt().toInstant().toString()));
        }
        return List.copyOf(result);
    }
    @org.springframework.transaction.annotation.Transactional
    public void verify(UUID kb, TaskState state) {
        var current=snapshot(kb,state.sources.stream().map(TaskState.Source::id).toList());
        if(!current.equals(state.sources))
            throw new ConflictException("TASK_SOURCE_CHANGED", "Sources changed; add a supplement to start a new round");
    }
}
