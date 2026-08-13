package io.github.fanqiepi.contextpilot.action;

import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/action-requests")
@Tag(name = "业务操作确认", description = "查询、确认或拒绝服务端持久化的业务操作提案")
public class ActionRequestController {

    private final ActionRequestService actionRequestService;

    public ActionRequestController(ActionRequestService actionRequestService) {
        this.actionRequestService = actionRequestService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询业务操作提案")
    public ActionRequestResponse get(@PathVariable UUID id) {
        return actionRequestService.get(id);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "确认并执行等待中的业务操作")
    public ActionRequestResponse confirm(@PathVariable UUID id) {
        return actionRequestService.confirm(id);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "拒绝等待中的业务操作")
    public ActionRequestResponse reject(@PathVariable UUID id) {
        return actionRequestService.reject(id);
    }
}
