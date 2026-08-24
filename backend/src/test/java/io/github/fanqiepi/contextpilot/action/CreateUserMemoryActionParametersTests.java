package io.github.fanqiepi.contextpilot.action;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import io.github.fanqiepi.contextpilot.memory.UserMemoryScope;
import io.github.fanqiepi.contextpilot.memory.UserMemoryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateUserMemoryActionParametersTests {

    @Test
    void fixesTypeScopeAndNormalizedContent() {
        CreateUserMemoryActionParameters parameters = new CreateUserMemoryActionParameters(
                UserMemoryType.RESPONSE_PREFERENCE,
                UserMemoryScope.GLOBAL,
                "  使用 Markdown   要点回答  ",
                null);

        assertThat(parameters.actionType()).isEqualTo(ActionType.CREATE_USER_MEMORY);
        assertThat(parameters.content()).isEqualTo("使用 Markdown 要点回答");
        assertThat(parameters.expiresAt()).isNull();
    }

    @Test
    void rejectsInvalidContentBeforePersistence() {
        assertThatThrownBy(() -> new CreateUserMemoryActionParameters(
                UserMemoryType.RESPONSE_PREFERENCE,
                UserMemoryScope.GLOBAL,
                " ",
                null))
                .isInstanceOfSatisfying(BadRequestException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("USER_MEMORY_CONTENT_INVALID"));
    }
}
