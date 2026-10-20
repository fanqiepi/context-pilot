package io.github.fanqiepi.contextpilot.readingnote;

import java.util.UUID;
import io.github.fanqiepi.contextpilot.book.ReadingText;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reading")
public class ReadingNoteProposalController {
    private final ReadingNoteProposalService proposals;
    private final ReadingNoteService notes;
    public ReadingNoteProposalController(ReadingNoteProposalService proposals,ReadingNoteService notes) {
        this.proposals=proposals;this.notes=notes;
    }
    @PostMapping("/tasks/{id}/note-proposals")
    public ResponseEntity<?> create(@PathVariable UUID id,@RequestBody String body) {
        var result=proposals.create(id,ReadingText.read(body,ReadingNoteProposalService.Create.class));
        return ResponseEntity.status(result.created()?201:200).body(proposals.get(result.id()));
    }
    @GetMapping("/tasks/{id}/note-proposal")
    public Object latest(@PathVariable UUID id) {return java.util.Collections.singletonMap("proposal",proposals.latest(id));}
    @GetMapping("/note-proposals/{id}")
    public Object get(@PathVariable UUID id) {return proposals.get(id);}
    @PostMapping("/note-proposals/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable UUID id,@RequestBody String body) {
        var result=proposals.confirm(id,ReadingText.read(body,ReadingNoteProposalService.Confirm.class));
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
    @PostMapping("/note-proposals/{id}/reject")
    public Object reject(@PathVariable UUID id,@RequestBody(required=false) String body) {
        ReadingText.require(body==null || body.isBlank() || "{}".equals(body.strip()),"拒绝操作不接受额外内容");
        return proposals.reject(id);
    }
    @GetMapping("/notes/{id}/citations/{key}")
    public Object citation(@PathVariable UUID id,@PathVariable String key,jakarta.servlet.http.HttpServletRequest request) {
        ReadingText.require(request.getParameterMap().isEmpty(),"引用不接受扩展范围参数");
        return notes.citation(id,key);
    }
}
