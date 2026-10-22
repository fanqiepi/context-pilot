package io.github.fanqiepi.contextpilot.readingtask;

import org.springframework.web.bind.annotation.*;

/** Status only. Approval is installed from an explicit local operator file, never from HTTP. */
@RestController
@RequestMapping("/api/reading/evaluation")
public class ReadingEvaluationController {
    private final ReadingLiveAuthorization authorization;
    private final ReadingAiGateway ai;
    public ReadingEvaluationController(ReadingLiveAuthorization authorization,ReadingAiGateway ai) { this.authorization=authorization;this.ai=ai; }
    @GetMapping("/status") public Object status() {
        var status=new java.util.LinkedHashMap<>(authorization.status());
        if(Boolean.TRUE.equals(status.get("liveAllowed")))try { ai.requireAuthorized(); }
        catch(io.github.fanqiepi.contextpilot.book.ReadingException e) {status.put("liveAllowed",false);status.put("reason",e.code());}
        return status;
    }
}
