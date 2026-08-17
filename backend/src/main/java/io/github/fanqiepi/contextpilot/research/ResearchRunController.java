package io.github.fanqiepi.contextpilot.research;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@RequestMapping("/api/research-runs")
@ConditionalOnProperty(prefix = "contextpilot.research", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ResearchRunController {
    private final ResearchQueryService queryService;
    private final ResearchRunCommandService commandService;

    public ResearchRunController(
            ResearchQueryService queryService,
            ResearchRunCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
    }

    @GetMapping("/{id}")
    public ResearchRunResponse get(@PathVariable UUID id) {
        return queryService.get(id);
    }

    @PostMapping("/{id}/cancel")
    public ResearchRunResponse cancel(@PathVariable UUID id) {
        return commandService.cancel(id);
    }
}
