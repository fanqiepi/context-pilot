package io.github.fanqiepi.contextpilot.taskworkspace;

import tools.jackson.databind.json.JsonMapper;
import io.github.fanqiepi.contextpilot.common.BadRequestException;

final class TaskJson {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static String write(Object value) { return JSON.writeValueAsString(value); }
    static <T> T read(String value, Class<T> type) {
        try { return JSON.readValue(value, type); }
        catch (RuntimeException error) { throw new BadRequestException("TASK_RESPONSE_INVALID", "Task data is invalid"); }
    }
    private TaskJson() { }
}
