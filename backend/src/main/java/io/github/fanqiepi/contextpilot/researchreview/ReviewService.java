package io.github.fanqiepi.contextpilot.researchreview;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import io.github.fanqiepi.contextpilot.taskworkspace.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.github.fanqiepi.contextpilot.researchreview.ReviewData.*;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="contextpilot.task-workspace",name="enabled",havingValue="true")
public class ReviewService {
    private final ReviewMapper mapper;
    private final CurrentSubjectProvider subjects;
    private final ReviewSources sources;
    public ReviewService(ReviewMapper mapper,CurrentSubjectProvider subjects,ReviewSources sources) {
        this.mapper=mapper;this.subjects=subjects;this.sources=sources;
    }
    @Transactional
    public View create(UUID taskId,Create request) {
        UUID owner=subjects.currentSubject().id();
        mapper.serialize("review:"+owner+":"+request.clientRequestId());
        var parent=ownedParent(taskId,owner);
        String fingerprint=hash(taskId+":"+request.expectedTaskRevision());
        var previous=mapper.request(owner,request.clientRequestId());
        if(previous!=null) {
            if(previous.getDeleted()!=0 || !fingerprint.equals(previous.getRequestHash())) throw conflict("REVIEW_REQUEST_CONFLICT");
            previous=mapper.lock(previous.getId());
            reconcile(previous,parent);
            return view(previous,parent,true);
        }
        if(parent.getRevision()!=request.expectedTaskRevision()) throw conflict("REVIEW_TASK_REVISION_CONFLICT");
        if(parent.getStatus()!=TaskStatus.COMPLETED) throw conflict("REVIEW_TASK_NOT_COMPLETED");
        var state=read(parent.getCheckpoint(),TaskState.class);
        var snapshot=new Snapshot(taskId,parent.getRevision(),state.round,parent.getKnowledgeBaseId(),state.goal,
                state.supplements,state.answer,state.sources,state.evidence);
        ReviewValidator.snapshot(snapshot);
        if(!sources.unchanged(snapshot)) throw conflict("REVIEW_SOURCE_CHANGED");
        String json=write(snapshot), snapshotHash=hash(json);
        // Reconcile any previous activation before enforcing the per-task active limit.
        for(UUID id:mapper.activeFor(taskId)) reconcile(mapper.lock(id),parent);
        if(mapper.activeForTask(taskId)>0) throw conflict("REVIEW_ALREADY_ACTIVE");
        if(mapper.attempts(taskId,snapshotHash)>=3) throw conflict("REVIEW_ATTEMPT_LIMIT");
        var row=new ReviewEntity();row.setId(UUID.randomUUID());row.setOwnerId(owner);row.setTaskId(taskId);
        row.setTaskRevision(parent.getRevision());row.setRound(state.round);row.setClientRequestId(request.clientRequestId());
        row.setRequestHash(fingerprint);row.setSnapshotHash(snapshotHash);row.setSnapshotJson(json);
        row.setStatus(Status.QUEUED);row.setExecutionId(UUID.randomUUID());row.setTraceId(UUID.randomUUID().toString());
        row.setPromptVersion("research-review-v1");row.setCreatedAt(OffsetDateTime.now());row.setUpdatedAt(row.getCreatedAt());row.setDeleted(0);
        mapper.create(row);return view(row,parent,true);
    }
    @Transactional
    public View get(UUID id) {
        var initial=owned(id);var parent=ownedParent(initial.getTaskId(),subjects.currentSubject().id());
        var row=mapper.lock(id);require(row);reconcile(row,parent);return view(row,parent,true);
    }
    @Transactional
    public List<View> list(UUID taskId,int page,int size) {
        if(page<1 || size<1 || size>50) throw new BadRequestException("REVIEW_PAGE_INVALID","Invalid page");
        var parent=ownedParent(taskId,subjects.currentSubject().id());
        return mapper.list(taskId,subjects.currentSubject().id(),size,((long)page-1)*size).stream().map(r->{
            var row=mapper.lock(r.getId());reconcile(row,parent);return view(row,parent,false);
        }).toList();
    }
    @Transactional
    public View cancel(UUID id,long revision) {
        var initial=owned(id);var parent=ownedParent(initial.getTaskId(),subjects.currentSubject().id());
        var row=mapper.lock(id);require(row);
        if(row.getStatus()==Status.CANCELLED) return view(row,parent,true);
        checkRevision(row,revision);
        if(!row.getStatus().active()) throw conflict("REVIEW_STATE_CONFLICT");
        terminal(row,Status.CANCELLED,"REVIEW_CANCELLED");return view(row,parent,true);
    }
    @Transactional
    public void delete(UUID id,long revision) {
        var initial=owned(id);ownedParent(initial.getTaskId(),subjects.currentSubject().id());
        var row=mapper.lock(id);require(row);checkRevision(row,revision);
        if(row.getStatus().active()) throw conflict("REVIEW_STATE_CONFLICT");
        row.setDeleted(1);save(row);
    }
    @Transactional
    public ReviewEntity begin(UUID id,UUID token) {
        var initial=mapper.selectById(id);if(initial==null) return null;
        var parent=mapper.parent(initial.getTaskId());var row=mapper.lock(id);
        if(!current(row,token)) return null;
        reconcile(row,parent);if(row.getStatus()!=Status.QUEUED) return null;
        row.setStatus(Status.RUNNING);row.setStartedAt(OffsetDateTime.now());save(row);return row;
    }
    @Transactional
    public boolean alive(UUID id,UUID token) {
        var initial=mapper.selectById(id);if(initial==null) return false;
        var parent=mapper.parent(initial.getTaskId());var row=mapper.lock(id);
        if(!current(row,token)) return false;
        reconcile(row,parent);return current(row,token);
    }
    @Transactional
    public boolean modelStarted(UUID id,UUID token) {
        var initial=mapper.selectById(id);if(initial==null) return false;
        var parent=mapper.parent(initial.getTaskId());var row=mapper.lock(id);
        if(!current(row,token)) return false;
        reconcile(row,parent);if(!current(row,token) || row.getUsageJson()!=null) return false;
        row.setUsageJson(write(new TaskState.Call("REVIEW","STARTED",null,null,null,null,0)));save(row);return true;
    }
    @Transactional
    public void complete(UUID id,UUID token,Result result,TaskState.Call usage) {
        var initial=mapper.selectById(id);if(initial==null) return;
        var parent=mapper.parent(initial.getTaskId());var row=mapper.lock(id);
        if(!current(row,token)) return;
        reconcile(row,parent);if(!current(row,token)) return;
        row.setResultJson(write(result));row.setOverallVerdict(ReviewValidator.overall(result));
        if(usage!=null) {row.setUsageJson(write(usage));row.setModel(usage.model());}
        terminal(row,Status.COMPLETED,null);
    }
    @Transactional
    public void fail(UUID id,UUID token,String code,TaskState.Call usage) {
        var row=mapper.lock(id);if(!current(row,token)) return;
        if(usage!=null) {row.setUsageJson(write(usage));row.setModel(usage.model());}
        terminal(row,Status.FAILED,code);
    }
    @Transactional
    public void recover() {
        for(UUID id:mapper.active()) {var row=mapper.lock(id);if(row!=null && row.getStatus().active()) terminal(row,Status.FAILED,"REVIEW_SERVER_RESTARTED");}
    }
    public ReviewEntity internal(UUID id) { return mapper.selectById(id); }
    private void reconcile(ReviewEntity row,TaskEntity parent) {
        if(row==null || !row.getStatus().active()) return;
        String error=null;
        if(parent==null || parent.getDeleted()!=0 || !row.getOwnerId().equals(parent.getOwnerId())
                || parent.getRevision()!=row.getTaskRevision() || parent.getStatus()!=TaskStatus.COMPLETED) error="REVIEW_TASK_CHANGED";
        else if(!sources.unchanged(snapshot(row))) error="REVIEW_SOURCE_CHANGED";
        else if(row.getStatus()==Status.QUEUED && row.getCreatedAt().plusSeconds(180).isBefore(OffsetDateTime.now())) error="REVIEW_QUEUE_TIMEOUT";
        else if(row.getStatus()==Status.RUNNING && row.getStartedAt().plusSeconds(90).isBefore(OffsetDateTime.now())) error="REVIEW_TIMEOUT";
        if(error!=null) terminal(row,Status.FAILED,error);
    }
    private View view(ReviewEntity row,TaskEntity parent,boolean detail) {
        var snapshot=snapshot(row);boolean fresh=sources.unchanged(snapshot);
        String freshness=!fresh?"SOURCE_CHANGED":parent.getRevision()!=row.getTaskRevision()?"HISTORICAL":"CURRENT";
        // A deleted or changed source must not remain readable through the review snapshot or model paraphrases.
        Snapshot visible=detail?new Snapshot(snapshot.taskId(),snapshot.taskRevision(),snapshot.round(),snapshot.knowledgeBaseId(),
                snapshot.goal(),snapshot.supplements(),fresh?snapshot.answer():null,snapshot.sources(),fresh?snapshot.evidence():List.of()):null;
        return new View(row.getId(),row.getTaskId(),row.getTaskRevision(),row.getRound(),row.getStatus(),row.getRevision(),
                fresh?row.getOverallVerdict():null,row.getErrorCode(),freshness,visible,
                detail && fresh && row.getResultJson()!=null?read(row.getResultJson(),Result.class):null,
                detail && row.getUsageJson()!=null?read(row.getUsageJson(),TaskState.Call.class):null,row.getTraceId(),row.getPromptVersion(),row.getCreatedAt());
    }
    private ReviewEntity owned(UUID id) {
        var row=mapper.selectById(id);require(row);
        if(!subjects.currentSubject().id().equals(row.getOwnerId())) throw missing();return row;
    }
    private TaskEntity ownedParent(UUID id,UUID owner) {
        var parent=mapper.parent(id);
        if(parent==null || parent.getDeleted()!=0 || !owner.equals(parent.getOwnerId())) throw missing();return parent;
    }
    private static void require(ReviewEntity row) { if(row==null || row.getDeleted()!=0) throw missing(); }
    private static ResourceNotFoundException missing() { return new ResourceNotFoundException("REVIEW_NOT_FOUND","Review or source task unavailable"); }
    private static ConflictException conflict(String code) { return new ConflictException(code,"Review state changed; refresh before retrying"); }
    private void checkRevision(ReviewEntity row,long revision) { if(revision!=row.getRevision()) throw conflict("REVIEW_REVISION_CONFLICT"); }
    private boolean current(ReviewEntity row,UUID token) { return row!=null && row.getDeleted()==0 && row.getStatus().active() && Objects.equals(token,row.getExecutionId()); }
    private void terminal(ReviewEntity row,Status status,String code) {
        row.setStatus(status);row.setErrorCode(code);row.setExecutionId(null);row.setCompletedAt(OffsetDateTime.now());
        if(row.getUsageJson()!=null) {var call=read(row.getUsageJson(),TaskState.Call.class);
            if("STARTED".equals(call.status())) row.setUsageJson(write(new TaskState.Call("REVIEW",status.name(),call.model(),null,null,null,
                    row.getStartedAt()==null?0:java.time.Duration.between(row.getStartedAt(),row.getCompletedAt()).toMillis())));}
        save(row);
    }
    private void save(ReviewEntity row) { row.setRevision(row.getRevision()+1);row.setUpdatedAt(OffsetDateTime.now());mapper.save(row); }
    static Snapshot snapshot(ReviewEntity row) { return read(row.getSnapshotJson(),Snapshot.class); }
    static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
