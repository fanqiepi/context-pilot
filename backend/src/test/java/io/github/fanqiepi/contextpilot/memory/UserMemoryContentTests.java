package io.github.fanqiepi.contextpilot.memory;

import io.github.fanqiepi.contextpilot.common.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMemoryContentTests {

    @Test
    void normalizesUnicodeWhitespaceAndProducesStableHash() {
        String normalized = UserMemoryContent.normalize("  使用 Ｍａｒｋｄｏｗｎ\n  要点回答  ");

        assertThat(normalized).isEqualTo("使用 Markdown 要点回答");
        assertThat(UserMemoryContent.hash(normalized))
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isEqualTo(UserMemoryContent.hash("使用 Markdown 要点回答"));
    }

    @Test
    void rejectsBlankAndOversizedContent() {
        assertInvalid("   ");
        assertInvalid("甲".repeat(301));
    }

    private void assertInvalid(String content) {
        assertThatThrownBy(() -> UserMemoryContent.normalize(content))
                .isInstanceOfSatisfying(BadRequestException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("USER_MEMORY_CONTENT_INVALID"));
    }
}
