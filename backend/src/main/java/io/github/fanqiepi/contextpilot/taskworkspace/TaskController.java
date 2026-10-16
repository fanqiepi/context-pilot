package io.github.fanqiepi.contextpilot.taskworkspace;

import java.util.*;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/research-tasks")
@ConditionalOnProperty(prefix="contextpilot.task-workspace",name="enabled",havingValue="true")
public class TaskController {
    private final TaskService service;
    private final TaskWorker worker;
    public TaskController(TaskService service,TaskWorker worker) { this.service=service;this.worker=worker; }
    @PostMapping public ResponseEntity<TaskRequests.View> create(@Valid @RequestBody TaskRequests.Create request) {
        var view=service.create(request);worker.submit(view.id());return ResponseEntity.accepted().body(view);
    }
    @GetMapping public List<TaskRequests.View> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return service.list(page,size);
    }
    @GetMapping("/{id}") public TaskRequests.View get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping("/{id}/commands") public TaskRequests.View command(@PathVariable UUID id,@Valid @RequestBody TaskRequests.Command command) {
        var view=service.command(id,command);worker.submit(id);return view;
    }
    @GetMapping("/{id}/events") public Map<String,Object> events(@PathVariable UUID id) { return service.events(id); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id,@RequestParam long expectedRevision) {
        service.delete(id,expectedRevision);return ResponseEntity.noContent().build();
    }
}
