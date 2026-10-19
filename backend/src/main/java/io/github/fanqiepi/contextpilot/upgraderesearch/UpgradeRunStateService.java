package io.github.fanqiepi.contextpilot.upgraderesearch;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.common.ConflictException;
import io.github.fanqiepi.contextpilot.common.ResourceNotFoundException;
import io.github.fanqiepi.contextpilot.document.EmbeddingIndexProperties;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import io.github.fanqiepi.contextpilot.knowledgebase.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short database transactions only. Never invokes models, HTTP, or document processing. */
@Service
public class UpgradeRunStateService {
    private final UpgradeRunMapper mapper;
    private final CurrentSubjectProvider subjects;
    private final KnowledgeBaseService knowledgeBases;
    private final EmbeddingIndexProperties profiles;

    public UpgradeRunStateService(UpgradeRunMapper mapper, CurrentSubjectProvider subjects,
            KnowledgeBaseService knowledgeBases, EmbeddingIndexProperties profiles) {
        this.mapper=mapper; this.subjects=subjects; this.knowledgeBases=knowledgeBases; this.profiles=profiles;
    }

    public record Registration(UpgradeRunEntity run, boolean replay) { }

    // Caller must reserve capacity before a new registration, in the same outer transaction.
    @Transactional
    public Registration register(UpgradeStartRequest request, String traceId) {
        validate(request);
        if (traceId==null || traceId.isBlank() || traceId.length()>100) throw invalid();
        UUID owner=subjects.currentSubject().id();
        mapper.lockKey("upgrade:"+owner+":"+request.clientRequestId());
        String hash=fingerprint(request);
        UpgradeRunEntity existing=mapper.byRequest(owner,request.clientRequestId());
        if(existing!=null) {
            if(!hash.equals(existing.getRequestHash())) throw conflict("UPGRADE_REQUEST_ID_CONFLICT");
            if(existing.getDeleted()!=0) throw conflict("UPGRADE_RUN_DELETED");
            return new Registration(existing,true);
        }
        knowledgeBases.get(request.knowledgeBaseId());
        if(mapper.lockKnowledgeBase(request.knowledgeBaseId())==null)
            throw new ResourceNotFoundException("KNOWLEDGE_BASE_NOT_FOUND","Knowledge base is unavailable");
        List<Map<String,Object>> snapshots=snapshots(request.knowledgeBaseId(),request.documentIds());
        if(request.retryOfRunId()!=null) {
            var old=mapper.owned(owner,request.retryOfRunId());
            if(old==null || !(old.getStatus()==UpgradeRunStatus.FAILED || old.getStatus()==UpgradeRunStatus.CANCELLED)
                    || !old.getKnowledgeBaseId().equals(request.knowledgeBaseId())) throw invalid();
        }
        var now=now();
        var run=new UpgradeRunEntity();
        run.setId(UUID.randomUUID());run.setOwnerId(owner);run.setKnowledgeBaseId(request.knowledgeBaseId());
        run.setClientRequestId(request.clientRequestId());run.setRequestHash(hash);run.setQuestion(request.question().strip());
        run.setDocumentSnapshots(UpgradeJson.write(snapshots));run.setRepositoryKey(request.repositoryKey());
        run.setReleaseTags(UpgradeJson.write(request.releaseTags().stream().distinct().sorted().toList()));
        run.setRetryOfRunId(request.retryOfRunId());run.setBudgetVersion(UpgradeBudget.VERSION);
        run.setPromptVersion("upgrade-research-v6");run.setSchemaVersion("upgrade-tools-v1");
        run.setDeadline(now.plusSeconds(UpgradeBudget.RUN_SECONDS));run.setTraceId(traceId);
        run.setCreatedAt(now);run.setUpdatedAt(now);
        mapper.insertRun(run);
        return new Registration(mapper.owned(owner,run.getId()),false);
    }

    @Transactional(readOnly=true)
    public UpgradeRunEntity get(UUID id) { return require(mapper.owned(subjects.currentSubject().id(),id)); }

    @Transactional
    public UpgradeRunEntity cancel(UUID id) {
        var run=require(mapper.lockOwned(subjects.currentSubject().id(),id));
        if(run.getStatus()==UpgradeRunStatus.CANCELLED) return run;
        if(run.getStatus().terminal()) throw conflict("UPGRADE_RUN_NOT_CANCELLABLE");
        stopLocked(run,UpgradeRunStatus.CANCELLED,"USER_CANCELLED");
        return mapper.owned(run.getOwnerId(),id);
    }

    @Transactional
    public boolean claim(UUID id) {
        var run=mapper.lockInternal(id);
        if(run==null || run.getStatus()!=UpgradeRunStatus.QUEUED) return false;
        if(!run.getDeadline().isAfter(now())) {stopLocked(run,UpgradeRunStatus.FAILED,"DEADLINE_EXCEEDED");return false;}
        return mapper.claim(id,now())==1;
    }

    @Transactional
    public void fail(UUID id, String errorCode) {
        var run=mapper.lockInternal(id);
        if(run!=null && !run.getStatus().terminal()) stopLocked(run,UpgradeRunStatus.FAILED,errorCode);
    }

    @Transactional
    public void expire(UUID id) {
        var run=mapper.lockInternal(id);
        if(run!=null && !run.getStatus().terminal() && !run.getDeadline().isAfter(now()))
            stopLocked(run,UpgradeRunStatus.FAILED,"DEADLINE_EXCEEDED");
    }

    @Transactional(readOnly=true)
    public boolean sourcesUnchanged(UpgradeRunEntity run) {
        try {
            var frozen=UpgradeJson.MAPPER.readTree(run.getDocumentSnapshots());
            List<UUID> ids=new ArrayList<>();
            for(var node:frozen) ids.add(UUID.fromString(node.path("id").asText()));
            return frozen.equals(UpgradeJson.MAPPER.readTree(UpgradeJson.write(snapshots(run.getKnowledgeBaseId(),ids))));
        } catch(Exception error) { return false; }
    }

    private List<Map<String,Object>> snapshots(UUID kb,List<UUID> ids) {
        String profile=profiles.currentProfile().id();
        List<Map<String,Object>> snapshots=new ArrayList<>();
        for(UUID id:ids.stream().sorted().toList()) {
            var document=mapper.document(id,profile);
            if(document==null || !document.knowledgeBaseId().equals(kb))
                throw new ResourceNotFoundException("UPGRADE_DOCUMENT_NOT_FOUND","Selected document is unavailable");
            if(!"SUCCEEDED".equals(document.status()) || !profile.equals(document.profileId())
                    || document.indexedAt()==null || !document.vectorPresent()) throw conflict("UPGRADE_DOCUMENT_NOT_ELIGIBLE");
            snapshots.add(document.frozen());
        }
        return snapshots;
    }

    private void stopLocked(UpgradeRunEntity run,UpgradeRunStatus status,String error) {
        var now=now();
        if(mapper.stop(run.getId(),status,error,now)!=1) throw new IllegalStateException("Lost locked upgrade transition");
        String child=status==UpgradeRunStatus.CANCELLED?"CANCELLED":"FAILED";
        mapper.stopTools(run.getId(),child,error,now);mapper.stopModels(run.getId(),child,error,now);
    }

    static String fingerprint(UpgradeStartRequest request) {
        Map<String,Object> normalized=new LinkedHashMap<>();
        normalized.put("version","upgrade-request-v1");normalized.put("knowledgeBaseId",request.knowledgeBaseId().toString());
        normalized.put("documentIds",request.documentIds().stream().sorted().map(UUID::toString).toList());
        normalized.put("releaseTags",request.releaseTags().stream().distinct().sorted().toList());
        normalized.put("repositoryKey",request.repositoryKey());normalized.put("question",request.question().strip());
        normalized.put("retryOfRunId",request.retryOfRunId()==null?null:request.retryOfRunId().toString());
        return UpgradeJson.hash(UpgradeJson.write(normalized));
    }

    static void validate(UpgradeStartRequest r) {
        if(r==null || r.clientRequestId()==null || r.knowledgeBaseId()==null
            || r.question()==null || r.question().isBlank() || r.question().length()>2000
            || !"spring-ai".equals(r.repositoryKey()) || r.documentIds()==null || r.documentIds().isEmpty()
            || r.documentIds().size()>3 || r.documentIds().stream().anyMatch(Objects::isNull)
            || new HashSet<>(r.documentIds()).size()!=r.documentIds().size()
            || r.releaseTags()==null || r.releaseTags().isEmpty() || r.releaseTags().size()>3
            || r.releaseTags().stream().anyMatch(tag->tag==null || !tag.matches("[A-Za-z0-9._-]{1,100}") || tag.equals(".") || tag.equals(".."))) throw invalid();
    }
    private static UpgradeRunEntity require(UpgradeRunEntity run) {
        if(run==null) throw new ResourceNotFoundException("UPGRADE_RUN_NOT_FOUND","Upgrade research run is unavailable");
        return run;
    }
    private static BadRequestException invalid() { return new BadRequestException("UPGRADE_REQUEST_INVALID","Invalid upgrade research request"); }
    private static ConflictException conflict(String code) { return new ConflictException(code,"Upgrade research state conflict"); }
    static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
