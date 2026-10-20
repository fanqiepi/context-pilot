package io.github.fanqiepi.contextpilot.readingtask;

import java.util.UUID;
import io.github.fanqiepi.contextpilot.book.ReadingText;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/reading")
public class ReadingTaskController {
    private final ReadingTaskService tasks;private final ReadingTaskEvents events;
    public ReadingTaskController(ReadingTaskService tasks,ReadingTaskEvents events) { this.tasks=tasks;this.events=events; }
    @GetMapping("/versions/{id}/index") public Object index(@PathVariable UUID id){return tasks.index(id);}
    @GetMapping("/versions/{id}/index-estimate") public Object estimate(@PathVariable UUID id,@RequestParam long expectedScopeRevision){return tasks.estimate(id,expectedScopeRevision);}
    @PostMapping("/versions/{id}/index-jobs") public ResponseEntity<?> index(@PathVariable UUID id,@RequestBody String body){
        var created=tasks.createIndex(id,ReadingText.read(body,ReadingTaskService.IndexCreate.class));
        return ResponseEntity.status(created.created()?202:200).body(tasks.get(true,created.id()));
    }
    @GetMapping("/index-jobs/{id}") public Object job(@PathVariable UUID id){return tasks.get(true,id);}
    @PostMapping("/index-jobs/{id}/commands") public Object jobCommand(@PathVariable UUID id,@RequestBody String body){return tasks.command(true,id,ReadingText.read(body,ReadingTaskService.Command.class));}
    @PostMapping("/tasks") public ResponseEntity<?> create(@RequestBody String body){
        var created=tasks.create(ReadingText.read(body,ReadingTaskService.Create.class));
        return ResponseEntity.status(created.created()?202:200).body(tasks.get(false,created.id()));
    }
    @GetMapping("/tasks") public Object list(@RequestParam UUID versionId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return tasks.list(versionId,page,size);}
    @GetMapping("/tasks/{id}") public Object get(@PathVariable UUID id){return tasks.get(false,id);}
    @PostMapping("/tasks/{id}/commands") public Object command(@PathVariable UUID id,@RequestBody String body){return tasks.command(false,id,ReadingText.read(body,ReadingTaskService.Command.class));}
    @DeleteMapping("/tasks/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id,@RequestParam long expectedRevision){tasks.delete(id,expectedRevision);return ResponseEntity.noContent().build();}
    @GetMapping("/tasks/{id}/citations/{key}") public Object citation(@PathVariable UUID id,@PathVariable String key,jakarta.servlet.http.HttpServletRequest request){
        ReadingText.require(request.getParameterMap().isEmpty(),"引用不接受扩展范围参数");return tasks.citation(id,key);
    }
    @GetMapping(value="/tasks/{id}/events",produces="text/event-stream")
    public SseEmitter events(@PathVariable UUID id,jakarta.servlet.http.HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");return events.subscribe(id);
    }
}
