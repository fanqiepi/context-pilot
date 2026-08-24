package io.github.fanqiepi.contextpilot.action;

import java.time.OffsetDateTime;
import java.util.Objects;

import io.github.fanqiepi.contextpilot.memory.UserMemoryContent;
import io.github.fanqiepi.contextpilot.memory.UserMemoryScope;
import io.github.fanqiepi.contextpilot.memory.UserMemoryType;

public record CreateUserMemoryActionParameters(
        UserMemoryType memoryType,
        UserMemoryScope scope,
        String content,
        OffsetDateTime expiresAt) implements ActionParameters {

    public CreateUserMemoryActionParameters {
        Objects.requireNonNull(memoryType, "Memory type must not be null");
        Objects.requireNonNull(scope, "Memory scope must not be null");
        if (memoryType != UserMemoryType.RESPONSE_PREFERENCE) {
            throw new IllegalArgumentException("Only response preferences can be created");
        }
        if (scope != UserMemoryScope.GLOBAL) {
            throw new IllegalArgumentException("Only global response preferences can be created");
        }
        content = UserMemoryContent.normalize(content);
    }

    @Override
    public ActionType actionType() {
        return ActionType.CREATE_USER_MEMORY;
    }
}
