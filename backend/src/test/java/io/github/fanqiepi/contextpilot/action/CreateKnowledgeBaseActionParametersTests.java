package io.github.fanqiepi.contextpilot.action;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateKnowledgeBaseActionParametersTests {

    @Test
    void normalizesPersistedParameters() {
        CreateKnowledgeBaseActionParameters parameters =
                new CreateKnowledgeBaseActionParameters("  Java   学习  ", "  后端\n资料  ");

        assertThat(parameters.name()).isEqualTo("Java 学习");
        assertThat(parameters.description()).isEqualTo("后端 资料");
    }

    @Test
    void rejectsBlankAndOversizedParameters() {
        assertThatThrownBy(() -> new CreateKnowledgeBaseActionParameters(" ", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> new CreateKnowledgeBaseActionParameters("a".repeat(101), null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("100");
        assertThatThrownBy(() -> new CreateKnowledgeBaseActionParameters("valid", "a".repeat(1001)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("1000");
    }
}
