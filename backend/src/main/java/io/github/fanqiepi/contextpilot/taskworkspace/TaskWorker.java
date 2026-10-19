package io.github.fanqiepi.contextpilot.taskworkspace;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import io.github.fanqiepi.contextpilot.common.*;
import io.github.fanqiepi.contextpilot.retrieval.RetrievalService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="contextpilot.task-workspace",name="enabled",havingValue="true")
public class TaskWorker {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(TaskWorker.class);
    private final TaskService tasks;
    private final TaskSources sources;
    private final TaskModelGateway model;
    private final RetrievalService retrieval;
    private final Set<UUID> scheduled=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=pool(1,2,"research-task");
    private final ThreadPoolExecutor calls=pool(2,2,"research-task-call");
    public TaskWorker(TaskService tasks,TaskSources sources,TaskModelGateway model,RetrievalService retrieval) {
        this.tasks=tasks; this.sources=sources; this.model=model; this.retrieval=retrieval;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() { tasks.recover(); }
    public void submit(UUID id) {
        var row=tasks.internal(id);
        if(row==null || !row.getStatus().active() || row.getExecutionId()==null) return;
        UUID token=row.getExecutionId();
        if(!scheduled.add(token)) return;
        try { workers.execute(()->{ try { execute(id,token); } finally { scheduled.remove(token); } }); }
        catch(RejectedExecutionException error) { scheduled.remove(token); tasks.fail(id,token,"TASK_QUEUE_FULL"); }
    }
    private void execute(UUID id,UUID token) {
        try {
            var row=current(id,token); var state=TaskService.state(row);
            sources.verify(row.getKnowledgeBaseId(),state);
            if(row.getStatus()==TaskStatus.PLANNING) {
                var response=modelCall(id,token,"PLANNING",state);
                var plan=TaskPlanValidator.plan(response.text(),state);
                sources.verify(row.getKnowledgeBaseId(),state);
                tasks.checkpoint(id,token,"PLAN_READY",s->s.plan=plan,
                        plan.questions().isEmpty()?TaskStatus.READY:TaskStatus.WAITING_INPUT);
                return;
            }
            if(state.plan==null || state.plan.steps().isEmpty()) throw new IllegalArgumentException("TASK_PLAN_MISSING");
            for(int index=state.completed.size();index<state.plan.steps().size();index++) {
                current(id,token); sources.verify(row.getKnowledgeBaseId(),state);
                var step=state.plan.steps().get(index); int hits=0;
                var evidence=new ArrayList<>(state.evidence);
                for(UUID document:step.documentIds()) {
                    var results=bounded(id,token,()->retrieval.searchDocument(row.getKnowledgeBaseId(),document,step.query(),3,state.traceId));
                    hits+=results.size();
                    for(var result:results) {
                        if(!document.equals(result.documentId())) throw new IllegalArgumentException("TASK_EVIDENCE_SCOPE");
                        if(result.content()==null || result.content().isBlank()) continue;
                        if(evidence.stream().anyMatch(e->e.documentId().equals(document) && e.chunkId().equals(result.chunkId()))) continue;
                        int remaining=12000-evidence.stream().mapToInt(e->e.text().length()).sum();
                        if(remaining<=0 || evidence.size()>=16) break;
                        String text=result.content().substring(0,Math.min(remaining,result.content().length()));
                        evidence.add(new TaskState.Evidence("E"+(evidence.size()+1),document,result.originalFilename(),result.chunkId(),result.pageNumber(),text));
                    }
                }
                sources.verify(row.getKnowledgeBaseId(),state);
                var completed=new TaskState.StepResult(index,hits,"COMPLETED");
                if(!tasks.checkpoint(id,token,"STEP_COMPLETED",s->{s.completed.add(completed);s.evidence=evidence;},null)) return;
                state.completed.add(completed); state.evidence=evidence;
            }
            if(state.evidence.isEmpty()) {
                tasks.checkpoint(id,token,"NO_EVIDENCE",s->s.answer="所选资料未检索到支持当前目标的证据。请补充研究条件或选择更相关的资料。",TaskStatus.INSUFFICIENT_EVIDENCE);
                return;
            }
            var response=modelCall(id,token,"SYNTHESIS",state);
            String answer=TaskPlanValidator.answer(response.text(),state);
            sources.verify(row.getKnowledgeBaseId(),state);
            tasks.checkpoint(id,token,"COMPLETED",s->s.answer=answer,TaskStatus.COMPLETED);
        } catch(CancellationException ignored) {
            // A user command invalidated this activation. Never overwrite their decision.
        } catch(Exception error) {
            Throwable root=error;
            while(root.getCause()!=null && root.getCause()!=root) root=root.getCause();
            LOG.warn("research.task.failed taskId={} executionId={} errorType={} location={}",id,token,
                    root.getClass().getSimpleName(),root.getStackTrace().length==0?"unknown":root.getStackTrace()[0]);
            String code=error instanceof ConflictException c?c.getCode():error instanceof TimeoutException?"TASK_DEADLINE_EXCEEDED":
                    error instanceof IllegalArgumentException?"TASK_MODEL_RESPONSE_INVALID":"TASK_EXECUTION_FAILED";
            tasks.fail(id,token,code);
        }
    }
    private TaskModelGateway.Result modelCall(UUID id,UUID token,String phase,TaskState state) throws Exception {
        var row=current(id,token); int ordinal=TaskService.state(row).calls.size();
        if(ordinal>=10) throw new IllegalArgumentException("TASK_MODEL_BUDGET");
        if(!tasks.checkpoint(id,token,"MODEL_STARTED",s->s.calls.add(new TaskState.Call(phase,"STARTED",null,null,null,null,0)),null))
            throw new CancellationException();
        var result=bounded(id,token,()->model.call(phase,state));
        if(!tasks.checkpoint(id,token,"MODEL_COMPLETED",s->s.calls.set(ordinal,result.call()),null)) throw new CancellationException();
        return result;
    }
    private <T> T bounded(UUID id,UUID token,Callable<T> action) throws Exception {
        Future<T> future=calls.submit(action);
        try {
            while(true) {
                var state=TaskService.state(current(id,token));
                long left=Duration.between(OffsetDateTime.now(),state.deadline).toMillis();
                if(left<=0) throw new TimeoutException();
                try { return future.get(Math.min(left,250),TimeUnit.MILLISECONDS); }
                catch(TimeoutException error) { if(future.isDone()) throw error; }
            }
        } finally { future.cancel(true); }
    }
    private TaskEntity current(UUID id,UUID token) {
        var row=tasks.internal(id);
        if(row==null || !row.getStatus().active() || !Objects.equals(row.getExecutionId(),token)) throw new CancellationException();
        return row;
    }
    private static ThreadPoolExecutor pool(int size,int queue,String name) {
        return new ThreadPoolExecutor(size,size,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(queue),r->{
            Thread t=new Thread(r,name);t.setDaemon(true);return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    @PreDestroy public void close() { workers.shutdownNow(); calls.shutdownNow(); }
}
