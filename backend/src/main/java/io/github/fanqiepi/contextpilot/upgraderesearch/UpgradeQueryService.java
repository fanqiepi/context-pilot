package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.util.*;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class UpgradeQueryService {
    private final UpgradeRunStateService state;
    private final UpgradeRunMapper runs;
    private final UpgradeExecutionMapper calls;
    private final CurrentSubjectProvider subjects;
    private final KnowledgeBaseService knowledgeBases;
    public UpgradeQueryService(UpgradeRunStateService state,UpgradeRunMapper runs,UpgradeExecutionMapper calls,CurrentSubjectProvider subjects,KnowledgeBaseService knowledgeBases){
        this.state=state;this.runs=runs;this.calls=calls;this.subjects=subjects;this.knowledgeBases=knowledgeBases;
    }
    public record View(UpgradeRunEntity run,List<UpgradeEvidence> sources,List<Map<String,Object>> tools,List<Map<String,Object>> modelCalls,UUID saveActionId){
        public UUID getRunId(){return run.getId();}
        public String getTraceId(){return run.getTraceId();}
        public long getRevision(){return run.getRevision();}
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View get(UUID id){var run=state.get(id);return new View(run,calls.sources(id),calls.toolSummaries(id),calls.modelSummaries(id),runs.latestSaveAction(id));}
    @Transactional(readOnly=true)
    public List<UpgradeRunEntity> list(UUID kb,int page,int size){
        page(page,size);knowledgeBases.get(kb);return runs.listOwned(subjects.currentSubject().id(),kb,size,(long)(page-1)*size);
    }
    @Transactional
    public void delete(UUID id){
        var run=runs.lockOwned(subjects.currentSubject().id(),id);
        if(run==null)return;
        if(runs.deleteTerminal(id)!=1)throw new ConflictException("UPGRADE_RUN_NOT_DELETABLE","Finish the run and resolve report/save proposals first");
    }
    public static void page(int page,int size){if(page<1 || size<1 || size>50)throw new BadRequestException("INVALID_PAGE","Page size must be 1 to 50");}
}
