package io.github.fanqiepi.contextpilot.researchreview;

import java.util.*;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(prefix="contextpilot.task-workspace",name="enabled",havingValue="true")
public class ReviewController {
    private final ReviewService service;
    private final ReviewWorker worker;
    public ReviewController(ReviewService service,ReviewWorker worker) {this.service=service;this.worker=worker;}
    @PostMapping("/research-tasks/{taskId}/reviews")
    public ResponseEntity<ReviewData.View> create(@PathVariable UUID taskId,@Valid @RequestBody ReviewData.Create request) {
        var view=service.create(taskId,request);worker.submit(view.id());return ResponseEntity.accepted().body(view);
    }
    @GetMapping("/research-tasks/{taskId}/reviews")
    public List<ReviewData.View> list(@PathVariable UUID taskId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return service.list(taskId,page,size);
    }
    @GetMapping("/research-reviews/{id}") public ReviewData.View get(@PathVariable UUID id) {return service.get(id);}
    @PostMapping("/research-reviews/{id}/cancel")
    public ReviewData.View cancel(@PathVariable UUID id,@Valid @RequestBody ReviewData.Cancel request) {return service.cancel(id,request.expectedRevision());}
    @DeleteMapping("/research-reviews/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id,@RequestParam long expectedRevision) {
        service.delete(id,expectedRevision);return ResponseEntity.noContent().build();
    }
}
