package io.github.fanqiepi.contextpilot.action;

import io.github.fanqiepi.contextpilot.memory.UserMemoryCreationResult;
import io.github.fanqiepi.contextpilot.memory.UserMemoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateUserMemoryActionExecutor {

    private final UserMemoryService userMemoryService;

    public CreateUserMemoryActionExecutor(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
    }

    @Transactional(propagation = Propagation.NESTED)
    public ActionExecutionResult execute(
            CreateUserMemoryActionParameters parameters,
            ActionExecutionContext context) {
        UserMemoryCreationResult result = userMemoryService.createConfirmedResponsePreference(
                parameters.content(),
                parameters.expiresAt(),
                context.conversationId(),
                context.userMessageId());
        String summary = result.created()
                ? "回答偏好已保存（ID：%s）".formatted(result.memoryId())
                : "相同的活动回答偏好已存在（ID：%s）".formatted(result.memoryId());
        return new ActionExecutionResult(summary, result.memoryId());
    }
}
