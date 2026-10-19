package io.github.fanqiepi.contextpilot.taskworkspace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.identity.CurrentSubjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(prefix="contextpilot.task-workspace",name="enabled",havingValue="true")
public class TaskService {
    private final TaskMapper mapper;
    private final CurrentSubjectProvider subjects;
    private final TaskSources sources;
    public TaskService(TaskMapper mapper, CurrentSubjectProvider subjects, TaskSources sources) {
        this.mapper=mapper; this.subjects=subjects; this.sources=sources;
    }

    @Transactional
    public TaskRequests.View create(TaskRequests.Create request) {
        UUID owner=subjects.currentSubject().id();
        mapper.serialize(owner+":"+request.clientRequestId());
        String hash=hash(TaskJson.write(request));
        TaskEntity existing=mapper.byRequest(owner,request.clientRequestId());
        if(existing!=null) {
            if(existing.getDeleted()!=0 || !hash.equals(existing.getRequestHash()))
                throw conflict("TASK_REQUEST_CONFLICT", "Request key was already used");
            return view(existing);
        }
        TaskState state=new TaskState(); state.goal=request.goal().strip();
        state.sources=sources.snapshot(request.knowledgeBaseId(),request.documentIds());
        state.deadline=OffsetDateTime.now().plusSeconds(180);
        TaskEntity row=new TaskEntity(); row.setId(UUID.randomUUID()); row.setOwnerId(owner);
        row.setKnowledgeBaseId(request.knowledgeBaseId()); row.setClientRequestId(request.clientRequestId());
        row.setRequestHash(hash); row.setStatus(TaskStatus.PLANNING); row.setRevision(0);
        row.setExecutionId(UUID.randomUUID()); row.setCheckpoint(TaskJson.write(state)); row.setDeleted(0);
        row.setCreatedAt(OffsetDateTime.now()); row.setUpdatedAt(row.getCreatedAt());
        mapper.create(row); mapper.event(UUID.randomUUID(),row,"CREATED");
        return view(row);
    }

    @Transactional(readOnly=true)
    public TaskRequests.View get(UUID id) { return view(require(mapper.owned(id,subjects.currentSubject().id()))); }

    @Transactional(readOnly=true)
    public List<TaskRequests.View> list(int page, int size) {
        if(page<1 || size<1 || size>50) throw new BadRequestException("TASK_PAGE_INVALID","Invalid page");
        return mapper.list(subjects.currentSubject().id(),size,((long)page-1)*size).stream().map(this::view).toList();
    }

    @Transactional(readOnly=true)
    public Map<String,Object> events(UUID id) {
        require(mapper.owned(id,subjects.currentSubject().id()));
        var events=mapper.events(id);
        return Map.of("items",events.stream().limit(100).toList(),"truncated",events.size()>100);
    }

    @Transactional
    public TaskRequests.View command(UUID id, TaskRequests.Command command) {
        var row=require(mapper.lockOwned(id,subjects.currentSubject().id()));
        if(row.getRevision()!=command.expectedRevision()) throw conflict("TASK_REVISION_CONFLICT","Task changed; refresh before retrying");
        var state=state(row);
        switch(command.operation()) {
            case START -> {
                expect(row,TaskStatus.READY);
                sources.verify(row.getKnowledgeBaseId(),state);
                state.phase="RUNNING"; activate(row,state,TaskStatus.RUNNING);
            }
            case PAUSE -> {
                if(!row.getStatus().active()) throw invalidTransition();
                row.setStatus(TaskStatus.PAUSED); row.setExecutionId(null); closeCalls(state,"INTERRUPTED");
            }
            case CANCEL -> {
                if(row.getStatus()==TaskStatus.CANCELLED) return view(row);
                row.setStatus(TaskStatus.CANCELLED); row.setExecutionId(null); closeCalls(state,"CANCELLED");
            }
            case RESUME -> {
                if(row.getStatus()!=TaskStatus.PAUSED && row.getStatus()!=TaskStatus.FAILED) throw invalidTransition();
                sources.verify(row.getKnowledgeBaseId(),state);
                activate(row,state,TaskStatus.valueOf(state.phase));
            }
            case SUPPLEMENT -> {
                if(row.getStatus().active() || row.getStatus()==TaskStatus.CANCELLED) throw invalidTransition();
                if(command.text()==null || command.text().isBlank() || command.text().length()>2000)
                    throw new BadRequestException("TASK_SUPPLEMENT_INVALID","A supplement of 1 to 2000 characters is required");
                if(state.round>=5) throw conflict("TASK_ROUND_LIMIT","At most five rounds per task");
                state.supplements.add(command.text().strip()); state.round++;
                state.sources=sources.snapshot(row.getKnowledgeBaseId(),state.sources.stream().map(TaskState.Source::id).toList());
                state.plan=null; state.completed.clear(); state.evidence.clear(); state.calls.clear(); state.answer=null;
                state.phase="PLANNING"; state.activations=0; activate(row,state,TaskStatus.PLANNING);
            }
        }
        save(row,state,command.operation().name());
        return view(row);
    }

    private void activate(TaskEntity row,TaskState state,TaskStatus status) {
        if(state.activations>=5) throw conflict("TASK_ACTIVATION_LIMIT","At most five activations per round; start a new round with a supplement");
        state.activations++; state.deadline=OffsetDateTime.now().plusSeconds(180); state.errorCode=null;
        row.setStatus(status); row.setExecutionId(UUID.randomUUID());
    }

    @Transactional
    public void delete(UUID id,long revision) {
        var row=require(mapper.lockOwned(id,subjects.currentSubject().id()));
        if(row.getRevision()!=revision) throw conflict("TASK_REVISION_CONFLICT","Task changed");
        if(row.getStatus().active()) throw invalidTransition();
        row.setDeleted(1); row.setExecutionId(null); save(row,state(row),"DELETED");
    }

    @Transactional(readOnly=true)
    public TaskEntity internal(UUID id) { return mapper.selectById(id); }

    /** Only the current activation may append a checkpoint. Caller cannot revive a stopped task. */
    @Transactional
    public boolean checkpoint(UUID id, UUID execution, String event, Consumer<TaskState> update, TaskStatus status) {
        var row=mapper.lock(id);
        if(row==null || !row.getStatus().active() || !Objects.equals(execution,row.getExecutionId())) return false;
        var state=state(row);
        if(state.deadline.isBefore(OffsetDateTime.now())) {
            state.errorCode="TASK_DEADLINE_EXCEEDED"; row.setStatus(TaskStatus.FAILED); row.setExecutionId(null);
            closeCalls(state,"TIMED_OUT"); save(row,state,"TIMED_OUT"); return false;
        }
        if(status==TaskStatus.COMPLETED || status==TaskStatus.READY || status==TaskStatus.INSUFFICIENT_EVIDENCE)
            sources.verify(row.getKnowledgeBaseId(),state);
        update.accept(state);
        if(status!=null) { row.setStatus(status); if(!status.active()) row.setExecutionId(null); }
        save(row,state,event); return true;
    }

    @Transactional
    public void fail(UUID id,UUID execution,String code) {
        var row=mapper.lock(id);
        if(row==null || !row.getStatus().active() || !Objects.equals(execution,row.getExecutionId())) return;
        var state=state(row); state.errorCode=code; row.setStatus(TaskStatus.FAILED); row.setExecutionId(null);
        closeCalls(state,"FAILED"); save(row,state,"FAILED");
    }

    @Transactional
    public void recover() {
        for(var id:mapper.active()) {
            var row=mapper.lock(id);
            if(row==null || !row.getStatus().active()) continue;
            var state=state(row); state.errorCode="TASK_SERVER_RESTARTED";
            row.setStatus(TaskStatus.PAUSED); row.setExecutionId(null); closeCalls(state,"INTERRUPTED"); save(row,state,"RECOVERED");
        }
    }

    private void save(TaskEntity row,TaskState state,String event) {
        row.setCheckpoint(TaskJson.write(state)); row.setRevision(row.getRevision()+1); row.setUpdatedAt(OffsetDateTime.now());
        mapper.save(row); mapper.event(UUID.randomUUID(),row,event);
    }
    private void closeCalls(TaskState state,String status) {
        state.calls.replaceAll(c->"STARTED".equals(c.status())?
                new TaskState.Call(c.phase(),status,c.model(),c.inputTokens(),c.outputTokens(),c.totalTokens(),c.latencyMs()):c);
    }
    static TaskState state(TaskEntity row) { return TaskJson.read(row.getCheckpoint(),TaskState.class); }
    TaskRequests.View view(TaskEntity row) { return new TaskRequests.View(row.getId(),row.getKnowledgeBaseId(),row.getStatus(),row.getRevision(),state(row),row.getCreatedAt(),row.getUpdatedAt()); }
    private TaskEntity require(TaskEntity row) {
        if(row==null) throw new ResourceNotFoundException("TASK_NOT_FOUND","Research task not found"); return row;
    }
    private void expect(TaskEntity row,TaskStatus expected) { if(row.getStatus()!=expected) throw invalidTransition(); }
    private ConflictException invalidTransition() { return conflict("TASK_STATE_CONFLICT","Command is not allowed in the current state"); }
    private static ConflictException conflict(String code,String message) { return new ConflictException(code,message); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
